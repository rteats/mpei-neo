package com.rteats.mpeineo.model

internal fun ScheduleTarget.isUsable(): Boolean =
    runCatching {
        id >= 0L &&
            name.isNotBlank() &&
            type.apiName.isNotBlank()
    }.getOrDefault(false)

internal fun Lesson.isUsable(): Boolean =
    runCatching {
        name.isNotBlank() &&
            startTime.isNotBlank() &&
            endTime.isNotBlank()
    }.getOrDefault(false)

internal fun ScheduleDay.isUsable(): Boolean =
    runCatching {
        LocalDateValidator.isIsoDate(date) &&
            lessons.all { it.isUsable() }
    }.getOrDefault(false)

internal fun ScheduleWeek.isUsable(): Boolean =
    runCatching {
        target.isUsable() &&
            LocalDateValidator.isIsoDate(weekStart) &&
            days.size == 7 &&
            days.all { it.isUsable() }
    }.getOrDefault(false)

private object LocalDateValidator {
    fun isIsoDate(raw: String): Boolean =
        runCatching { java.time.LocalDate.parse(raw) }.isSuccess
}
