package com.example.ui.panels.servers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
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

        val hysteria = composeRule.onNodeWithText("Hysteria2").fetchSemanticsNode().boundsInRoot.top
        val psiphon = composeRule.onNodeWithText("Psiphon Server").fetchSemanticsNode().boundsInRoot.top
        val xui = composeRule.onNodeWithText("3X-UI").fetchSemanticsNode().boundsInRoot.top
        assertTrue("Psiphon should follow Hysteria2", hysteria < psiphon)
        assertTrue("Psiphon should precede the Panels section", psiphon < xui)
        composeRule.onNodeWithText("Psiphon Server").assertExists().performClick()
        composeRule.runOnIdle { assertTrue("Psiphon detail did not open", opened) }
    }
}
