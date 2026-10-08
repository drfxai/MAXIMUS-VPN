package com.example.ui.popups

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The three community logos, drawn on a 24 x 24 grid so they stay sharp at any size.

private val GitHubMark: Path = PathParser().parsePathString(
    "M12 .297c-6.63 0-12 5.373-12 12 0 5.303 3.438 9.8 8.205 11.385.6.113.82-.258.82-.577 " +
        "0-.285-.01-1.04-.015-2.04-3.338.724-4.042-1.61-4.042-1.61C4.422 18.07 3.633 17.7 3.633 17.7" +
        "c-1.087-.744.084-.729.084-.729 1.205.084 1.838 1.236 1.838 1.236 1.07 1.835 2.809 1.305 " +
        "3.495.998.108-.776.417-1.305.76-1.605-2.665-.3-5.466-1.332-5.466-5.93 0-1.31.465-2.38 " +
        "1.235-3.22-.135-.303-.54-1.523.105-3.176 0 0 1.005-.322 3.3 1.23.96-.267 1.98-.399 " +
        "3-.405 1.02.006 2.04.138 3 .405 2.28-1.552 3.285-1.23 3.285-1.23.645 1.653.24 2.873.12 " +
        "3.176.765.84 1.23 1.91 1.23 3.22 0 4.61-2.805 5.625-5.475 5.92.42.36.81 1.096.81 2.22 " +
        "0 1.606-.015 2.896-.015 3.286 0 .315.21.69.825.57C20.565 22.092 24 17.592 24 12.297" +
        "c0-6.627-5.373-12-12-12"
).toPath()

private val PaperPlane: Path = PathParser().parsePathString(
    "M5.4 11.5 17.3 6.9c.55-.2 1.04.13.86.97l-2.03 9.55c-.15.68-.55.84-1.12.52l-3.1-2.28-1.5 " +
        "1.44c-.16.16-.3.3-.62.3l.22-3.14 5.72-5.17c.25-.22-.05-.34-.39-.12l-7.07 4.45-3.05-.95" +
        "c-.66-.2-.67-.66.14-.98z"
).toPath()

private val PlayTriangle: Path = PathParser().parsePathString("M9.6 8.4v7.2l6.3-3.6z").toPath()

@Composable
fun YouTubeLogo(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width / 24f
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(Color(0xFFFF3B30), Color(0xFFE00000))),
            topLeft = Offset(1f * s, 4.5f * s),
            size = Size(22f * s, 15f * s),
            cornerRadius = CornerRadius(4.2f * s)
        )
        scale(s, pivot = Offset.Zero) { drawPath(PlayTriangle, Color.White) }
    }
}

@Composable
fun GitHubLogo(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width / 24f
        // The mark is a disc with the cat cut out: a white disc underneath shows the cat in white.
        drawCircle(Color.White, radius = 11.4f * s, center = Offset(12f * s, 12.3f * s))
        scale(s, pivot = Offset.Zero) { drawPath(GitHubMark, Color(0xFF0D1117)) }
    }
}

@Composable
fun TelegramLogo(size: Dp = 40.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val s = this.size.width / 24f
        drawCircle(Brush.verticalGradient(listOf(Color(0xFF37BBFE), Color(0xFF1E96DB))), radius = 12f * s)
        scale(s, pivot = Offset.Zero) { translate(-0.4f, 0.1f) { drawPath(PaperPlane, Color.White) } }
    }
}
