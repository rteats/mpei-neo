package com.rteats.mpeineo.ui

import java.util.Locale
import kotlin.math.roundToLong

/** Pure prediction: never writes hypothetical marks to BARS or local grades. */
internal data class GradeForecastSlot(
    val name: String,
    val weight: Double,
    val currentMark: Double?,
)

internal data class GradeForecastCombination(
    val marks: List<Int>,
    val weightedSum: Double,
)

internal sealed interface GradeForecast {
    data class Available(
        val currentSum: Double,
        val totalWeight: Double,
        val completed: List<GradeForecastSlot>,
        val remaining: List<GradeForecastSlot>,
        val combinations: List<GradeForecastCombination>,
        val truncated: Boolean,
    ) : GradeForecast

    data class Unavailable(val explanation: String) : GradeForecast
}

internal const val TARGET_EXAM_SUM = 4.2
private const val MAX_COMBINATIONS = 5
private const val MAX_STATES = 150_000
// The source weights are percentages/fractions. Micro-units preserve ordinary
// BARS weights accurately without floating-point equality comparisons.
private const val SCORE_SCALE = 1_000_000L

private data class ForecastPath(
    val marks: List<Int>,
    val gradeSum: Int,
    val fives: Int,
)

private val preferredPathOrder = Comparator<ForecastPath> { a, b ->
    val total = a.gradeSum.compareTo(b.gradeSum)
    if (total != 0) total
    else {
        val fifths = a.fives.compareTo(b.fives)
        if (fifths != 0) fifths
        else {
            var compared = 0
            for (i in a.marks.indices) {
                compared = a.marks[i].compareTo(b.marks[i])
                if (compared != 0) break
            }
            compared
        }
    }
}

/**
 * BARS weight strings can be "0,25", "0.25", "25" or "25%".
 * Treat percentages and numbers > 1 as percentage points.
 */
internal fun parseBarsWeight(raw: String?): Double? {
    val value = raw?.trim()?.replace(',', '.') ?: return null
    val match = Regex("""^(\d+(?:\.\d+)?)\s*(%)?$""").matchEntire(value) ?: return null
    val number = match.groupValues[1].toDoubleOrNull() ?: return null
    val normalized = if (match.groupValues[2].isNotEmpty() || number > 1.0) {
        number / 100.0
    } else number
    return normalized.takeIf { it.isFinite() && it in 0.0..1.0 }
}

internal fun calculateGradeForecast(
    discipline: BarsDiscipline,
    target: Double = TARGET_EXAM_SUM,
): GradeForecast {
    val controls = discipline.activities.filter { it.type == BarsActivityType.CONTROL_ACTIVITY }
    if (controls.isEmpty()) {
        return GradeForecast.Unavailable("Нет контрольных мероприятий с весами для расчёта.")
    }

    val slots = controls.mapIndexed { index, activity ->
        val weight = parseBarsWeight(activity.weight)
            ?: return GradeForecast.Unavailable(
                "Не удалось определить вес «${activity.name ?: "КМ ${index + 1}"}»: ${activity.weight ?: "не указан"}."
            )
        val grade = activity.markValue?.toDouble()
        if (grade != null && (!grade.isFinite() || grade !in 0.0..5.0)) {
            return GradeForecast.Unavailable(
                "Оценка «${activity.name ?: "КМ ${index + 1}"}» не соответствует пятибалльной шкале."
            )
        }
        GradeForecastSlot(
            name = activity.name?.takeIf(String::isNotBlank) ?: "КМ ${index + 1}",
            weight = weight,
            currentMark = grade,
        )
    }.filter { it.weight > 0.0 }

    if (slots.isEmpty()) {
        return GradeForecast.Unavailable("Все веса контрольных мероприятий равны нулю.")
    }

    val totalWeight = slots.sumOf { it.weight }
    if (totalWeight > 1.0001) {
        return GradeForecast.Unavailable(
            "Сумма весов превышает 1 (${formatBarsWeightedSum(totalWeight)}). Проверьте данные БАРС."
        )
    }


    // Already-awarded marks are fixed; only ungraded control activities
    // receive hypothetical grades. Grades of 0, 1 and 2 already in BARS
    // still contribute their real weighted values.
    val completed = slots.filter { it.currentMark != null }
    val currentSum = completed.sumOf { it.currentMark!! * it.weight }
    val missing = slots.filter { it.currentMark == null }
    if (currentSum + missing.sumOf { it.weight * 5.0 } + 1e-9 < target) {
        return GradeForecast.Available(
            currentSum, totalWeight, completed, missing, emptyList(), false,
        )
    }

    if (missing.isEmpty()) {
        return GradeForecast.Available(
            currentSum, totalWeight, completed, missing,
            if (currentSum + 1e-9 >= target)
                listOf(GradeForecastCombination(emptyList(), currentSum))
            else emptyList(),
            false,
        )
    }

    // The lowest possible future mark is 3. Start with 3 in every
    // ungraded slot and search only the additional contribution of 4/5.
    // This makes the optimum the smallest weighted result >= target;
    // exact 4.2 wins whenever it is achievable.
    val units = missing.map { (it.weight * SCORE_SCALE).roundToLong() }
    if (units.any { it <= 0L }) {
        return GradeForecast.Unavailable(
            "Вес одного из мероприятий слишком мал для точного прогноза.",
        )
    }
    val baselineUnits = (currentSum * SCORE_SCALE).roundToLong() +
        units.sum() * 3L
    val targetUnits = (target * SCORE_SCALE).roundToLong()
    if (baselineUnits >= targetUnits) {
        val marks = List(missing.size) { 3 }
        return GradeForecast.Available(
            currentSum, totalWeight, completed, missing,
            listOf(GradeForecastCombination(
                marks, currentSum + missing.sumOf { it.weight * 3.0 },
            )),
            false,
        )
    }

    // Dynamic programming merges partial scenarios with the same weighted
    // sum. Each sum keeps up to five cheapest grade sequences. This avoids
    // depending on the first 30 DFS matches, which were not minimal.
    val suffixMax = LongArray(units.size + 1)
    for (index in units.lastIndex downTo 0) {
        suffixMax[index] = suffixMax[index + 1] + 2L * units[index]
    }
    var bestPossibleSum = baselineUnits + suffixMax[0]
    var states: Map<Long, List<ForecastPath>> = mapOf(
        0L to listOf(ForecastPath(emptyList(), 0, 0)),
    )
    var truncated = false

    for (index in units.indices) {
        val next = HashMap<Long, MutableList<ForecastPath>>()
        for ((extra, paths) in states) {
            for (grade in 3..5) {
                val newExtra = extra + (grade - 3L) * units[index]
                val resultingSum = baselineUnits + newExtra
                if (resultingSum > bestPossibleSum ||
                    resultingSum + suffixMax[index + 1] < targetUnits
                ) continue
                if (resultingSum >= targetUnits && resultingSum < bestPossibleSum) {
                    bestPossibleSum = resultingSum
                }
                val candidates = next.getOrPut(newExtra) { mutableListOf() }
                for (path in paths) {
                    val candidate = ForecastPath(
                        marks = path.marks + grade,
                        gradeSum = path.gradeSum + grade,
                        fives = path.fives + if (grade == 5) 1 else 0,
                    )
                    if (candidate in candidates) continue
                    candidates.add(candidate)
                    candidates.sortWith(preferredPathOrder)
                    if (candidates.size > MAX_COMBINATIONS) {
                        candidates.removeAt(candidates.lastIndex)
                        truncated = true
                    }
                }
            }
        }
        if (next.size > MAX_STATES) {
            return GradeForecast.Unavailable(
                "Слишком много различных сочетаний весов для точного расчёта.",
            )
        }
        states = next
    }

    val bestExtra = states.keys
        .filter { baselineUnits + it >= targetUnits }
        .minOrNull()
        ?: return GradeForecast.Available(
            currentSum, totalWeight, completed, missing, emptyList(), false,
        )
    val bestPaths = states[bestExtra].orEmpty()
    val minGradeSum = bestPaths.minOf { it.gradeSum }
    val fewestFives = bestPaths
        .filter { it.gradeSum == minGradeSum }
        .minOf { it.fives }
    val combinations = bestPaths
        .filter { it.gradeSum == minGradeSum && it.fives == fewestFives }
        .map { path ->
            GradeForecastCombination(
                marks = path.marks,
                weightedSum = currentSum + missing.indices.sumOf { i ->
                    path.marks[i] * missing[i].weight
                },
            )
        }
    return GradeForecast.Available(
        currentSum, totalWeight, completed, missing, combinations, truncated,
    )
}

internal fun formatBarsWeightedSum(value: Double): String =
    String.format(Locale.forLanguageTag("ru"), "%.2f", value)
