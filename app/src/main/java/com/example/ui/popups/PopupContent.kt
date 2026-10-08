package com.example.ui.popups

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * The two launch pop-ups: "join the community" and "new update available". They always use the
 * dark gold-and-violet brand look of the mockups, whatever the app theme, and take the emblem
 * as a Painter so they hold no Android resources and can be previewed anywhere.
 */

object CommunityLinks {
    const val TELEGRAM = "https://t.me/DrFXAi"
    const val YOUTUBE = "https://www.youtube.com/@DrFXAi"
    const val GITHUB = "https://github.com/drfxai/MAXIMUS-VPN"
}

private object Lux {
    val night = Color(0xFF070816)
    val deep = Color(0xFF0D0B26)
    val indigo = Color(0xFF17124A)
    val gold = Color(0xFFE9C46A)
    val goldLight = Color(0xFFFFF1C7)
    val goldDark = Color(0xFFA8792C)
    val violet = Color(0xFF7C4DFF)
    val violetDeep = Color(0xFF4A23D6)
    val blue = Color(0xFF3D8BFF)
    val text = Color(0xFFF4F2FF)
    val textSoft = Color(0xFFB9B6D3)
    val glass = Color(0x1AFFFFFF)
}

private val goldBorder = Brush.linearGradient(listOf(Lux.goldLight, Lux.gold, Lux.violet, Lux.blue, Lux.gold))
private val goldText = Brush.verticalGradient(listOf(Lux.goldLight, Lux.gold, Lux.goldDark))
private val violetButton = Brush.horizontalGradient(listOf(Color(0xFF5B2BE8), Lux.violet, Color(0xFF3F51F5)))
private val cardBackground = Brush.verticalGradient(listOf(Lux.indigo, Lux.deep, Lux.night))

/** Paints [content] (usually text) with [brush] instead of its own color. */
private fun Modifier.brushed(brush: Brush) = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithCache { onDrawWithContent { drawContent(); drawRect(brush, blendMode = BlendMode.SrcAtop) } }

@Composable
private fun LuxCard(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Box(
        modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(cardBackground)
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(Lux.violet.copy(alpha = 0.38f), Color.Transparent), Offset(size.width / 2, 0f), size.width * 0.8f),
                    size.width * 0.8f, Offset(size.width / 2, 0f)
                )
            }
            .border(BorderStroke(1.5.dp, goldBorder), shape),
        content = content
    )
}

@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .border(1.dp, Color(0x55FFFFFF), CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(Icons.Filled.Close, contentDescription = "Close", tint = Lux.text, modifier = Modifier.size(18.dp)) }
}

/** The helmet emblem with its glow and the two blue orbits around it. */
@Composable
private fun Emblem(emblem: Painter, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size * 1.5f, size * 1.12f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val c = Offset(this.size.width / 2, this.size.height / 2)
            drawCircle(Brush.radialGradient(listOf(Lux.blue.copy(alpha = 0.45f), Lux.violet.copy(alpha = 0.18f), Color.Transparent), c, this.size.width / 2), this.size.width / 2, c)
            val orbit = Size(this.size.width * 0.98f, this.size.height * 0.36f)
            val tl = Offset(c.x - orbit.width / 2, c.y - orbit.height / 2)
            rotate(-14f, c) {
                drawOval(Brush.horizontalGradient(listOf(Color.Transparent, Lux.blue, Color(0xFFB7D3FF), Lux.violet, Color.Transparent)), tl, orbit, style = Stroke(2.2.dp.toPx()))
            }
            rotate(10f, c) {
                drawOval(Brush.horizontalGradient(listOf(Lux.violet, Color.Transparent, Lux.blue)), tl + Offset(0f, 4f), orbit, alpha = 0.7f, style = Stroke(1.2.dp.toPx()))
            }
        }
        Image(
            emblem, contentDescription = "Maximus VPN",
            modifier = Modifier
                .size(size)
                .shadow(18.dp, CircleShape, ambientColor = Lux.gold, spotColor = Lux.gold)
                .clip(CircleShape)
                .border(1.5.dp, Brush.verticalGradient(listOf(Lux.goldLight, Lux.goldDark)), CircleShape)
        )
    }
}

@Composable
private fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, leading: @Composable (() -> Unit)? = null, trailing: @Composable (() -> Unit)? = null) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .height(54.dp)
            .shadow(14.dp, shape, ambientColor = Lux.violet, spotColor = Lux.violet)
            .clip(shape)
            .background(violetButton)
            .border(1.dp, Brush.verticalGradient(listOf(Color(0xCCD9CCFF), Color(0x33FFFFFF))), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        leading?.invoke()
        Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        trailing?.invoke()
    }
}

@Composable
private fun GoldWordmark(fontSize: TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
        Box(Modifier.width(34.dp).height(1.5.dp).background(Brush.horizontalGradient(listOf(Color.Transparent, Lux.gold))))
        Text(
            "MAXIMUS VPN",
            modifier = Modifier.padding(horizontal = 10.dp).brushed(goldText),
            fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold,
            fontSize = fontSize, letterSpacing = 2.sp, color = Lux.gold
        )
        Box(Modifier.width(34.dp).height(1.5.dp).background(Brush.horizontalGradient(listOf(Lux.gold, Color.Transparent))))
    }
}

@Composable
private fun CommunityTile(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, logo: @Composable () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .height(104.dp)
            .clip(shape)
            .background(Brush.verticalGradient(listOf(Color(0x2A8C7BFF), Lux.glass)))
            .border(1.dp, Brush.linearGradient(listOf(Lux.gold.copy(alpha = 0.9f), Lux.violet, Lux.blue.copy(alpha = 0.9f))), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        logo()
        Spacer(Modifier.height(10.dp))
        Text(label, color = Lux.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * "Join the community": links to the official Telegram, YouTube and GitHub. Every tap only opens a
 * public page; [onDontShowAgain] stops the pop-up from appearing on later launches.
 */
@Composable
fun CommunityPopupContent(
    emblem: Painter,
    onOpenLink: (String) -> Unit,
    onClose: () -> Unit,
    onDontShowAgain: () -> Unit,
    modifier: Modifier = Modifier
) {
    LuxCard(modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Emblem(emblem, 150.dp)
            Spacer(Modifier.height(6.dp))
            GoldWordmark(20.sp)
            Spacer(Modifier.height(10.dp))
            Text("Premium Speed.", color = Lux.text, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, lineHeight = 34.sp)
            Text(
                "Elite Security.",
                modifier = Modifier.brushed(Brush.horizontalGradient(listOf(Color(0xFFC9B6FF), Lux.violet, Lux.blue))),
                color = Lux.violet, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, lineHeight = 34.sp
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Stay connected with the official Maximus VPN channels for secure updates, news, and releases.",
                color = Lux.textSoft, fontSize = 14.sp, lineHeight = 20.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CommunityTile("YouTube", { onOpenLink(CommunityLinks.YOUTUBE) }, Modifier.weight(1f)) { YouTubeLogo(40.dp) }
                CommunityTile("GitHub", { onOpenLink(CommunityLinks.GITHUB) }, Modifier.weight(1f)) { GitHubLogo(40.dp) }
                CommunityTile("Telegram", { onOpenLink(CommunityLinks.TELEGRAM) }, Modifier.weight(1f)) { TelegramLogo(40.dp) }
            }
            Spacer(Modifier.height(16.dp))
            PrimaryButton(
                "Join the Community", { onOpenLink(CommunityLinks.TELEGRAM) }, Modifier.fillMaxWidth(),
                trailing = {
                    Spacer(Modifier.width(10.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Don't show again",
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onDontShowAgain).padding(horizontal = 12.dp, vertical = 8.dp),
                color = Lux.textSoft, fontSize = 13.sp, fontWeight = FontWeight.Medium
            )
        }
        CloseButton(onClose, Modifier.align(Alignment.TopEnd).padding(12.dp))
    }
}

/**
 * "New update available". [version] / [installedVersion] are version names (for example V1.0.1) and
 * [build] / [installedBuild] release build numbers, shown because a rebuilt release keeps its name.
 */
@Composable
fun UpdatePopupContent(
    emblem: Painter,
    version: String,
    build: Int,
    installedVersion: String,
    installedBuild: Int,
    onLater: () -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier.widthIn(max = 420.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        LuxCard(Modifier.padding(top = 62.dp)) {
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 76.dp, bottom = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "New Update Available",
                    modifier = Modifier.brushed(goldText),
                    fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold,
                    fontSize = 24.sp, color = Lux.gold, textAlign = TextAlign.Center, maxLines = 1, softWrap = false
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "A new Maximus VPN version is now ready on GitHub.",
                    color = Lux.textSoft, fontSize = 15.sp, lineHeight = 21.sp, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier
                        .clip(CircleShape)
                        .background(Color(0x33000000))
                        .border(1.2.dp, Brush.horizontalGradient(listOf(Lux.goldDark, Lux.goldLight, Lux.goldDark)), CircleShape)
                        .padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null, tint = Lux.gold, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Version $version", color = Lux.goldLight, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Build $build · you have V${installedVersion.trimStart('v', 'V')}" + if (installedBuild > 0) " build $installedBuild" else "",
                    color = Lux.textSoft.copy(alpha = 0.75f), fontSize = 12.sp
                )
                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val laterShape = RoundedCornerShape(18.dp)
                    Box(
                        Modifier
                            .weight(1f)
                            .height(54.dp)
                            .clip(laterShape)
                            .background(Lux.glass)
                            .border(1.dp, Color(0x55B9B6D3), laterShape)
                            .clickable(role = Role.Button, onClick = onLater),
                        contentAlignment = Alignment.Center
                    ) { Text("Later", color = Lux.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
                    PrimaryButton(
                        "Download", onDownload, Modifier.weight(1.35f),
                        leading = {
                            Icon(Icons.Filled.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                        }
                    )
                }
            }
            CloseButton(onLater, Modifier.align(Alignment.TopEnd).padding(12.dp))
        }
        Emblem(emblem, 128.dp)
    }
}
