package com.rteats.mpeineo.model

enum class ScheduleTargetType(val apiName: String, val displayName: String) {
    GROUP("group", "Группа"),
    PERSON("person", "Преподаватель"),
    ROOM("room", "Аудитория"),
}

data class ScheduleTarget(
    val id: Long,
    val name: String,
    val description: String = "",
    val type: ScheduleTargetType,
)

data class Lesson(
    val name: String,
    val kind: String,
    val startTime: String,
    val endTime: String,
    val place: String,
    val lecturer: String,
    val groups: String,
)

data class ScheduleDay(
    val date: String,
    val lessons: List<Lesson>,
)

data class ScheduleWeek(
    val target: ScheduleTarget,
    val weekStart: String,
    val days: List<ScheduleDay>,
    val fetchedAtEpochMillis: Long,
)

enum class ScheduleSource {
    NETWORK,
    CACHE,
}

data class ScheduleLoad(
    val week: ScheduleWeek,
    val source: ScheduleSource,
)
