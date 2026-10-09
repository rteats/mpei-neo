package com.rteats.mpeineo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LessonPeriodsTest {
    @Test
    fun standardMpeiPeriods() {
        assertEquals(1, lessonPeriodNumber("09:20"))
        assertEquals(2, lessonPeriodNumber("11:10"))
        assertEquals(3, lessonPeriodNumber("13:45"))
        assertEquals(4, lessonPeriodNumber("15:35"))
        assertEquals(5, lessonPeriodNumber("17:20"))
        assertEquals(6, lessonPeriodNumber("18:55"))
        assertEquals(7, lessonPeriodNumber("20:30"))
    }

    @Test
    fun periodIsNotReindexedWhenEarlierLessonsAreMissing() {
        assertEquals(7, lessonPeriodNumber("20:30"))
        assertEquals(6, lessonPeriodNumber("18:55"))
        assertEquals(6, lessonPeriodNumber("18:55"))
    }

    @Test
    fun nonstandardTimesAreNotAssignedAnIncorrectNumber() {
        assertNull(lessonPeriodNumber("13:10"))
        assertNull(lessonPeriodNumber("bad input"))
        assertNull(lessonPeriodNumber(""))
    }
}
