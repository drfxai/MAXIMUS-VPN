
package com.example.ui.panels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Reference-driven MAXIMUS premium palette: AMOLED black, navy, electric blue, gold and cyan. */
internal object Mx {
    val Black = Color(0xFF050914)
    val Navy = Color(0xFF0B182C)
    val Card = Color(0xFF101F36)
    val Line = Color(0xFF315475)
    val Gold = Color(0xFFF4C263)
    val Blue = Color(0xFF1379FF)
    val Cyan = Color(0xFF3CCEFF)
    val Green = Color(0xFF42DEAD)
    val Orange = Color(0xFFFFAD55)
    val Red = Color(0xFFFF697C)
    val White = Color(0xFFF4F7FF)
    val Muted = Color(0xFF99AAC2)
}
@Composable
internal fun MxHero(kicker: String, title: String, subtitle: String) {
    Surface(
        shape = RoundedCornerShape(19.dp),
        color = Mx.Navy,
        border = BorderStroke(1.2.dp, Mx.Gold),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = 115.dp).background(
            Brush.linearGradient(listOf(Color(0xFF362815), Color(0xFF0A182F), Color(0xFF092E55)))
        )) {
            // Gold orbital arcs echo the approved reference without embedding a flat screenshot.
            Canvas(Modifier.size(145.dp).align(Alignment.TopEnd)) {
                val center = Offset(size.width * .90f, size.height * .83f)
                listOf(.80f, .59f, .39f).forEach { radius ->
                    drawCircle(Mx.Gold.copy(alpha = .32f), size.minDimension * radius,
                        center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.2.dp.toPx()))
                }
                for (i in 0..8) {
                    val x = size.width * (.25f + i * .10f)
                    drawLine(Mx.Cyan.copy(alpha = .11f), Offset(x, 0f), Offset(x, size.height),
                        strokeWidth = .6.dp.toPx())
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.size(49.dp).clip(RoundedCornerShape(15.dp)).background(
                    Brush.verticalGradient(listOf(Color(0xFF73521F), Color(0xFF1C2B3A)))
                ), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = Mx.Gold,
                        modifier = Modifier.size(31.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(kicker, color = Mx.Gold, fontWeight = FontWeight.Bold, fontSize = 10.sp,
                        letterSpacing = .8.sp, maxLines = 1)
                    Text(title, color = Mx.White, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold,
                        maxLines = 2)
                    Text(subtitle, color = Mx.Muted, fontSize = 11.sp, lineHeight = 15.sp, maxLines = 3)
                }
            }
        }
    }
}
@Composable
internal fun MxCard(
    title: String, subtitle: String = "", accent: Color = Mx.Cyan,
    modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = Mx.Navy, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = .65f))
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text(title, color = if (accent == Mx.Gold) Mx.Gold else Mx.White,
                fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            if (subtitle.isNotEmpty()) Text(subtitle, color = Mx.Muted, fontSize = 11.sp,
                lineHeight = 15.sp)
            content()
        }
    }
}
@Composable
internal fun MxStatus(text: String, tint: Color = Mx.Cyan) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = tint.copy(alpha = .12f),
        border = BorderStroke(1.dp, tint.copy(alpha = .55f))
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            color = tint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}
@Composable
internal fun MxAction(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    strong: Boolean = false, enabled: Boolean = true
) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 42.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (strong) Mx.Gold else Mx.Card,
            contentColor = if (strong) Mx.Black else Mx.Cyan,
            disabledContainerColor = Color(0xFF202B3C),
            disabledContentColor = Mx.Muted
        ),
        border = if (strong) null else BorderStroke(1.dp, Mx.Line),
        shape = RoundedCornerShape(11.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 2,
            lineHeight = 14.sp)
    }
}
@Composable
internal fun MxProgress(label: String, value: Float?, tint: Color = Mx.Cyan) {
    val fraction = value?.coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Mx.Muted, fontSize = 10.sp)
        Text(if (fraction == null) "—" else "${(fraction * 100).toInt()}%",
            color = Mx.White, fontSize = 10.sp)
    }
    Box(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(9.dp))
        .background(Mx.Line.copy(alpha = .65f))) {
        if (fraction != null) Box(Modifier.fillMaxWidth(fraction).height(5.dp)
            .clip(RoundedCornerShape(9.dp)).background(tint))
    }
}
@Composable
internal fun MxMetric(label: String, value: String, fraction: Float?, tint: Color,
                      modifier: Modifier = Modifier) {
    Surface(modifier = modifier, color = Mx.Card, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Mx.Line)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(label, color = Mx.Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(value, color = tint, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(4.dp))
                .background(Mx.Line.copy(alpha = .6f))) {
                if (fraction != null) Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(4.dp).background(tint))
            }
        }
    }
}
@Composable
internal fun MxInfoRow(label: String, value: String, tint: Color = Mx.Muted) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Mx.White, fontSize = 11.sp, modifier = Modifier.weight(1f),
            maxLines = 2)
        Text(value, color = tint, fontSize = 10.sp, modifier = Modifier.weight(.8f),
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(color = Mx.Line.copy(alpha = .55f), thickness = .5.dp)
}
@Composable
internal fun MxFlowSteps(names: List<String>) {
    // Six-stage graphical flow, laid out as a responsive 3x2 grid.
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        names.chunked(3).forEachIndexed { group, items ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items.forEachIndexed { idx, name ->
                    val number = group * 3 + idx + 1
                    Surface(Modifier.weight(1f), color = Mx.Card, shape = RoundedCornerShape(11.dp),
                        border = BorderStroke(1.dp, Mx.Cyan.copy(alpha = .45f))) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("%02d".format(number), color = Mx.Gold,
                                fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(name, color = Mx.White, fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                lineHeight = 12.sp, maxLines = 2)
                        }
                    }
                }
            }
        }
    }
}
