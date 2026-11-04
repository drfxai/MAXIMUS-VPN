package com.example

import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.example.data.model.*
import com.example.ui.connections.*
import com.example.ui.theme.MyApplicationTheme
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-xxhdpi", sdk = [34])
class GalaxyConnectionsScreenshotTest {
    @get:Rule val compose = createComposeRule()
    private fun render(dark: Boolean, detail: Boolean, direct: Boolean) {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MyApplicationTheme(darkTheme = dark) {
                Box(Modifier.width(360.dp).height(760.dp)) {
                    ConnectionsContent(ConnectionsViewModel.UiState(ConnectionsViewModel.Options(direct = direct)),
                        ConnectionState(), detail, false, true, {}, {}, {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        compose.onRoot().captureRoboImage("build/galaxy-previews/${if (dark) "dark" else "light"}-${if (!detail) "hub" else if(direct) "direct" else "aether"}.png")
    }
    @Test fun darkHub() = render(true, false, false)
    @Test fun lightHub() = render(false, false, false)
    @Test fun darkAether() = render(true, true, false)
    @Test fun lightAether() = render(false, true, false)
    @Test fun darkDirect() = render(true, true, true)
    @Test fun lightDirect() = render(false, true, true)
}
