package com.example.ui.connections

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AppTheme
import kotlin.math.*

/** Code-native artwork scales without bitmap blur, with quiet motion and no external assets. */
@Composable
fun GalaxyBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = AppTheme.colors.isDark
    Box(modifier.background(if (dark) Color(0xFF060711) else Color(0xFFF5F3FF))) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(Brush.radialGradient(listOf(Color(0xFF7948D7).copy(alpha = if (dark) .24f else .10f), Color.Transparent),
                Offset(size.width * .8f, size.height * .12f), size.width * 1.1f))
            drawRect(Brush.radialGradient(listOf(Color(0xFF3D85CB).copy(alpha = if (dark) .10f else .07f), Color.Transparent),
                Offset(size.width * .15f, size.height * .65f), size.width * .85f))
            repeat(68) { i ->
                val x = ((i * 7919L % 1009) / 1009f) * size.width
                val y = ((i * 3571L % 1013) / 1013f) * size.height * .8f
                drawCircle(if (dark) Color(0xFFDED2FF).copy(alpha = .13f + (i % 3) * .06f) else Color.White.copy(alpha = .8f),
                    if (i % 4 == 0) 1.3f else .7f, Offset(x, y))
            }
        }
        content()
    }
}

@Composable
fun GalaxyPlanet(modifier: Modifier = Modifier, animate: Boolean = true) {
    val dark = AppTheme.colors.isDark
    val transition = rememberInfiniteTransition(label = "galaxy")
    val drift by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(120_000, easing = LinearEasing)), label = "orbit")
    Canvas(modifier.height(184.dp).fillMaxWidth()) {
        val c = center; val r = size.height * .34f
        drawCircle(Brush.radialGradient(listOf(Color(0xFFB790FF).copy(alpha = .19f), Color.Transparent), c, r * 2f), r * 2f, c)
        rotate(-22f + if (animate) sin(drift * PI.toFloat() / 180) * 3f else 0f, c) {
            drawOval(Brush.linearGradient(listOf(Color(0xFF9A69F8), Color(0xFF5FD6EC), Color(0xFF9A69F8))),
                c - Offset(r * 1.75f, r * .43f), Size(r * 3.5f, r * .86f), style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx()))
        }
        drawCircle(Brush.radialGradient(if (dark) listOf(Color(0xFFE2C6FF), Color(0xFF7650B4), Color(0xFF24183E), Color(0xFF0A0D23))
            else listOf(Color.White, Color(0xFFE5D8FF), Color(0xFFB7AAED), Color(0xFF8986C1)),
            c - Offset(r * .42f, r * .5f), r * 1.9f), r, c)
        drawCircle(Color(0xFFC8ACFF).copy(alpha = .42f), r, c, style = androidx.compose.ui.graphics.drawscope.Stroke(.6.dp.toPx()))
        rotate(-22f, c) {
            drawArc(Brush.linearGradient(listOf(Color(0xFFD2A6FF), Color(0xFF6CD6F1))), 5f, 170f, false,
                c - Offset(r * 1.75f, r * .43f), Size(r * 3.5f, r * .86f), style = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))
        }
    }
}
