package com.rteats.mpeineo.data

import com.rteats.mpeineo.model.Lesson
import com.rteats.mpeineo.model.ScheduleDay
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

internal object RemoteScheduleMapper {
    private val remoteDateFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")

    fun map(
        target: ScheduleTarget,
        weekStart: LocalDate,
        classes: List<RemoteClassDto>,
        fetchedAtEpochMillis: Long = System.currentTimeMillis(),
    ): ScheduleWeek {
        val byDate = classes.groupBy { LocalDate.parse(it.date, remoteDateFormatter) }
        val days = (0L..6L).map { offset ->
            val date = weekStart.plusDays(offset)
            val lessons = byDate[date].orEmpty()
                .sortedWith(compareBy({ it.beginLesson }, { it.discipline }))
                .map { item ->
                    Lesson(
                        name = item.discipline.trim(),
                        kind = item.kindOfWork.orEmpty().trim(),
                        startTime = item.beginLesson,
                        endTime = item.endLesson,
                        place = item.auditorium.orEmpty().trim(),
                        lecturer = item.lecturer.orEmpty().trim(),
                        groups = listOf(item.stream, item.group, item.subGroup)
                            .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
                            .distinct()
                            .joinToString(", "),
                    )
                }
            ScheduleDay(date = date.toString(), lessons = lessons)
        }
        return ScheduleWeek(
            target = target,
            weekStart = weekStart.toString(),
            days = days,
            fetchedAtEpochMillis = fetchedAtEpochMillis,
        )
    }
}
