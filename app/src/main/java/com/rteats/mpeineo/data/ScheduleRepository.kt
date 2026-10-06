package com.rteats.mpeineo.data

import com.rteats.mpeineo.model.ScheduleLoad
import com.rteats.mpeineo.model.ScheduleSource
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleTargetType
import java.time.LocalDate
import kotlinx.coroutines.CancellationException

class ScheduleRepository(
    private val remote: ScheduleRemoteDataSource,
    private val cache: ScheduleCacheStore,
) {
    suspend fun search(query: String, type: ScheduleTargetType?): List<ScheduleTarget> =
        remote.search(query, type)

    suspend fun cachedWeek(
        target: ScheduleTarget,
        weekStart: LocalDate,
    ): ScheduleLoad? =
        cache.read(target, weekStart)?.let { ScheduleLoad(it, ScheduleSource.CACHE) }

    suspend fun loadWeek(
        target: ScheduleTarget,
        weekStart: LocalDate,
        forceNetwork: Boolean,
    ): ScheduleLoad {
        val cached = cache.read(target, weekStart)
        if (!forceNetwork && cached != null) {
            return ScheduleLoad(cached, ScheduleSource.CACHE)
        }

        return try {
            val fresh = remote.loadWeek(target, weekStart)
            cache.write(fresh)
            ScheduleLoad(fresh, ScheduleSource.NETWORK)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (cached != null) ScheduleLoad(cached, ScheduleSource.CACHE) else throw error
        }
    }

    suspend fun clearCache() = cache.clear()
}
