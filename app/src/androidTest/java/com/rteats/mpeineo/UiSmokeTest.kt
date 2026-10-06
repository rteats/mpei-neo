package com.rteats.mpeineo

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
    fun settingsNavigationWorks() {
        composeRule
            .onNodeWithText("Настройки")
            .performClick()

        composeRule
            .onNodeWithText("Обновлять при запуске")
            .assertIsDisplayed()

        composeRule
            .onNodeWithText("Локальный кэш")
            .assertIsDisplayed()
    }
}
