package com.example

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.home.EarthHero
import com.example.ui.home.EarthMode
import com.example.ui.home.UnlockDnsCard
import com.example.ui.home.UnlockDnsSheetContent
import com.example.ui.home.UnlockServiceOrbit
import com.example.ui.home.UnlockSheetActions
import com.example.ui.home.UnlockStatusPill
import com.example.ui.theme.AppTheme
import com.example.ui.theme.MyApplicationTheme
import com.example.vpn.unlock.BlockReason
import com.example.vpn.unlock.ServerCheck
import com.example.vpn.unlock.ServerHealth
import com.example.vpn.unlock.UnlockDns
import com.example.vpn.unlock.UnlockDnsHealth
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
 * Unlock mode's Home hero and its "Unlock DNS" sheet, drawn from fixed data. The images are always written
 * (to build/reports/tests/unlock-screens, kept with the CI test reports) so a reviewer can see the UI.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class UnlockModeScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val record = RoborazziOptions(taskType = RoborazziTaskType.Record)
    private val now = System.currentTimeMillis()

    private val state = UnlockDns.State(
        rows = listOf(
            UnlockDns.Row("143.20.64.55", false, ServerCheck("143.20.64.55", ServerHealth.HEALTHY, 38, checkedAt = now)),
            UnlockDns.Row("193.233.112.67", false, ServerCheck("193.233.112.67", ServerHealth.HEALTHY, 52, checkedAt = now,
                shape = com.example.vpn.unlock.QueryShape.TC_Z)),
            UnlockDns.Row("45.13.237.213", false, ServerCheck("45.13.237.213", ServerHealth.HEALTHY, 61, checkedAt = now)),
            UnlockDns.Row("5.175.161.3", false, ServerCheck("5.175.161.3", ServerHealth.BLOCKED, null, BlockReason.NO_ANSWER, now))
        ),
        autoPick = true,
        shaping = true,
        active = "143.20.64.55",
        activeMs = 38,
        listFromPack = true,
        listUpdatedAt = now,
        services = UnlockDnsHealth.Service.entries.associateWith { true }
    )

    @Test fun unlockDnsSheet() {
        compose.setContent {
            MyApplicationTheme {
                Box(Modifier.background(AppTheme.colors.surface).padding(vertical = 16.dp)) {
                    UnlockDnsSheetContent(state, object : UnlockSheetActions {})
                }
            }
        }
        compose.onNodeWithTag("unlock_query_shaping").assertIsDisplayed()
        compose.onNodeWithTag("unlock_server_5.175.161.3").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/reports/tests/unlock-screens/unlock_dns_sheet.png", roborazziOptions = record)
    }

    @Test fun unlockedHome() {
        // The Earth animates forever; frames are advanced by hand so the capture does not wait on it.
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MyApplicationTheme {
                Column(
                    Modifier.background(AppTheme.colors.background).padding(horizontal = 20.dp, vertical = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    EarthHero(mode = EarthMode.ON) { UnlockServiceOrbit(state.services) }
                    Text(androidx.compose.ui.res.stringResource(R.string.unlock_state_unlocked),
                        color = AppTheme.colors.statusConnected, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    UnlockStatusPill(state.active!!, state.activeMs)
                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.fillMaxWidth()) { UnlockDnsCard(state.copy(privateDnsStrict = true), onOpen = {}) }
                    Spacer(Modifier.width(1.dp))
                }
            }
        }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("unlock_status_pill").assertIsDisplayed()
        compose.onRoot().captureRoboImage("build/reports/tests/unlock-screens/unlock_home.png", roborazziOptions = record)
    }
}
