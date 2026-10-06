package com.example.ui.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RocketLaunch
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.panels.ManagedPanel

/** Cloudflare tab palette: the Panels screen's navy with Cloudflare orange as the accent. */
private object Cf {
    val Orange = Color(0xFFF38020)
    val Amber = Color(0xFFFBAD41)
    val Card = Color(0xFF0C1526)
    val CardBorder = Color(0xFF1B2A44)
    val Inset = Color(0xFF070E1B)
    val Text = Color(0xFFF1F5F9)
    val Muted = Color(0xFF94A3B8)
    val Dim = Color(0xFF64748B)
    val Green = Color(0xFF34D399)
    val Red = Color(0xFFF87171)
    val Blue = Color(0xFF38BDF8)
}

/**
 * The Cloudflare tab of the Panels screen: a status header, a three-step BPB setup, live install
 * progress, the result, and the BPB panels already deployed.
 */
@Composable
internal fun CloudflareTab(
    token: String,
    onTokenChange: (String) -> Unit,
    account: String,
    onAccountChange: (String) -> Unit,
    panels: List<ManagedPanel>,
    isBusy: Boolean,
    statusText: String,
    logs: List<String>,
    newlyDeployedPanel: ManagedPanel?,
    onDismissNewPanel: () -> Unit,
    onSignUp: () -> Unit,
    onCreateToken: () -> Unit,
    onOpenDocs: () -> Unit,
    onPasteToken: () -> String?,
    onInstall: (token: String, account: String, username: String, password: String) -> Unit,
    onImportToVpn: (ManagedPanel) -> Unit,
    onFixBpb: (ManagedPanel) -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopy: (label: String, value: String) -> Unit,
    initialLoginExpanded: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        CloudflareHero(
            panelCount = panels.size,
            tokenReady = token.isNotBlank(),
            busy = isBusy
        )

        SetupCard(
            token = token,
            onTokenChange = onTokenChange,
            account = account,
            onAccountChange = onAccountChange,
            isBusy = isBusy,
            onSignUp = onSignUp,
            onCreateToken = onCreateToken,
            onOpenDocs = onOpenDocs,
            onPasteToken = onPasteToken,
            onInstall = onInstall,
            initialLoginExpanded = initialLoginExpanded
        )

        if (isBusy || logs.isNotEmpty() || newlyDeployedPanel != null) {
            DeployProgressCard(
                isBusy = isBusy,
                statusText = statusText,
                logs = logs,
                done = newlyDeployedPanel != null
            )
        }

        if (newlyDeployedPanel != null && !isBusy) {
            DeployedResultCard(
                panel = newlyDeployedPanel,
                onDismiss = onDismissNewPanel,
                onOpenUrl = onOpenUrl,
                onCopy = onCopy,
                onImportToVpn = onImportToVpn,
                onFixBpb = onFixBpb
            )
        }

        DeployedPanelsCard(
            panels = panels,
            busy = isBusy,
            onImportToVpn = onImportToVpn,
            onFixBpb = onFixBpb,
            onOpenUrl = onOpenUrl
        )
    }
}

@Composable
private fun CfCard(modifier: Modifier = Modifier, borderColor: Color = Cf.CardBorder, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Cf.Card)
            .border(1.dp, borderColor, RoundedCornerShape(18.dp)),
        content = content
    )
}

@Composable
private fun CloudflareHero(panelCount: Int, tokenReady: Boolean, busy: Boolean) {
    CfCard {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Cf.Orange.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Cloud, contentDescription = null, tint = Cf.Orange, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Cloudflare Workers", color = Cf.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Your own BPB panel, free", color = Cf.Muted, fontSize = 12.5.sp)
            }
            StatusChip(
                text = when {
                    busy -> "Installing"
                    panelCount > 0 -> "Live"
                    tokenReady -> "Token ready"
                    else -> "Not set up"
                },
                color = when {
                    busy -> Cf.Blue
                    panelCount > 0 || tokenReady -> Cf.Green
                    else -> Cf.Muted
                }
            )
        }
        HorizontalDivider(color = Cf.CardBorder)
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Metric("Panels", panelCount.toString(), if (panelCount > 0) Cf.Text else Cf.Muted, Modifier.weight(1f))
            VDivider()
            Metric("API token", if (tokenReady) "Added" else "Missing", if (tokenReady) Cf.Green else Cf.Amber, Modifier.weight(1f))
            VDivider()
            Metric("Plan", "Free", Cf.Text, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatusChip(text: String, color: Color) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(text, color = color, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Metric(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text(label, color = Cf.Muted, fontSize = 11.5.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = valueColor, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SetupCard(
    token: String,
    onTokenChange: (String) -> Unit,
    account: String,
    onAccountChange: (String) -> Unit,
    isBusy: Boolean,
    onSignUp: () -> Unit,
    onCreateToken: () -> Unit,
    onOpenDocs: () -> Unit,
    onPasteToken: () -> String?,
    onInstall: (token: String, account: String, username: String, password: String) -> Unit,
    initialLoginExpanded: Boolean
) {
    var tokenVisible by remember { mutableStateOf(false) }
    var passVisible by remember { mutableStateOf(false) }
    var loginExpanded by remember { mutableStateOf(initialLoginExpanded) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val tokenReady = token.isNotBlank()
    val currentStep = if (tokenReady) 3 else 1

    CfCard {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Deploy a BPB panel", color = Cf.Text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (tokenReady) "Step 3 of 3 · ready to install" else "Three steps · about two minutes",
                    color = Cf.Muted,
                    fontSize = 12.5.sp
                )
            }
            TextButton(onClick = onOpenDocs) {
                Text("Guide", color = Cf.Muted, fontSize = 13.sp)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Cf.Muted, modifier = Modifier.size(14.dp))
            }
        }

        // Progress across the three steps.
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Account", "Token", "Install").forEachIndexed { i, label ->
                val reached = i + 1 <= currentStep || (tokenReady && i < 2)
                Column(modifier = Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(CircleShape)
                            .background(if (reached) Cf.Orange else Cf.CardBorder)
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(label, color = if (reached) Cf.Text else Cf.Dim, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        HorizontalDivider(color = Cf.CardBorder, modifier = Modifier.padding(top = 4.dp))

        StepRow(
            number = 1,
            done = tokenReady,
            title = "Cloudflare account",
            subtitle = "Free account with a verified email",
            action = "Sign up",
            onAction = onSignUp
        )
        HorizontalDivider(color = Cf.CardBorder, modifier = Modifier.padding(start = 60.dp))
        StepRow(
            number = 2,
            done = tokenReady,
            title = "API token",
            subtitle = "Workers Scripts and Workers KV are pre-selected",
            action = "Create",
            primary = !tokenReady,
            onAction = onCreateToken
        ) {
            Text(
                "On Cloudflare tap Continue to summary, then Create Token, and copy it.",
                color = Cf.Dim,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        HorizontalDivider(color = Cf.CardBorder, modifier = Modifier.padding(start = 60.dp))

        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                StepBadge(number = 3, done = false, active = tokenReady)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Paste token and install", color = Cf.Text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
                    Text("Your login details appear here when done", color = Cf.Muted, fontSize = 12.5.sp)
                }
            }
            CfField(
                value = token,
                onValueChange = onTokenChange,
                placeholder = "Cloudflare API token",
                icon = Icons.Default.Key,
                secret = !tokenVisible,
                trailing = {
                    IconButton(onClick = { tokenVisible = !tokenVisible }) {
                        Icon(
                            if (tokenVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Show token",
                            tint = Cf.Muted,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    IconButton(onClick = { onPasteToken()?.let(onTokenChange) }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = Cf.Muted, modifier = Modifier.size(18.dp))
                    }
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { loginExpanded = !loginExpanded }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Advanced: custom login and account ID",
                    color = Cf.Muted,
                    fontSize = 12.5.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (loginExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    tint = Cf.Muted,
                    modifier = Modifier.size(20.dp)
                )
            }

            AnimatedVisibility(visible = loginExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Leave any field empty and Maximus fills it in for you.", color = Cf.Dim, fontSize = 11.5.sp)
                    CfField(value = username, onValueChange = { username = it }, placeholder = "Username", icon = Icons.Default.Person)
                    CfField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "Password",
                        icon = Icons.Default.Lock,
                        secret = !passVisible,
                        trailing = {
                            IconButton(onClick = { passVisible = !passVisible }) {
                                Icon(
                                    if (passVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = "Show password",
                                    tint = Cf.Muted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    )
                    CfField(value = account, onValueChange = onAccountChange, placeholder = "Account ID", icon = Icons.Default.Tag)
                }
            }

            InstallButton(
                enabled = tokenReady && !isBusy,
                busy = isBusy,
                onClick = { onInstall(token.trim(), account.trim(), username.trim(), password.trim()) }
            )
        }
    }
}

@Composable
private fun StepBadge(number: Int, done: Boolean, active: Boolean) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(
                when {
                    done -> Cf.Green.copy(alpha = 0.14f)
                    active -> Cf.Orange.copy(alpha = 0.14f)
                    else -> Cf.Inset
                }
            )
            .border(1.dp, if (done) Cf.Green else if (active) Cf.Orange else Cf.CardBorder, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (done) {
            Icon(Icons.Default.Check, contentDescription = "Done", tint = Cf.Green, modifier = Modifier.size(15.dp))
        } else {
            Text(number.toString(), color = if (active) Cf.Orange else Cf.Muted, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StepRow(
    number: Int,
    done: Boolean,
    title: String,
    subtitle: String,
    action: String,
    onAction: () -> Unit,
    primary: Boolean = false,
    extra: (@Composable () -> Unit)? = null
) {
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        StepBadge(number = number, done = done, active = !done)
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Cf.Text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Cf.Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
            extra?.invoke()
        }
        Spacer(Modifier.width(10.dp))
        if (primary) {
            Button(
                onClick = onAction,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cf.Orange, contentColor = Color.White),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(action, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
            }
        } else {
            OutlinedButton(
                onClick = onAction,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Cf.CardBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cf.Text),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text(action, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(4.dp))
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Cf.Muted, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun CfField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    icon: ImageVector,
    secret: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        placeholder = { Text(placeholder, color = Cf.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = Cf.Orange, modifier = Modifier.size(18.dp)) },
        trailingIcon = trailing?.let { t -> { Row(verticalAlignment = Alignment.CenterVertically) { t() } } },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.5.sp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Cf.Inset,
            unfocusedContainerColor = Cf.Inset,
            focusedBorderColor = Cf.Orange,
            unfocusedBorderColor = Cf.CardBorder,
            focusedTextColor = Cf.Text,
            unfocusedTextColor = Cf.Text,
            cursorColor = Cf.Orange
        )
    )
}

@Composable
private fun VDivider() {
    Box(Modifier.width(1.dp).fillMaxHeight().background(Cf.CardBorder))
}

@Composable
private fun InstallButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(50.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Cf.Orange,
            contentColor = Color.White,
            disabledContainerColor = Cf.Inset,
            disabledContentColor = Cf.Dim
        )
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Cf.Muted)
            Spacer(Modifier.width(10.dp))
            Text("Installing…", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        } else {
            Icon(Icons.Default.RocketLaunch, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text(if (enabled) "Install BPB panel" else "Paste a token to install", fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

@Composable
private fun DeployProgressCard(isBusy: Boolean, statusText: String, logs: List<String>, done: Boolean) {
    val stage = when {
        done -> 4
        logs.any { it.contains("Uploading Worker", ignoreCase = true) || it.contains("[BPB]", ignoreCase = true) } -> 3
        logs.any { it.contains("KV namespace", ignoreCase = true) } -> 2
        logs.any { it.contains("Verifying API token", ignoreCase = true) || it.contains("Connecting to Cloudflare", ignoreCase = true) } -> 1
        else -> 0
    }
    val failed = !isBusy && !done && logs.any { it.contains("ERROR", true) || it.contains("FAIL", true) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Cf.Card)
            .border(1.dp, if (failed) Cf.Red.copy(alpha = 0.5f) else Cf.Blue.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = when {
                done -> "Panel deployed"
                failed -> "Install stopped"
                else -> "Installing on Cloudflare"
            },
            color = when {
                done -> Cf.Green
                failed -> Cf.Red
                else -> Cf.Text
            },
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            listOf("Token", "Storage", "Worker", "Ready").forEachIndexed { index, label ->
                val complete = done || stage > index + 1
                val current = !complete && stage == index + 1 && isBusy
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(if (complete) Cf.Green.copy(alpha = 0.15f) else Cf.Inset)
                            .border(1.5.dp, if (complete) Cf.Green else if (current) Cf.Blue else Cf.CardBorder, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (complete) Icon(Icons.Default.Check, contentDescription = null, tint = Cf.Green, modifier = Modifier.size(14.dp))
                        else Text((index + 1).toString(), color = if (current) Cf.Blue else Cf.Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(label, color = if (complete || current) Cf.Text else Cf.Muted, fontSize = 10.5.sp)
                }
                if (index < 3) {
                    Box(
                        Modifier
                            .padding(horizontal = 4.dp)
                            .padding(bottom = 16.dp)
                            .height(2.dp)
                            .weight(1f)
                            .background(if (stage > index + 1) Cf.Green else Cf.CardBorder)
                    )
                }
            }
        }
        if (isBusy) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = Cf.Blue,
                trackColor = Cf.Inset
            )
            if (statusText.isNotBlank()) {
                Text(statusText, color = Cf.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (logs.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 90.dp, max = 180.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Cf.Inset)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                logs.takeLast(30).forEach { line ->
                    Text(
                        text = line,
                        color = if (line.contains("ERROR", true) || line.contains("FAIL", true)) Cf.Red else Cf.Muted,
                        fontSize = 10.5.sp,
                        lineHeight = 14.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun DeployedResultCard(
    panel: ManagedPanel,
    onDismiss: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopy: (label: String, value: String) -> Unit,
    onImportToVpn: (ManagedPanel) -> Unit,
    onFixBpb: (ManagedPanel) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Cf.Card)
            .border(1.dp, Cf.Green.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Cf.Green, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(8.dp))
            Text("Your BPB panel is live", color = Cf.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Cf.Muted, modifier = Modifier.size(16.dp))
            }
        }

        PanelCredentialsCard(
            panelName = panel.name,
            link = panel.url,
            username = panel.username,
            password = panel.password,
            healthNote = panel.healthNote
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = { onOpenUrl(panel.url) },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Cf.Orange, contentColor = Color.White)
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open panel", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = { onCopy("Private link", panel.url) },
                modifier = Modifier.weight(1f).height(44.dp),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, Cf.CardBorder),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Cf.Text)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy link", fontSize = 13.sp)
            }
        }
        PanelActions(panel = panel, busy = false, onImportToVpn = onImportToVpn, onFixBpb = onFixBpb)
    }
}

/** Saved BPB workers, so "Add to VPN" and "FIX BPB" stay available after the result card is closed. */
@Composable
private fun DeployedPanelsCard(
    panels: List<ManagedPanel>,
    busy: Boolean,
    onImportToVpn: (ManagedPanel) -> Unit,
    onFixBpb: (ManagedPanel) -> Unit,
    onOpenUrl: (String) -> Unit
) {
    if (panels.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Cf.Card)
            .border(1.dp, Cf.CardBorder, RoundedCornerShape(18.dp))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Your BPB panels", color = Cf.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
        Text(
            "FIX BPB adds TLS fragmentation, a plain-TLS fingerprint, HTTP/1.1 ALPN and a tuned cipher list. Use it when a panel's configs do not connect.",
            color = Cf.Muted,
            fontSize = 12.sp,
            lineHeight = 16.sp
        )
        panels.forEach { panel ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Cf.Inset)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Cf.Orange.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = Cf.Orange, modifier = Modifier.size(20.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            panel.name.ifBlank { "BPB panel" },
                            color = Cf.Text,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            panel.host.ifBlank { panel.url },
                            color = Cf.Muted,
                            fontSize = 11.5.sp,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    IconButton(onClick = { onOpenUrl(panel.url) }) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = "Open panel", tint = Cf.Muted, modifier = Modifier.size(18.dp))
                    }
                }
                PanelActions(panel = panel, busy = busy, onImportToVpn = onImportToVpn, onFixBpb = onFixBpb)
            }
        }
    }
}

@Composable
private fun PanelActions(
    panel: ManagedPanel,
    busy: Boolean,
    onImportToVpn: (ManagedPanel) -> Unit,
    onFixBpb: (ManagedPanel) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(
            onClick = { onImportToVpn(panel) },
            enabled = !busy,
            modifier = Modifier.weight(1f).height(42.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Cf.Green.copy(alpha = 0.15f), contentColor = Cf.Green)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Add to VPN", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
        }
        OutlinedButton(
            onClick = { onFixBpb(panel) },
            enabled = !busy,
            modifier = Modifier.weight(1f).height(42.dp),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, Cf.Amber.copy(alpha = 0.6f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cf.Amber)
        ) {
            Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("FIX BPB", fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
        }
    }
}
