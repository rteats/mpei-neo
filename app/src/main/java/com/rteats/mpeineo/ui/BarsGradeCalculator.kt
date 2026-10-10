package com.rteats.mpeineo.ui

import java.util.Locale
import kotlin.math.abs

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
        val remaining: List<GradeForecastSlot>,
        val combinations: List<GradeForecastCombination>,
        val truncated: Boolean,
    ) : GradeForecast

    data class Unavailable(val explanation: String) : GradeForecast
}

internal const val TARGET_EXAM_SUM = 4.2
private const val MAX_COMBINATIONS = 30

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

    val currentSum = slots.sumOf { (it.currentMark ?: 0.0) * it.weight }
    val missing = slots.filter { it.currentMark == null }
    if (currentSum + missing.sumOf { it.weight * 5.0 } + 1e-9 < target) {
        return GradeForecast.Available(currentSum, totalWeight, missing, emptyList(), false)
    }

    if (missing.isEmpty()) {
        return GradeForecast.Available(
            currentSum, totalWeight, missing,
            if (currentSum + 1e-9 >= target) listOf(GradeForecastCombination(emptyList(), currentSum))
            else emptyList(),
            false,
        )
    }

    // Search future grades in ascending order. Prune any branch whose
    // best-case completion cannot reach the threshold; stop once 30
    // combinations are found rather than exploring 4^N possibilities.
    val suffixMax = DoubleArray(missing.size + 1)
    for (i in missing.lastIndex downTo 0) {
        suffixMax[i] = suffixMax[i + 1] + missing[i].weight * 5.0
    }
    val scenarios = ArrayList<GradeForecastCombination>()
    val trial = IntArray(missing.size)
    var truncated = false

    fun solve(index: Int, sum: Double) {
        if (scenarios.size >= MAX_COMBINATIONS) {
            truncated = true
            return
        }
        if (sum + suffixMax[index] + 1e-9 < target) return
        if (index == missing.size) {
            if (sum + 1e-9 >= target) {
                scenarios += GradeForecastCombination(trial.toList(), sum)
            }
            return
        }
        for (grade in 2..5) {
            trial[index] = grade
            solve(index + 1, sum + grade * missing[index].weight)
            if (truncated) return
        }
    }
    solve(0, currentSum)

    return GradeForecast.Available(currentSum, totalWeight, missing, scenarios, truncated)
}

internal fun formatBarsWeightedSum(value: Double): String =
    String.format(Locale.forLanguageTag("ru"), "%.2f", value)
