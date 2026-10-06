package com.rteats.mpeineo.model

import com.google.gson.annotations.SerializedName

enum class ScheduleTargetType(val apiName: String, val displayName: String) {
    GROUP("group", "Группа"),
    PERSON("person", "Преподаватель"),
    ROOM("room", "Аудитория"),
}

data class ScheduleTarget(
    @SerializedName("id") val id: Long,
    @SerializedName("name") val name: String,
    @SerializedName("description") val description: String = "",
    @SerializedName("type") val type: ScheduleTargetType,
)

data class Lesson(
    @SerializedName("name") val name: String,
    @SerializedName("kind") val kind: String,
    @SerializedName("startTime") val startTime: String,
    @SerializedName("endTime") val endTime: String,
    @SerializedName("place") val place: String,
    @SerializedName("lecturer") val lecturer: String,
    @SerializedName("groups") val groups: String,
)

data class ScheduleDay(
    @SerializedName("date") val date: String,
    @SerializedName("lessons") val lessons: List<Lesson>,
)

data class ScheduleWeek(
    @SerializedName("target") val target: ScheduleTarget,
    @SerializedName("weekStart") val weekStart: String,
    @SerializedName("days") val days: List<ScheduleDay>,
    @SerializedName("fetchedAtEpochMillis") val fetchedAtEpochMillis: Long,
)

enum class ScheduleSource {
    NETWORK,
    CACHE,
}

data class ScheduleLoad(
    val week: ScheduleWeek,
    val source: ScheduleSource,
)
