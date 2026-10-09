package com.rteats.mpeineo.ui

import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Published standard timetable for НИУ МЭИ. Number by the actual start time,
 * not by a card's position (groups and teachers can have simultaneous lessons
 * or only late-evening lessons in their timetable).
 */
private val mpeiPeriodStarts = mapOf(
    LocalTime.of(9, 20) to 1,
    LocalTime.of(11, 10) to 2,
    LocalTime.of(13, 45) to 3,
    LocalTime.of(15, 35) to 4,
    LocalTime.of(17, 20) to 5,
    LocalTime.of(18, 55) to 6,
    LocalTime.of(20, 30) to 7,
)

internal fun lessonPeriodNumber(startTime: String): Int? =
    runCatching {
        val start = LocalTime.parse(startTime.trim(), DateTimeFormatter.ofPattern("H:mm"))
        mpeiPeriodStarts[start]
    }.getOrNull()
