package com.example.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.R
import com.example.ui.theme.AppTheme

/**
 * Promotional showcase model representing an app feature highlight.
 */
data class PromoFeatureItem(
    val id: String,
    val title: String,
    val tag: String,
    val tagColor: Color,
    val headline: String,
    val description: String,
    val primaryIcon: ImageVector,
    val gradientColors: List<Color>,
    val actionLabel: String,
    val metricsList: List<Pair<String, String>>
)

/**
 * Main Promotional & Landing Hero Section featured on the homepage.
 */
@Composable
fun LandingHeroBanner(
    onExploreArchitecture: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(if (AppTheme.colors.isDark) 4.dp else 6.dp, RoundedCornerShape(26.dp))
            .testTag("landing_hero_banner"),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (AppTheme.colors.isDark) Color(0xFF131522) else Color(0xFFF8FAFC)
        ),
        border = BorderStroke(
            1.5.dp,
            Brush.linearGradient(
                listOf(
                    AppTheme.colors.primary.copy(alpha = glowAlpha),
                    Color(0xFF00E5FF).copy(alpha = 0.6f),
                    AppTheme.colors.borderSubtle
                )
            )
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.radialGradient(
                        colors = listOf(
                            AppTheme.colors.primary.copy(alpha = if (AppTheme.colors.isDark) 0.18f else 0.08f),
                            Color.Transparent
                        ),
                        center = Offset(200f, 100f),
                        radius = 800f
                    )
                )
                .padding(20.dp)
        ) {
            Column {
                // Top Badge & Platform Indicator
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = AppTheme.colors.primary.copy(alpha = 0.16f),
                        border = BorderStroke(1.dp, AppTheme.colors.primary.copy(alpha = 0.35f))
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4ADE80))
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "NEXT-GEN VLESS & XRAY PLATFORM",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = AppTheme.colors.primary,
                                letterSpacing = 0.35.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFF1E293B).copy(alpha = 0.7f),
                        border = BorderStroke(1.dp, AppTheme.colors.borderSubtle)
                    ) {
                        Text(
                            text = "V${BuildConfig.VERSION_NAME}\nPRO",
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF94A3B8),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            maxLines = 2,
                            lineHeight = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Hero Logo + Title Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color(0xFF0A0C14))
                            .border(
                                2.dp,
                                Brush.linearGradient(
                                    listOf(AppTheme.colors.primary, Color(0xFF00E5FF))
                                ),
                                RoundedCornerShape(18.dp)
                            )
                            .shadow(8.dp, RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_maximus_logo),
                            contentDescription = "Maximus Spartan VPN Emblem",
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(RoundedCornerShape(18.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Maximus VPN",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Black,
                            color = AppTheme.colors.textPrimary,
                            letterSpacing = 0.3.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Sovereign Privacy • Anti-DPI • Multi-Engine",
                            style = MaterialTheme.typography.bodySmall,
                            color = AppTheme.colors.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Engineered with Xray-core, VLESS Reality & P2P Mesh",
                            style = MaterialTheme.typography.labelSmall,
                            color = AppTheme.colors.textSecondary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Value Proposition Chips
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    LandingStatPill(
                        label = "Zero Logs",
                        sub = "RAM-Only",
                        icon = Icons.Default.VisibilityOff,
                        modifier = Modifier.weight(1f)
                    )
                    LandingStatPill(
                        label = "REALITY TLS",
                        sub = "Camouflage",
                        icon = Icons.Default.Lock,
                        modifier = Modifier.weight(1f)
                    )
                    LandingStatPill(
                        label = "0 DNS Leaks",
                        sub = "DoH Encrypted",
                        icon = Icons.Default.Shield,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Action Row: Learn More / Architecture Tour
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Designed by DrFXAi for high censorship resilience",
                        fontSize = 11.sp,
                        color = AppTheme.colors.textMuted,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = onExploreArchitecture,
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, AppTheme.colors.primary.copy(alpha = 0.6f)),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = AppTheme.colors.primary
                        ),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp).testTag("explore_features_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Architecture", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun LandingStatPill(
    label: String,
    sub: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (AppTheme.colors.isDark) Color(0xFF181A28) else Color(0xFFEDF2F7),
        border = BorderStroke(1.dp, AppTheme.colors.borderSubtle)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = AppTheme.colors.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Column {
                Text(
                    text = label,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = sub,
                    fontSize = 9.sp,
                    lineHeight = 12.sp,
                    color = AppTheme.colors.textMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Interactive Promotional Carousel displaying the app's flagship interfaces and features.
 */
@Composable
fun PromotionalFeatureShowcase(
    onNavigateToServers: () -> Unit,
    onNavigateToSecretChat: () -> Unit,
    onNavigateToGodBrowser: () -> Unit,
    onNavigateToAiAgent: () -> Unit,
    modifier: Modifier = Modifier
) {
    val items = remember {
        listOf(
            PromoFeatureItem(
                id = "vless_reality",
                title = "VLESS REALITY & ANTI-DPI",
                tag = "ULTRA SPEED",
                tagColor = Color(0xFF00E5FF),
                headline = "Undetectable Camouflage",
                description = "Disguises VPN traffic as normal TLS 1.3 web requests using SNI spoofing and XTLS direct streaming.",
                primaryIcon = Icons.Default.Lock,
                gradientColors = listOf(Color(0xFF0D2538), Color(0xFF131C30)),
                actionLabel = "Explore Nodes",
                metricsList = listOf("Latency" to "14ms", "Speed" to "1.2 Gbps", "Bypass" to "100%")
            ),
            PromoFeatureItem(
                id = "god_mode",
                title = "GOD MODE & P2P MESH",
                tag = "MAX SURVIVABILITY",
                tagColor = Color(0xFFFF5252),
                headline = "Multi-Tier Failover Cascade",
                description = "Automatically escalates from VLESS to Hysteria2, Psiphon Conduit bridges, and local peer-to-peer mesh when blocked.",
                primaryIcon = Icons.Default.Shield,
                gradientColors = listOf(Color(0xFF381216), Color(0xFF22111E)),
                actionLabel = "Open Hardened Suite",
                metricsList = listOf("Cascade" to "4 Tiers", "Mesh" to "P2P Active", "DPI Guard" to "Armed")
            ),
            PromoFeatureItem(
                id = "ai_agent",
                title = "AI CO-PILOT AGENT",
                tag = "SMART ROUTING",
                tagColor = Color(0xFFB388FF),
                headline = "Autonomous Diagnostics",
                description = "Integrated with Gemini intelligence to analyze connection health, ping anomalies, and recommend gaming/streaming nodes.",
                primaryIcon = Icons.Default.AutoAwesome,
                gradientColors = listOf(Color(0xFF221538), Color(0xFF141930)),
                actionLabel = "Launch AI Agent",
                metricsList = listOf("Model" to "Gemini Flash", "Modes" to "Voice & Chat", "Privacy" to "Filtered")
            ),
            PromoFeatureItem(
                id = "hardened_tools",
                title = "ZERO-KNOWLEDGE VAULT",
                tag = "END-TO-END",
                tagColor = Color(0xFF00E676),
                headline = "Ephemeral Chat & Browser",
                description = "Zero-log encrypted peer chat with QR key exchange and custom built-in privacy browser with script/tracker neutralization.",
                primaryIcon = Icons.Default.Security,
                gradientColors = listOf(Color(0xFF0D2E24), Color(0xFF13202E)),
                actionLabel = "Secret Chat",
                metricsList = listOf("Cipher" to "AES-256-GCM", "Trackers" to "Blocked", "History" to "RAM Only")
            )
        )
    }

    var selectedIndex by remember { mutableIntStateOf(0) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "FEATURE SPOTLIGHT",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = AppTheme.colors.primary,
                    letterSpacing = 1.sp
                )
                Text(
                    text = "Next-Gen Architecture & Capabilities",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary
                )
            }

            // Dot indicators
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                items.indices.forEach { index ->
                    Box(
                        modifier = Modifier
                            .size(if (index == selectedIndex) 16.dp else 6.dp, 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                if (index == selectedIndex) AppTheme.colors.primary
                                else AppTheme.colors.textMuted.copy(alpha = 0.4f)
                            )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal Carousel of rich promotional cards
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            contentPadding = PaddingValues(horizontal = 2.dp)
        ) {
            itemsIndexed(items) { index, item ->
                PromotionalCard(
                    item = item,
                    isSelected = selectedIndex == index,
                    onSelect = { selectedIndex = index },
                    onAction = {
                        when (item.id) {
                            "vless_reality" -> onNavigateToServers()
                            "god_mode" -> onNavigateToGodBrowser()
                            "ai_agent" -> onNavigateToAiAgent()
                            "hardened_tools" -> onNavigateToSecretChat()
                        }
                    }
                )
            }
        }
    }
}

/**
 * Single Promotional Card with visual UI mockup representation and metrics.
 */
@Composable
private fun PromotionalCard(
    item: PromoFeatureItem,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier
            .width(300.dp)
            .clip(RoundedCornerShape(22.dp))
            .clickable(onClick = onSelect)
            .shadow(if (AppTheme.colors.isDark) 3.dp else 5.dp, RoundedCornerShape(22.dp))
            .testTag("promo_card_${item.id}"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
        border = BorderStroke(
            if (isSelected) 1.5.dp else 1.dp,
            if (isSelected) item.tagColor.copy(alpha = 0.8f) else AppTheme.colors.borderSubtle
        )
    ) {
        Column {
            // Visual Header / UI Preview Mockup Canvas
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(125.dp)
                    .background(
                        Brush.linearGradient(
                            listOf(item.gradientColors[0], item.gradientColors[1])
                        )
                    )
                    .padding(12.dp)
            ) {
                // Background artistic grid / pattern
                CanvasUiGridMockup(accentColor = item.tagColor)

                // Top Tag badge
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = item.tagColor.copy(alpha = 0.2f),
                    border = BorderStroke(1.dp, item.tagColor.copy(alpha = 0.5f)),
                    modifier = Modifier.align(Alignment.TopStart)
                ) {
                    Text(
                        text = item.tag,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = item.tagColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }

                // Center Icon + UI mockup elements
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(item.tagColor.copy(alpha = 0.15f))
                        .border(1.dp, item.tagColor.copy(alpha = 0.4f), CircleShape)
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = item.primaryIcon,
                        contentDescription = null,
                        tint = item.tagColor,
                        modifier = Modifier.size(22.dp)
                    )
                }

                // Simulated UI telemetry pill at bottom
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color.Black.copy(alpha = 0.65f),
                    modifier = Modifier.align(Alignment.BottomEnd)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(item.tagColor)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "ACTIVE",
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }

            // Card Body
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = item.title,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.primary,
                    letterSpacing = 0.5.sp
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.headline,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = item.description,
                    fontSize = 12.sp,
                    color = AppTheme.colors.textSecondary,
                    lineHeight = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Metrics row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    item.metricsList.forEach { (metricLabel, metricVal) ->
                        Column {
                            Text(
                                text = metricLabel.uppercase(),
                                fontSize = 9.sp,
                                color = AppTheme.colors.textMuted,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = metricVal,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.textPrimary,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // CTA Button
                Button(
                    onClick = onAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(36.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = item.tagColor.copy(alpha = 0.9f)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = item.actionLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Black
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

/**
 * Custom canvas drawing depicting a futuristic holographic network grid inside the card banner.
 */
@Composable
private fun CanvasUiGridMockup(
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                val step = 20.dp.toPx()
                val linePaint = accentColor.copy(alpha = 0.08f)

                // Vertical lines
                var x = 0f
                while (x < size.width) {
                    drawLine(
                        color = linePaint,
                        start = Offset(x, 0f),
                        end = Offset(x, size.height),
                        strokeWidth = 1f
                    )
                    x += step
                }

                // Horizontal lines
                var y = 0f
                while (y < size.height) {
                    drawLine(
                        color = linePaint,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = 1f
                    )
                    y += step
                }

                // Glowing node points
                drawCircle(
                    color = accentColor.copy(alpha = 0.25f),
                    radius = 3.dp.toPx(),
                    center = Offset(size.width * 0.2f, size.height * 0.35f)
                )
                drawCircle(
                    color = accentColor.copy(alpha = 0.35f),
                    radius = 4.dp.toPx(),
                    center = Offset(size.width * 0.8f, size.height * 0.65f)
                )
            }
    )
}

