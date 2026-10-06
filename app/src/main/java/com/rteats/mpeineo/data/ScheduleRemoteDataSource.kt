package com.rteats.mpeineo.data

import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleTargetType
import com.rteats.mpeineo.model.ScheduleWeek
import java.time.LocalDate

interface ScheduleRemoteDataSource {
    suspend fun search(query: String, type: ScheduleTargetType? = null): List<ScheduleTarget>
    suspend fun loadWeek(target: ScheduleTarget, weekStart: LocalDate): ScheduleWeek
}
