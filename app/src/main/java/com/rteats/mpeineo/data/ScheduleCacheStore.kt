package com.rteats.mpeineo.data

import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleWeek
import java.time.LocalDate

interface ScheduleCacheStore {
    suspend fun read(target: ScheduleTarget, weekStart: LocalDate): ScheduleWeek?
    suspend fun write(week: ScheduleWeek)
    suspend fun clear()
}
