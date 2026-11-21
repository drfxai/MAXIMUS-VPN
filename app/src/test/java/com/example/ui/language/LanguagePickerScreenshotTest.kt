package com.example.ui.language

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.example.ui.settings.SettingsHubSample
import com.example.ui.settings.SettingsPreviewFrame
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Records the first-launch picker and a Russian Settings hub into the "reports" artifact for review. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class LanguagePickerScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val record = RoborazziOptions(taskType = RoborazziTaskType.Record)

    private fun capture(name: String) {
        // The emblem glow is an infinite animation, so the clock is advanced by hand.
        compose.mainClock.advanceTimeBy(1_200)
        compose.onRoot().captureRoboImage(
            filePath = "build/reports/tests/language-screenshots/$name.png",
            roborazziOptions = record
        )
    }

    @Test
    fun pickerDefaultsToEnglishAndSwitchesItsTextsToRussian() {
        compose.mainClock.autoAdvance = false
        var chosen: AppLanguage? = null
        compose.setContent {
            SettingsPreviewFrame(dark = true) { LanguagePickerScreen(initial = AppLanguage.ENGLISH, onContinue = { chosen = it }) }
        }
        capture("language-picker-dark-en")
        compose.onNodeWithText("Choose your language").assertIsDisplayed()

        compose.onNodeWithTag("language_ru").performClick()
        capture("language-picker-dark-ru")
        compose.onNodeWithText("Выберите язык").assertIsDisplayed()

        compose.onNodeWithTag("language_continue").performClick()
        assertEquals(AppLanguage.RUSSIAN, chosen)
    }

    @Test
    fun pickerLight() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            SettingsPreviewFrame(dark = false) { LanguagePickerScreen(initial = AppLanguage.RUSSIAN, onContinue = {}) }
        }
        capture("language-picker-light-ru")
        compose.onNodeWithText("Продолжить").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "+ru")
    fun settingsHubInRussian() {
        compose.setContent { SettingsPreviewFrame(dark = true) { SettingsHubSample() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            filePath = "build/reports/tests/language-screenshots/settings-hub-ru.png",
            roborazziOptions = record
        )
        compose.onNodeWithText("Подключение").assertIsDisplayed()
    }
}
