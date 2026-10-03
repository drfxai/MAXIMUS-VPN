package com.example.ui.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ai.AiAgentConfig
import com.example.ui.theme.AppTheme
import java.text.NumberFormat
import java.util.Locale

@Composable
fun AiTokenTrackerModal(
    config: AiAgentConfig,
    onResetTokens: () -> Unit,
    onDismiss: () -> Unit
) {
    val formatter = NumberFormat.getNumberInstance(Locale.US)
    val totalTokens = config.lifetimeTokens
    val dailyLimit = 1_000_000L
    val usedRatio = (totalTokens.toDouble() / dailyLimit).coerceIn(0.0, 1.0)
    val remainingTokens = (dailyLimit - totalTokens).coerceAtLeast(0L)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f)
                .padding(vertical = 12.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = AppTheme.colors.surfaceCard),
            border = androidx.compose.foundation.BorderStroke(1.dp, AppTheme.colors.borderSubtle)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(AppTheme.colors.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.DataUsage,
                                contentDescription = "Token Tracker",
                                tint = AppTheme.colors.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Token Analytics",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = AppTheme.colors.textPrimary
                            )
                            Text(
                                text = "Real-time consumption monitor",
                                fontSize = 11.sp,
                                color = AppTheme.colors.textSecondary
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = AppTheme.colors.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Gauge / Progress
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(AppTheme.colors.surfaceElevated.copy(alpha = 0.5f))
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Consumed Session Quota",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = AppTheme.colors.textSecondary
                            )
                            Text(
                                text = "${(usedRatio * 100).toInt()}% of 1M quota",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.primary
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        LinearProgressIndicator(
                            progress = { usedRatio.toFloat() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            color = AppTheme.colors.primary,
                            trackColor = AppTheme.colors.borderSubtle
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Used: ${formatter.format(totalTokens)}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = AppTheme.colors.textPrimary
                            )
                            Text(
                                text = "Remaining: ~${formatter.format(remainingTokens)}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }

                    // Stat Cards Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Prompt Tokens
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp)),
                            color = AppTheme.colors.surfaceElevated.copy(alpha = 0.5f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    "PROMPT TOKENS",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.textSecondary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = formatter.format(config.totalPromptTokens),
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.textPrimary
                                )
                                Text("Inputs & Logs", fontSize = 9.sp, color = AppTheme.colors.textMuted)
                            }
                        }

                        // Candidate Tokens
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp)),
                            color = AppTheme.colors.surfaceElevated.copy(alpha = 0.5f)
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    "REPLY TOKENS",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.textSecondary
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = formatter.format(config.totalCandidateTokens),
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppTheme.colors.textPrimary
                                )
                                Text("Agent Replies", fontSize = 9.sp, color = AppTheme.colors.textMuted)
                            }
                        }
                    }

                    // Last request tokens
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp)),
                        color = AppTheme.colors.surfaceElevated.copy(alpha = 0.3f)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Bolt,
                                    contentDescription = "Last turn",
                                    tint = AppTheme.colors.primary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Last Turn Consumed",
                                    fontSize = 11.5.sp,
                                    color = AppTheme.colors.textSecondary
                                )
                            }
                            Text(
                                text = "${formatter.format(config.lastSessionTokens)} tokens",
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppTheme.colors.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = onResetTokens,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Reset", modifier = Modifier.size(13.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Reset Counter", fontSize = 11.5.sp)
                    }

                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppTheme.colors.primary),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        modifier = Modifier.height(38.dp)
                    ) {
                        Text("Done", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
