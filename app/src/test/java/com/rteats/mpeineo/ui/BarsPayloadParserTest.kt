package com.rteats.mpeineo.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BarsPayloadParserTest {

    @Test
    fun parsesMpeixStyleMarksPayloadAndBuildsControlSchedule() {
        val payload = BarsPayloadParser.parseExtraction(
            """
            {
              "name": "Иванов Иван",
              "group": "А-17-24",
              "semester": "Осенний семестр",
              "disciplines": [
                {
                  "disciplineName": "Метрология",
                  "personName": "Преподаватель И.О.",
                  "assessmentType": "Зачет",
                  "activities": [
                    {
                      "type": "CONTROL_ACTIVITY",
                      "name": "КМ-1",
                      "weight": "0.2",
                      "weekNum": "7",
                      "markAndDate": "4 (07.10.2026)"
                    },
                    {
                      "type": "CURRENT_SCORE",
                      "name": "Балл текущего контроля",
                      "markAndDate": "8.5"
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
        )

        assertEquals("Иванов Иван", payload.name)
        assertEquals("А-17-24", payload.group)
        assertEquals(1, payload.disciplines.size)

        val discipline = payload.disciplines.single()
        assertEquals("8.5", discipline.currentScore)
        assertTrue(discipline.currentMarks.single().contains("4"))

        val state = BarsUiState(disciplines = payload.disciplines)
        assertEquals(1, state.controlSchedule.size)
        assertEquals("7", state.controlSchedule.single().weekNum)
    }

    @Test
    fun parsesPageStateUsedByWebSessionBridge() {
        val page = BarsPayloadParser.parsePageState(
            """
            {
              "url": "https://bars.mpei.ru/bars_web/Auth/Login",
              "path": "/bars_web/auth/login",
              "isLoginPage": true,
              "isAuthFlow": true,
              "isStudentList": false,
              "isMarksPage": false
            }
            """.trimIndent(),
        )

        assertTrue(page.isLoginPage)
        assertTrue(page.isAuthFlow)
        assertEquals("/bars_web/auth/login", page.path)
    }
}
