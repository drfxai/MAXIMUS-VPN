package com.example.ui.panels

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CyberGlobeCanvas(
    modifier: Modifier = Modifier.size(90.dp)
) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f
        val center = Offset(size.width / 2f, size.height / 2f)

        // Outer glow
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF00C2FF).copy(alpha = 0.35f),
                    Color(0xFF0088FF).copy(alpha = 0.15f),
                    Color.Transparent
                ),
                center = center,
                radius = radius * 1.25f
            ),
            radius = radius * 1.25f,
            center = center
        )

        // Dark sphere base
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFF0055AA),
                    Color(0xFF0A1C3C),
                    Color(0xFF061024)
                ),
                center = Offset(center.x - radius * 0.3f, center.y - radius * 0.3f),
                radius = radius
            ),
            radius = radius,
            center = center
        )

        // Sphere outline
        drawCircle(
            color = Color(0xFF00D2FF),
            radius = radius,
            center = center,
            style = Stroke(width = 2.dp.toPx())
        )

        // Equator
        drawLine(
            color = Color(0xFF00D2FF).copy(alpha = 0.7f),
            start = Offset(center.x - radius, center.y),
            end = Offset(center.x + radius, center.y),
            strokeWidth = 1.5.dp.toPx()
        )

        // Latitudes
        drawOval(
            color = Color(0xFF00D2FF).copy(alpha = 0.5f),
            topLeft = Offset(center.x - radius * 0.86f, center.y - radius * 0.5f - radius * 0.15f),
            size = Size(radius * 1.72f, radius * 0.3f),
            style = Stroke(width = 1.2.dp.toPx())
        )
        drawOval(
            color = Color(0xFF00D2FF).copy(alpha = 0.5f),
            topLeft = Offset(center.x - radius * 0.86f, center.y + radius * 0.5f - radius * 0.15f),
            size = Size(radius * 1.72f, radius * 0.3f),
            style = Stroke(width = 1.2.dp.toPx())
        )

        // Longitudes (ellipses)
        drawOval(
            color = Color(0xFF00D2FF).copy(alpha = 0.6f),
            topLeft = Offset(center.x - radius * 0.35f, center.y - radius),
            size = Size(radius * 0.7f, radius * 2f),
            style = Stroke(width = 1.2.dp.toPx())
        )
        drawOval(
            color = Color(0xFF00D2FF).copy(alpha = 0.4f),
            topLeft = Offset(center.x - radius * 0.7f, center.y - radius),
            size = Size(radius * 1.4f, radius * 2f),
            style = Stroke(width = 1.2.dp.toPx())
        )

        // Prime meridian
        drawLine(
            color = Color(0xFF00D2FF).copy(alpha = 0.7f),
            start = Offset(center.x, center.y - radius),
            end = Offset(center.x, center.y + radius),
            strokeWidth = 1.5.dp.toPx()
        )

        // Glowing node dots
        val dots = listOf(
            Offset(center.x - radius * 0.4f, center.y - radius * 0.2f),
            Offset(center.x + radius * 0.3f, center.y + radius * 0.3f),
            Offset(center.x + radius * 0.1f, center.y - radius * 0.5f),
            Offset(center.x - radius * 0.2f, center.y + radius * 0.5f),
            Offset(center.x + radius * 0.6f, center.y - radius * 0.1f)
        )
        for (dot in dots) {
            drawCircle(
                color = Color(0xFF67E8F9),
                radius = 2.5.dp.toPx(),
                center = dot
            )
            drawCircle(
                color = Color.White,
                radius = 1.2.dp.toPx(),
                center = dot
            )
        }
    }
}

@Composable
fun CloudflarePlatformCanvas(
    modifier: Modifier = Modifier.size(100.dp)
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height

        // Isometric platform base
        val platformPath = Path().apply {
            moveTo(w * 0.5f, h * 0.62f)
            lineTo(w * 0.95f, h * 0.78f)
            lineTo(w * 0.5f, h * 0.96f)
            lineTo(w * 0.05f, h * 0.78f)
            close()
        }

        // Platform depth
        val depthPath = Path().apply {
            moveTo(w * 0.05f, h * 0.78f)
            lineTo(w * 0.5f, h * 0.96f)
            lineTo(w * 0.95f, h * 0.78f)
            lineTo(w * 0.95f, h * 0.83f)
            lineTo(w * 0.5f, h * 1.0f)
            lineTo(w * 0.05f, h * 0.83f)
            close()
        }

        drawPath(
            path = depthPath,
            brush = Brush.verticalGradient(
                listOf(Color(0xFF0044AA), Color(0xFF001F5C))
            )
        )

        drawPath(
            path = platformPath,
            brush = Brush.radialGradient(
                listOf(Color(0xFF0D3E8C), Color(0xFF07214E)),
                center = Offset(w * 0.5f, h * 0.78f)
            )
        )

        drawPath(
            path = platformPath,
            color = Color(0xFF00C2FF).copy(alpha = 0.8f),
            style = Stroke(width = 1.5.dp.toPx())
        )

        // Platform grid lines
        drawLine(
            color = Color(0xFF00C2FF).copy(alpha = 0.4f),
            start = Offset(w * 0.275f, h * 0.70f),
            end = Offset(w * 0.725f, h * 0.87f),
            strokeWidth = 1.dp.toPx()
        )
        drawLine(
            color = Color(0xFF00C2FF).copy(alpha = 0.4f),
            start = Offset(w * 0.725f, h * 0.70f),
            end = Offset(w * 0.275f, h * 0.87f),
            strokeWidth = 1.dp.toPx()
        )

        // Floating Orange Cloudflare Cloud
        val cloudGlowCenter = Offset(w * 0.5f, h * 0.42f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    Color(0xFFFF9900).copy(alpha = 0.45f),
                    Color(0xFFFF5500).copy(alpha = 0.15f),
                    Color.Transparent
                ),
                center = cloudGlowCenter,
                radius = w * 0.4f
            ),
            radius = w * 0.4f,
            center = cloudGlowCenter
        )

        // Cloud body
        val cloudPath = Path().apply {
            moveTo(w * 0.32f, h * 0.54f)
            // Bottom line
            lineTo(w * 0.74f, h * 0.54f)
            // Right curve
            cubicTo(w * 0.86f, h * 0.54f, w * 0.86f, h * 0.40f, w * 0.76f, h * 0.38f)
            // Top right puff
            cubicTo(w * 0.76f, h * 0.26f, w * 0.60f, h * 0.24f, w * 0.52f, h * 0.32f)
            // Top left main puff
            cubicTo(w * 0.44f, h * 0.24f, w * 0.30f, h * 0.28f, w * 0.28f, h * 0.42f)
            // Left curve
            cubicTo(w * 0.20f, h * 0.44f, w * 0.20f, h * 0.54f, w * 0.32f, h * 0.54f)
            close()
        }

        // Cloud shadow on platform
        drawOval(
            color = Color(0xFF00112D).copy(alpha = 0.6f),
            topLeft = Offset(w * 0.25f, h * 0.74f),
            size = Size(w * 0.5f, h * 0.10f)
        )

        drawPath(
            path = cloudPath,
            brush = Brush.verticalGradient(
                listOf(Color(0xFFFFB300), Color(0xFFFF6D00), Color(0xFFE65100))
            )
        )

        drawPath(
            path = cloudPath,
            color = Color(0xFFFFD54F).copy(alpha = 0.9f),
            style = Stroke(width = 1.5.dp.toPx())
        )
    }
}

@Composable
fun PanelBrandLogo(
    type: String,
    modifier: Modifier = Modifier.size(42.dp)
) {
    when (type) {
        "3X-UI" -> {
            Box(
                modifier = modifier
                    .background(
                        brush = Brush.linearGradient(
                            listOf(Color(0xFFFF2A85), Color(0xFFC2185B))
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize(0.65f)) {
                    val w = size.width
                    val h = size.height
                    val path = Path().apply {
                        moveTo(w * 0.15f, h * 0.25f)
                        lineTo(w * 0.85f, h * 0.25f)
                        lineTo(w * 0.35f, h * 0.52f)
                        lineTo(w * 0.85f, h * 0.52f)
                        lineTo(w * 0.15f, h * 0.85f)
                    }
                    drawPath(
                        path = path,
                        color = Color.White,
                        style = Stroke(width = 2.8.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
        }
        "x-ui" -> {
            Box(
                modifier = modifier
                    .background(
                        brush = Brush.linearGradient(
                            listOf(Color(0xFF00A3FF), Color(0xFF0055D4))
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize(0.6f)) {
                    val w = size.width
                    val h = size.height
                    drawLine(
                        color = Color.White,
                        start = Offset(w * 0.1f, h * 0.1f),
                        end = Offset(w * 0.9f, h * 0.9f),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = Color.White,
                        start = Offset(w * 0.9f, h * 0.1f),
                        end = Offset(w * 0.1f, h * 0.9f),
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                }
            }
        }
        "hiddify" -> {
            Box(
                modifier = modifier
                    .background(
                        brush = Brush.linearGradient(
                            listOf(Color(0xFFA855F7), Color(0xFF6B21A8))
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize(0.6f)) {
                    val w = size.width
                    val h = size.height
                    val barWidth = w * 0.22f

                    // Bar 1
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.8f),
                        topLeft = Offset(w * 0.05f, h * 0.6f),
                        size = Size(barWidth, h * 0.4f),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                    // Bar 2
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(w * 0.38f, h * 0.35f),
                        size = Size(barWidth, h * 0.65f),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                    // Bar 3
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(w * 0.72f, h * 0.08f),
                        size = Size(barWidth, h * 0.92f),
                        cornerRadius = CornerRadius(2.dp.toPx())
                    )
                }
            }
        }
        "other" -> {
            Box(
                modifier = modifier
                    .background(
                        brush = Brush.linearGradient(
                            listOf(Color(0xFF1E293B), Color(0xFF0F172A))
                        ),
                        shape = RoundedCornerShape(10.dp)
                    )
                    .border(1.dp, Color(0xFF38BDF8), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize(0.6f)) {
                    val w = size.width
                    val h = size.height
                    val p = Path().apply {
                        moveTo(w * 0.5f, h * 0.1f)
                        lineTo(w * 0.9f, h * 0.32f)
                        lineTo(w * 0.9f, h * 0.75f)
                        lineTo(w * 0.5f, h * 0.95f)
                        lineTo(w * 0.1f, h * 0.75f)
                        lineTo(w * 0.1f, h * 0.32f)
                        close()
                    }
                    drawPath(
                        path = p,
                        color = Color(0xFF38BDF8),
                        style = Stroke(width = 1.5.dp.toPx())
                    )
                    drawLine(
                        color = Color(0xFF38BDF8),
                        start = Offset(w * 0.5f, h * 0.54f),
                        end = Offset(w * 0.5f, h * 0.95f),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawLine(
                        color = Color(0xFF38BDF8),
                        start = Offset(w * 0.5f, h * 0.54f),
                        end = Offset(w * 0.9f, h * 0.32f),
                        strokeWidth = 1.5.dp.toPx()
                    )
                    drawLine(
                        color = Color(0xFF38BDF8),
                        start = Offset(w * 0.5f, h * 0.54f),
                        end = Offset(w * 0.1f, h * 0.32f),
                        strokeWidth = 1.5.dp.toPx()
                    )
                }
            }
        }
        "bpb" -> {
            Canvas(modifier = modifier) {
                val w = size.width
                val h = size.height
                val dropPath = Path().apply {
                    moveTo(w * 0.5f, h * 0.1f)
                    cubicTo(w * 0.8f, h * 0.45f, w * 0.9f, h * 0.75f, w * 0.5f, h * 0.95f)
                    cubicTo(w * 0.1f, h * 0.75f, w * 0.2f, h * 0.45f, w * 0.5f, h * 0.1f)
                    close()
                }
                drawPath(
                    path = dropPath,
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF38BDF8), Color(0xFF0284C7))
                    )
                )
            }
        }
        "v2ray" -> {
            Canvas(modifier = modifier) {
                val w = size.width
                val h = size.height
                val vPath = Path().apply {
                    moveTo(w * 0.15f, h * 0.15f)
                    lineTo(w * 0.5f, h * 0.85f)
                    lineTo(w * 0.85f, h * 0.15f)
                    lineTo(w * 0.65f, h * 0.15f)
                    lineTo(w * 0.5f, h * 0.55f)
                    lineTo(w * 0.35f, h * 0.15f)
                    close()
                }
                drawPath(
                    path = vPath,
                    brush = Brush.verticalGradient(
                        listOf(Color(0xFF38BDF8), Color(0xFF2563EB))
                    )
                )
            }
        }
        "custom" -> {
            Box(modifier = modifier, contentAlignment = Alignment.Center) {
                Text(
                    text = "</>",
                    color = Color(0xFFA78BFA),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
fun CircularPercentGauge(
    percent: Int,
    modifier: Modifier = Modifier.size(46.dp)
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = 3.5.dp.toPx()
            val radius = (size.minDimension - stroke) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Background ring
            drawCircle(
                color = Color(0xFF0A2B1D),
                radius = radius,
                center = center,
                style = Stroke(width = stroke)
            )

            // Progress arc
            if (percent >= 100) {
                drawCircle(
                    color = Color(0xFF00E676),
                    radius = radius,
                    center = center,
                    style = Stroke(width = stroke)
                )
            } else {
                drawArc(
                    color = Color(0xFF00E676),
                    startAngle = -90f,
                    sweepAngle = (percent / 100f) * 360f,
                    useCenter = false,
                    topLeft = Offset(center.x - radius, center.y - radius),
                    size = Size(radius * 2f, radius * 2f),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        Text(
            text = "$percent%",
            color = Color(0xFF00E676),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
fun SignalBars(
    latencyMs: Long,
    modifier: Modifier = Modifier.size(16.dp, 12.dp)
) {
    val barColor = if (latencyMs < 50) Color(0xFF00E676) else if (latencyMs < 100) Color(0xFF38BDF8) else Color(0xFFFFB300)
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val barW = w * 0.18f
        val gap = w * 0.08f

        val heights = listOf(h * 0.35f, h * 0.55f, h * 0.78f, h * 1.0f)
        for (i in heights.indices) {
            drawRoundRect(
                color = barColor,
                topLeft = Offset(i * (barW + gap), h - heights[i]),
                size = Size(barW, heights[i]),
                cornerRadius = CornerRadius(1.dp.toPx())
            )
        }
    }
}

@Composable
fun CountryFlag(
    flag: String,
    modifier: Modifier = Modifier
) {
    Text(
        text = flag,
        fontSize = 20.sp,
        modifier = modifier
    )
}
