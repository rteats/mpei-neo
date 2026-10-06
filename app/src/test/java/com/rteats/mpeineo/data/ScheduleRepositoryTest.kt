package com.rteats.mpeineo.data

import com.rteats.mpeineo.model.ScheduleDay
import com.rteats.mpeineo.model.ScheduleSource
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleTargetType
import com.rteats.mpeineo.model.ScheduleWeek
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ScheduleRepositoryTest {

    private val target = ScheduleTarget(
        id = 1,
        name = "А-12-23",
        description = "",
        type = ScheduleTargetType.GROUP,
    )
    private val weekStart = LocalDate.of(2026, 10, 5)

    @Test
    fun usesCacheWithoutCallingNetwork() = runBlocking {
        val cached = sampleWeek(100)
        val remote = FakeRemote(sampleWeek(200))
        val cache = FakeCache(cached)
        val repository = ScheduleRepository(remote, cache)

        val result = repository.loadWeek(
            target = target,
            weekStart = weekStart,
            forceNetwork = false,
        )

        assertEquals(ScheduleSource.CACHE, result.source)
        assertEquals(100, result.week.fetchedAtEpochMillis)
        assertFalse(remote.loadCalled)
    }

    @Test
    fun cachedWeekReadsCacheWithoutCallingNetwork() = runBlocking {
        val cached = sampleWeek(100)
        val remote = FakeRemote(sampleWeek(200))
        val repository = ScheduleRepository(remote, FakeCache(cached))

        val result = repository.cachedWeek(target, weekStart)

        assertNotNull(result)
        assertEquals(ScheduleSource.CACHE, result?.source)
        assertEquals(cached, result?.week)
        assertFalse(remote.loadCalled)
    }

    @Test
    fun forcedRefreshWritesFreshNetworkValue() = runBlocking {
        val cached = sampleWeek(100)
        val fresh = sampleWeek(200)
        val remote = FakeRemote(fresh)
        val cache = FakeCache(cached)
        val repository = ScheduleRepository(remote, cache)

        val result = repository.loadWeek(
            target = target,
            weekStart = weekStart,
            forceNetwork = true,
        )

        assertEquals(ScheduleSource.NETWORK, result.source)
        assertEquals(200, result.week.fetchedAtEpochMillis)
        assertTrue(remote.loadCalled)
        assertEquals(fresh, cache.value)
    }

    @Test
    fun cancellationDoesNotFallBackToCache() = runBlocking {
        val cached = sampleWeek(100)
        val remote = FakeRemote(
            week = sampleWeek(200),
            cancelLoad = true,
        )
        val repository = ScheduleRepository(remote, FakeCache(cached))

        try {
            repository.loadWeek(
                target = target,
                weekStart = weekStart,
                forceNetwork = true,
            )
            fail("CancellationException should propagate")
        } catch (_: CancellationException) {
            // Expected: superseded network work must stop instead of returning stale cache.
        }
    }

    @Test
    fun networkFailureFallsBackToExistingCache() = runBlocking {
        val cached = sampleWeek(100)
        val remote = FakeRemote(
            week = sampleWeek(200),
            failLoad = true,
        )
        val cache = FakeCache(cached)
        val repository = ScheduleRepository(remote, cache)

        val result = repository.loadWeek(
            target = target,
            weekStart = weekStart,
            forceNetwork = true,
        )

        assertEquals(ScheduleSource.CACHE, result.source)
        assertEquals(cached, result.week)
    }

    private fun sampleWeek(timestamp: Long): ScheduleWeek =
        ScheduleWeek(
            target = target,
            weekStart = weekStart.toString(),
            days = (0L..6L).map { offset ->
                ScheduleDay(
                    date = weekStart.plusDays(offset).toString(),
                    lessons = emptyList(),
                )
            },
            fetchedAtEpochMillis = timestamp,
        )

    private class FakeRemote(
        private val week: ScheduleWeek,
        private val failLoad: Boolean = false,
        private val cancelLoad: Boolean = false,
    ) : ScheduleRemoteDataSource {
        var loadCalled = false

        override suspend fun search(
            query: String,
            type: ScheduleTargetType?,
        ): List<ScheduleTarget> = emptyList()

        override suspend fun loadWeek(
            target: ScheduleTarget,
            weekStart: LocalDate,
        ): ScheduleWeek {
            loadCalled = true
            if (cancelLoad) throw CancellationException("cancelled")
            if (failLoad) error("network down")
            return week
        }
    }

    private class FakeCache(
        var value: ScheduleWeek?,
    ) : ScheduleCacheStore {
        override suspend fun read(
            target: ScheduleTarget,
            weekStart: LocalDate,
        ): ScheduleWeek? = value

        override suspend fun write(week: ScheduleWeek) {
            value = week
        }

        override suspend fun clear() {
            value = null
        }
    }
}
