package com.rteats.mpeineo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BarsGradeCalculatorTest {
    private fun activity(name: String, weight: String?, mark: String? = null) =
        BarsActivity(
            type = BarsActivityType.CONTROL_ACTIVITY,
            name = name,
            weight = weight,
            markAndDate = mark,
        )

    @Test fun weightsAcceptFractionsAndPercentages() {
        assertEquals(0.25, parseBarsWeight("0,25")!!, 0.0001)
        assertEquals(0.25, parseBarsWeight("25%")!!, 0.0001)
        assertEquals(0.25, parseBarsWeight("25")!!, 0.0001)
        assertEquals(1.0, parseBarsWeight("100%")!!, 0.0001)
        assertEquals(null, parseBarsWeight("нет"))
        assertEquals(null, parseBarsWeight("105%"))
    }

    @Test fun currentMarksAreWeightedAndUnknownSlotsStayEditable() {
        val discipline = BarsDiscipline(
            activities = listOf(
                activity("КМ 1", "0,25", "5 / 03.10"),
                activity("КМ 2", "0,35"),
                activity("Экзамен", "40%"),
            ),
        )
        val result = calculateGradeForecast(discipline) as GradeForecast.Available
        assertEquals(1.25, result.currentSum, 0.00001)
        assertEquals(1.0, result.totalWeight, 0.00001)
        assertEquals(listOf("КМ 2", "Экзамен"), result.remaining.map { it.name })
        assertTrue(result.combinations.isNotEmpty())
        assertTrue(result.combinations.all { it.weightedSum >= 4.2 - 1e-8 })
        assertTrue(result.combinations.all { it.marks.size == 2 })
        assertTrue(result.combinations.all { combination -> combination.marks.all { it in 3..5 } })
    }

    @Test fun bestForecastUsesActualBARSMarksAndFindsExactThreshold() {
        val discipline = BarsDiscipline(
            activities = listOf(
                activity("Уже оценено", "25%", "5 / 03.10"),
                activity("Следующий КМ", "35%"),
                activity("Экзамен", "40%"),
            ),
        )
        val result = calculateGradeForecast(discipline) as GradeForecast.Available
        assertEquals(1.25, result.currentSum, 0.000001)
        assertEquals(listOf("Уже оценено"), result.completed.map { it.name })
        assertEquals(5.0, result.completed.single().currentMark!!, 0.000001)
        assertEquals(listOf("Следующий КМ", "Экзамен"), result.remaining.map { it.name })
        assertEquals(listOf(5, 3), result.combinations.single().marks)
        assertEquals(4.2, result.combinations.single().weightedSum, 0.000001)
    }

    @Test fun returnsSmallestAchievableScoreAboveThreshold() {
        val result = calculateGradeForecast(
            BarsDiscipline(activities = listOf(
                activity("Первое", "50%"),
                activity("Второе", "50%"),
            )),
        ) as GradeForecast.Available
        assertTrue(result.combinations.isNotEmpty())
        assertTrue(result.combinations.all { it.weightedSum == 4.5 })
        assertTrue(result.combinations.all { it.marks.sum() == 9 })
        assertTrue(result.combinations.all { marks -> marks.marks.all { it in 3..5 } })
    }

    @Test fun whenResultsTiePrefersLessDemandingGradeSequence() {
        val result = calculateGradeForecast(
            BarsDiscipline(activities = listOf(
                activity("Получено", "40%", "5"),
                activity("КМ 2", "30%"),
                activity("КМ 3", "30%"),
            )),
        ) as GradeForecast.Available
        assertEquals(2.0, result.currentSum, 0.000001)
        assertEquals(listOf(4, 4), result.combinations.single().marks)
        assertEquals(4.4, result.combinations.single().weightedSum, 0.000001)
    }

    @Test fun existingLowMarksAreIncludedRatherThanReplaced() {
        val result = calculateGradeForecast(
            BarsDiscipline(activities = listOf(
                activity("Плохо", "25%", "2"),
                activity("Исправить", "75%"),
            )),
        ) as GradeForecast.Available
        assertEquals(0.5, result.currentSum, 0.000001)
        assertEquals(2.0, result.completed.single().currentMark!!, 0.000001)
        assertTrue(result.combinations.isNotEmpty())
        assertEquals(listOf(5), result.combinations.single().marks)
        assertEquals(4.25, result.combinations.single().weightedSum, 0.000001)
    }

    @Test fun whenAlreadyPastThresholdRequiresOnlyThreesOnRemaining() {
        val result = calculateGradeForecast(
            BarsDiscipline(activities = listOf(
                activity("Получено", "90%", "5"),
                activity("Осталось", "10%"),
            )),
        ) as GradeForecast.Available
        assertEquals(listOf(3), result.combinations.single().marks)
        assertEquals(4.8, result.combinations.single().weightedSum, 0.000001)
    }

    @Test fun impossibleThresholdIsNotPresentedAsPassing() {
        val result = calculateGradeForecast(
            BarsDiscipline(activities = listOf(
                activity("Плохой результат", "0.9", "2"),
                activity("Осталось", "0.1"),
            )),
        ) as GradeForecast.Available
        assertTrue(result.combinations.isEmpty())
        assertEquals(1.8, result.currentSum, 0.00001)
    }

    @Test fun completeHighGradesRequireNoHypotheticalMarks() {
        val result = calculateGradeForecast(
            BarsDiscipline(activities = listOf(
                activity("Первое", "50", "5"),
                activity("Второе", "50", "4"),
            )),
        ) as GradeForecast.Available
        assertTrue(result.remaining.isEmpty())
        assertEquals(4.5, result.combinations.single().weightedSum, 0.00001)
    }

    @Test fun unknownWeightCannotBeSilentlyAssumed() {
        val result = calculateGradeForecast(
            BarsDiscipline(activities = listOf(
                activity("Первое", "0,5", "5"),
                activity("Второе", null),
            )),
        )
        assertTrue(result is GradeForecast.Unavailable)
    }

    @Test fun tooManyWeightsDoNotProduceAnInvalidForecast() {
        val result = calculateGradeForecast(
            BarsDiscipline(activities = listOf(
                activity("Первое", "80%", "5"),
                activity("Второе", "80%"),
            )),
        )
        assertTrue(result is GradeForecast.Unavailable)
    }
}
