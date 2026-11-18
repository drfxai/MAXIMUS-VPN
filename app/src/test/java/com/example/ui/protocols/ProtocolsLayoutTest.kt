package com.example.ui.protocols

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.example.ui.theme.MyApplicationTheme
import com.example.vpn.lab.LabFamily
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.RoborazziTaskType
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = RobolectricDeviceQualifiers.Pixel8, sdk = [34])
class ProtocolsLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun narrowHeaderShowsFullTitleAndSourceRemainsClickable() {
        var sourceOpened = false
        compose.setContent {
            MyApplicationTheme(darkTheme = true) {
                Box(Modifier.width(320.dp)) {
                    ProtocolsContent(ProtocolsUiState(), ProtocolsActions(onPickSource = { sourceOpened = true }))
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText("Protocols", useUnmergedTree = true)
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val title = layouts.single()
        assertFalse((0 until title.lineCount).any(title::isLineEllipsized))
        compose.onNodeWithText("Saved configs").assertIsDisplayed().performClick()
        compose.runOnIdle { assertTrue(sourceOpened) }
    }

    @Test
    fun darkCatalogShowsTheSavedFamilies() {
        compose.setContent {
            MyApplicationTheme(darkTheme = true) {
                ProtocolsContent(
                    ProtocolsUiState(savedFamilyCounts = mapOf(
                        LabFamily.REALITY to 2, LabFamily.XHTTP to 1, LabFamily.HYSTERIA2 to 3,
                        LabFamily.WIREGUARD to 1, LabFamily.HTTPUPGRADE to 2, LabFamily.WEBSOCKET to 1,
                        LabFamily.CDN to 2
                    )),
                    ProtocolsActions()
                )
            }
        }
        compose.onNodeWithText("Network check").assertIsDisplayed()
        compose.onNodeWithText("Run network test").assertIsDisplayed()
        compose.onNodeWithText("REALITY").assertIsDisplayed()
        compose.onRoot().captureRoboImage(
            filePath = "build/reports/tests/protocols-screenshots/protocols-dark.png",
            roborazziOptions = RoborazziOptions(taskType = RoborazziTaskType.Record)
        )
    }
}
