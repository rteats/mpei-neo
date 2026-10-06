package com.rteats.mpeineo.data

import com.google.gson.GsonBuilder
import com.rteats.mpeineo.model.ScheduleTarget
import com.rteats.mpeineo.model.ScheduleTargetType
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MpeiScheduleRemoteTest {

    private lateinit var server: MockWebServer
    private lateinit var remote: MpeiScheduleRemote

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        remote = MpeiScheduleRemote(
            client = OkHttpClient(),
            gson = GsonBuilder().create(),
            baseUrl = server.url("/api/"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun typedSearchMapsMpeiResult() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    [
                      {
                        "id": 123,
                        "label": "А-12-23",
                        "description": "Институт ИВТИ",
                        "type": "group"
                      }
                    ]
                    """.trimIndent(),
                ),
        )

        val results = remote.search(
            query = "А-12",
            type = ScheduleTargetType.GROUP,
        )

        assertEquals(1, results.size)
        assertEquals("А-12-23", results.single().name)
        assertEquals(ScheduleTargetType.GROUP, results.single().type)

        val request = server.takeRequest()
        assertEquals("/api/search", request.requestUrl?.encodedPath)
        assertEquals("А-12", request.requestUrl?.queryParameter("term"))
        assertEquals("group", request.requestUrl?.queryParameter("type"))
    }

    @Test
    fun roomScheduleIsSupportedDirectlyByMpeiApi() = runBlocking {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """
                    [
                      {
                        "auditorium": "Б-200",
                        "beginLesson": "09:20",
                        "endLesson": "10:55",
                        "date": "2026.10.05",
                        "discipline": "Цифровая схемотехника",
                        "kindOfWork": "Лекция",
                        "lecturer": "Иванов И.И.",
                        "stream": null,
                        "group": "А-12-23",
                        "subGroup": null
                      }
                    ]
                    """.trimIndent(),
                ),
        )

        val target = ScheduleTarget(
            id = 77,
            name = "Б-200",
            description = "",
            type = ScheduleTargetType.ROOM,
        )
        val week = remote.loadWeek(
            target = target,
            weekStart = LocalDate.of(2026, 10, 5),
        )

        assertEquals(target, week.target)
        assertEquals(7, week.days.size)
        val lesson = week.days.first().lessons.single()
        assertEquals("Цифровая схемотехника", lesson.name)
        assertEquals("Б-200", lesson.place)
        assertEquals("А-12-23", lesson.groups)

        val request = server.takeRequest()
        assertEquals(
            "/api/schedule/room/77",
            request.requestUrl?.encodedPath,
        )
        assertEquals(
            "2026.10.05",
            request.requestUrl?.queryParameter("start"),
        )
        assertEquals(
            "2026.10.11",
            request.requestUrl?.queryParameter("finish"),
        )
        assertTrue(request.getHeader("User-Agent")!!.startsWith("MPEI-Neo/"))
    }
}
