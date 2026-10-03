package com.example.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.R
import com.example.ui.theme.AppTheme

/**
 * Architecture & Landing Overview Dialog showcasing Maximus VPN's technical foundations.
 */
@Composable
fun LandingTourDialog(
    onDismiss: () -> Unit,
    onNavigateToServers: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .padding(vertical = 16.dp)
                .heightIn(max = 760.dp)
                .testTag("landing_tour_dialog"),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = BorderStroke(1.dp, AppTheme.colors.borderMedium)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // Header with Logo and Close
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF0A0C14))
                                .border(1.dp, AppTheme.colors.primary, RoundedCornerShape(10.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Image(
                                painter = painterResource(id = R.drawable.ic_maximus_logo),
                                contentDescription = "Logo",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(RoundedCornerShape(10.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Maximus VPN",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.textPrimary
                            )
                            Text(
                                text = "Architecture & Security Model",
                                fontSize = 11.sp,
                                color = AppTheme.colors.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(AppTheme.colors.surfaceElevated)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = AppTheme.colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Mission Statement Banner
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = AppTheme.colors.primary.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, AppTheme.colors.primary.copy(alpha = 0.3f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "BUILT FOR THE ADVERSARIAL INTERNET",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = AppTheme.colors.primary,
                            letterSpacing = 1.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Maximus VPN replaces legacy VPN protocols that are easily blocked by state-level Deep Packet Inspection (DPI) with modern camouflage protocols designed for unblockable access.",
                            fontSize = 12.sp,
                            color = AppTheme.colors.textPrimary,
                            lineHeight = 17.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Core Technology Pillars",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = AppTheme.colors.textPrimary
                )

                Spacer(modifier = Modifier.height(10.dp))

                // Pillar 1: VLESS Reality
                PillarRow(
                    icon = Icons.Default.Lock,
                    title = "VLESS Reality XTLS",
                    desc = "Eliminates TLS certificate fingerprints by borrowing public TLS certificates from legitimate destinations (e.g., Apple, Microsoft), rendering packets indistinguishable from ordinary web browsing.",
                    color = Color(0xFF00E5FF)
                )

                // Pillar 2: 4-Tier God Mode Cascade
                PillarRow(
                    icon = Icons.Default.Shield,
                    title = "Quad-Tier Failover Cascade",
                    desc = "When primary routes face interference, the tunnel cascades across VLESS -> Hysteria2 (QUIC/UDP) -> Psiphon Conduit -> Local P2P Mesh.",
                    color = Color(0xFFFF5252)
                )

                // Pillar 3: Anti-DPI Desync Engine
                PillarRow(
                    icon = Icons.Default.Speed,
                    title = "TCP Window & SNI Desync",
                    desc = "Splits HTTP/TLS client handshakes across fragment boundaries to confuse middleboxes and state firewalls without sacrificing connection speed.",
                    color = Color(0xFFB388FF)
                )

                // Pillar 4: Zero-Knowledge Privacy
                PillarRow(
                    icon = Icons.Default.Security,
                    title = "Zero Logs & DNS Shield",
                    desc = "Encrypted DNS-over-HTTPS (DoH) routing with strict DNS leak prevention. Ephemeral chat and browser histories reside exclusively in transient memory.",
                    color = Color(0xFF00E676)
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Comparison Box
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (AppTheme.colors.isDark) Color(0xFF141724) else Color(0xFFF1F5F9),
                    border = BorderStroke(1.dp, AppTheme.colors.borderSubtle),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "COMPARISON: TRADITIONAL VS MAXIMUS",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = AppTheme.colors.textMuted,
                            letterSpacing = 0.8.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))

                        ComparisonItem(
                            feature = "DPI Detection",
                            conventional = "Easily flagged by ISP",
                            maximus = "Undetectable TLS Camouflage"
                        )
                        ComparisonItem(
                            feature = "Firewall Resistance",
                            conventional = "Port/Protocol blocked",
                            maximus = "Multi-Engine Cascade Failover"
                        )
                        ComparisonItem(
                            feature = "DNS Privacy",
                            conventional = "Prone to leaks",
                            maximus = "Strict DoH & Direct Route"
                        )
                    }
                }

                Spacer(modifier = Modifier.height(18.dp))

                Button(
                    onClick = {
                        onDismiss()
                        onNavigateToServers()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .testTag("tour_view_nodes_button"),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(imageVector = Icons.Default.Public, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Select a Node & Connect", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun PillarRow(
    icon: ImageVector,
    title: String,
    desc: String,
    color: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.15f))
                .border(1.dp, color.copy(alpha = 0.4f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(17.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = AppTheme.colors.textPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = desc,
                fontSize = 12.sp,
                color = AppTheme.colors.textSecondary,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun ComparisonItem(
    feature: String,
    conventional: String,
    maximus: String
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = feature, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, color = AppTheme.colors.textPrimary)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "✗ $conventional",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = Color(0xFFEF5350),
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "✓ $maximus",
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = Color(0xFF4ADE80),
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1.2f)
            )
        }
    }
}
