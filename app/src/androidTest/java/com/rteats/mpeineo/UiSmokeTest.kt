package com.rteats.mpeineo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class UiSmokeTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun primaryDestinationsRender() {
        composeRule
            .onNodeWithTag("schedule-docked-search")
            .performClick()

        composeRule
            .onNodeWithText("Все")
            .assertIsDisplayed()

        composeRule
            .onNodeWithContentDescription("Закрыть поиск")
            .performClick()

        composeRule
            .onNodeWithContentDescription("БАРС")
            .performClick()

        composeRule
            .onNodeWithTag("bars-screen")
            .assertIsDisplayed()

        composeRule
            .onNodeWithContentDescription("Почта")
            .performClick()

        composeRule
            .onNodeWithTag("mail-inbox")
            .assertIsDisplayed()

        composeRule
            .onNodeWithContentDescription("Настройки")
            .performClick()

        composeRule
            .onNodeWithText("Обновления приложения")
            .assertIsDisplayed()

        composeRule
            .onNodeWithText("Локальный кэш")
            .assertIsDisplayed()
    }
}
