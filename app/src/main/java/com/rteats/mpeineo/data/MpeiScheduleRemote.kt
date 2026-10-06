package com.rteats.mpeineo.data

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleTargetType
import com.rteats.mpeineo.model.ScheduleWeek
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

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
                    async { runCatching { searchTyped(query.trim(), requestedType) } }
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
            val parsedType = ScheduleTargetType.entries.firstOrNull { it.apiName == dto.type.lowercase() }
                ?: type
            ScheduleTarget(
                id = dto.id,
                name = dto.label.trim(),
                description = dto.description.orEmpty().trim(),
                type = parsedType,
            )
        }
    }

    private suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "MPEI-Neo/0.1 Android")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("MPEI timetable returned HTTP ${response.code}")
            }
            response.body?.string() ?: throw IOException("Empty MPEI timetable response")
        }
    }
}
