package com.example.ui.panels.servers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performClick
import com.example.panels.servers.ServerToolCatalog
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Regression: Psiphon must never be a hidden seventh tab; it lives after Hysteria2. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class PsiphonCatalogPlacementTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun psiphonIsClickableImmediatelyAfterHysteria() {
        var opened = false
        val chosen = listOf(ServerToolCatalog.DNSTT, ServerToolCatalog.HYSTERIA2,
            ServerToolCatalog.XUI, ServerToolCatalog.HIDDIFY)
            .map { requireNotNull(ServerToolCatalog.byId(it)) }

        composeRule.setContent {
            MyApplicationTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    InstallCenterTab(
                        tools = chosen,
                        installedOn = emptyMap(),
                        advice = emptyList(),
                        onOpen = {},
                        onAdvice = {},
                        onPsiphonServer = { opened = true }
                    )
                }
            }
        }

        // Assert the actual data-driven catalog order instead of clipped offscreen
        // boundsInRoot coordinates (which are viewport-dependent under Robolectric).
        val order = installCatalogIds(chosen)
        val hysteria = order.indexOf(ServerToolCatalog.HYSTERIA2)
        val psiphon = order.indexOf(PSIPHON_CATALOG_ROW)
        val xui = order.indexOf(ServerToolCatalog.XUI)
        assertTrue("Hysteria2 missing from catalog", hysteria >= 0)
        assertTrue("Psiphon Server must immediately follow Hysteria2", psiphon == hysteria + 1)
        assertTrue("Psiphon Server must precede 3X-UI", xui > psiphon)
        composeRule.onNodeWithText("Psiphon Server").performScrollTo().assertExists().performClick()
        composeRule.runOnIdle { assertTrue("Psiphon detail did not open", opened) }
    }
}
