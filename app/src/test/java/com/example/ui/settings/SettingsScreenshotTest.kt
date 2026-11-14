package com.example.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the Settings hub and its main pages in both themes. Images are always recorded into
 * build/reports/tests/settings-screenshots so CI keeps them in the "reports" artifact for review;
 * there are no golden files to compare against.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class SettingsScreenshotTest {

    @get:Rule val compose = createComposeRule()

    private val record = RoborazziOptions(taskType = RoborazziTaskType.Record)

    private fun shoot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent { SettingsPreviewFrame(dark = dark) { content() } }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            filePath = "build/reports/tests/settings-screenshots/$name.png",
            roborazziOptions = record
        )
    }

    @Test
    fun hub_dark() {
        shoot("settings-hub-dark", dark = true) { SettingsHubSample() }
        compose.onNodeWithTag("settings_screen").assertIsDisplayed()
        compose.onNodeWithText("Connection").assertIsDisplayed()
        compose.onNodeWithText("Balanced · Auto engine").assertIsDisplayed()
        compose.onNodeWithText("Up to date").assertIsDisplayed()
    }

    @Test
    fun hub_light() = shoot("settings-hub-light", dark = false) { SettingsHubSample() }

    @Test
    fun connection_dark() {
        shoot("settings-connection-dark", dark = true) { ConnectionPageSample() }
        compose.onNodeWithText("Automated failover").assertIsDisplayed()
        compose.onNodeWithText("Power button fallback").assertIsDisplayed()
    }

    @Test
    fun connection_light() = shoot("settings-connection-light", dark = false) { ConnectionPageSample() }

    @Test
    fun routing_dns_dark() = shoot("settings-routing-dns-dark", dark = true) { RoutingDnsPageSample() }

    @Test
    fun anti_censorship_dark() = shoot("settings-anti-censorship-dark", dark = true) { AntiCensorshipPageSample() }

    @Test
    fun privacy_light() = shoot("settings-privacy-light", dark = false) { PrivacyPageSample() }

    @Test
    fun updates_dark() = shoot("settings-updates-dark", dark = true) { UpdatesPageSample() }

    @Test
    fun diagnostics_dark() {
        shoot("settings-diagnostics-dark", dark = true) { DiagnosticsPageSample() }
        compose.onNodeWithTag("view_diagnostics_button").assertIsDisplayed()
        compose.onNodeWithTag("settings_reset").assertIsDisplayed()
    }
}
