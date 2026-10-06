package com.rteats.mpeineo.model

import com.google.gson.GsonBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelValidationTest {

    private val gson = GsonBuilder().create()

    @Test
    fun malformedPersistedTargetIsRejectedInsteadOfCrashingUi() {
        val target = gson.fromJson(
            """{"id":123,"name":"А-12-23","description":""}""",
            ScheduleTarget::class.java,
        )

        assertFalse(target.isUsable())
    }

    @Test
    fun validPersistedTargetIsAccepted() {
        val target = gson.fromJson(
            """{"id":123,"name":"А-12-23","description":"","type":"GROUP"}""",
            ScheduleTarget::class.java,
        )

        assertTrue(target.isUsable())
    }

    @Test
    fun malformedCachedWeekIsRejected() {
        val week = gson.fromJson(
            """{"weekStart":"2026-10-05","days":[]}""",
            ScheduleWeek::class.java,
        )

        assertFalse(week.isUsable())
    }
}
