package com.rteats.mpeineo.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleTargetType
import com.rteats.mpeineo.model.ScheduleWeek
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal data class RemoteSearchDto(
    val id: Long,
    val label: String,
    val description: String?,
    val type: String,
)

internal data class RemoteClassDto(
    val auditorium: String?,
    val beginLesson: String,
    val endLesson: String,
    val date: String,
    val discipline: String,
    val kindOfWork: String?,
    val lecturer: String?,
    val stream: String?,
    val group: String?,
    val subGroup: String?,
)

class MpeiScheduleRemote(
    private val client: OkHttpClient,
    private val gson: Gson,
    private val baseUrl: HttpUrl = "http://ts.mpei.ru/api/".toHttpUrl(),
) : ScheduleRemoteDataSource {

    override suspend fun search(query: String, type: ScheduleTargetType?): List<ScheduleTarget> {
        require(query.trim().length >= 2) { "Введите минимум 2 символа" }
        return if (type != null) {
            searchTyped(query.trim(), type)
        } else {
            coroutineScope {
                val results = ScheduleTargetType.entries.map { requestedType ->
                    async {
                        try {
                            Result.success(searchTyped(query.trim(), requestedType))
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Throwable) {
                            Result.failure(error)
                        }
                    }
                }.awaitAll()
                val successful = results.mapNotNull { it.getOrNull() }
                if (successful.isEmpty()) {
                    throw results.firstNotNullOf { it.exceptionOrNull() }
                }
                successful.flatten()
                    .distinctBy { "${it.type.apiName}:${it.id}" }
                    .sortedWith(compareBy({ it.type.ordinal }, { it.name }))
            }
        }
    }

    override suspend fun loadWeek(target: ScheduleTarget, weekStart: LocalDate): ScheduleWeek {
        val remoteDateFormatter = DateTimeFormatter.ofPattern("yyyy.MM.dd")
        val finish = weekStart.plusDays(6)
        val url = baseUrl.newBuilder()
            .addPathSegment("schedule")
            .addPathSegment(target.type.apiName)
            .addPathSegment(target.id.toString())
            .addQueryParameter("start", weekStart.format(remoteDateFormatter))
            .addQueryParameter("finish", finish.format(remoteDateFormatter))
            .addQueryParameter("lng", "1")
            .build()

        val body = get(url)
        val listType = object : TypeToken<List<RemoteClassDto>>() {}.type
        val classes: List<RemoteClassDto> = gson.fromJson(body, listType)
        return RemoteScheduleMapper.map(target, weekStart, classes)
    }

    private suspend fun searchTyped(query: String, type: ScheduleTargetType): List<ScheduleTarget> {
        val url = baseUrl.newBuilder()
            .addPathSegment("search")
            .addQueryParameter("term", query)
            .addQueryParameter("type", type.apiName)
            .build()
        val body = get(url)
        val listType = object : TypeToken<List<RemoteSearchDto>>() {}.type
        val items: List<RemoteSearchDto> = gson.fromJson(body, listType)
        return items.mapNotNull { dto ->
            val parsedType = ScheduleTargetType.entries.firstOrNull {
                it.apiName == dto.type.lowercase()
            } ?: type
            ScheduleTarget(
                id = dto.id,
                name = dto.label.trim(),
                description = dto.description.orEmpty().trim(),
                type = parsedType,
            )
        }
    }

    private suspend fun get(url: HttpUrl): String {
        var lastError: IOException? = null

        repeat(2) { attempt ->
            try {
                return getOnce(url)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                lastError = error
                if (attempt == 0) {
                    delay(200)
                }
            }
        }

        throw lastError ?: IOException("MPEI timetable request failed")
    }

    private suspend fun getOnce(url: HttpUrl): String =
        suspendCancellableCoroutine { continuation ->
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", "MPEI-Neo/0.2 Android")
                .build()

            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }

            call.enqueue(
                object : Callback {
                    override fun onFailure(call: Call, error: IOException) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(error)
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        try {
                            response.use {
                                if (!it.isSuccessful) {
                                    throw IOException(
                                        "MPEI timetable returned HTTP ${it.code}",
                                    )
                                }
                                val body = it.body?.string()
                                    ?: throw IOException("Empty MPEI timetable response")
                                if (continuation.isActive) {
                                    continuation.resume(body)
                                }
                            }
                        } catch (error: Throwable) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(error)
                            }
                        }
                    }
                },
            )
        }
}
