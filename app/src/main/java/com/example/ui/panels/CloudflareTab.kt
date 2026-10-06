package com.example.ui.panels

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
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
            busy = isBusy,
            onOpenDocs = onOpenDocs
        )

        SetupCard(
            token = token,
            onTokenChange = onTokenChange,
            account = account,
            onAccountChange = onAccountChange,
            isBusy = isBusy,
            onSignUp = onSignUp,
            onCreateToken = onCreateToken,
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
private fun CloudflareHero(panelCount: Int, tokenReady: Boolean, busy: Boolean, onOpenDocs: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF2A1405), Color(0xFF16100D), Color(0xFF0B1424))
                )
            )
            .border(1.dp, Cf.Orange.copy(alpha = 0.35f), RoundedCornerShape(24.dp))
            .padding(18.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Brush.linearGradient(listOf(Cf.Amber, Cf.Orange))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Cloud, contentDescription = null, tint = Color.White, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Cloudflare", color = Cf.Text, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        "Your own BPB panel on the free Workers plan",
                        color = Cf.Muted,
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp
                    )
                }
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.06f))
                        .clickable(onClick = onOpenDocs)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Language, contentDescription = null, tint = Cf.Muted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Docs", color = Cf.Muted, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HeroStat(
                    value = panelCount.toString(),
                    label = if (panelCount == 1) "Panel" else "Panels",
                    accent = if (panelCount > 0) Cf.Green else Cf.Text,
                    modifier = Modifier.weight(1f)
                )
                HeroStat(
                    value = if (tokenReady) "Ready" else "Needed",
                    label = "API token",
                    accent = if (tokenReady) Cf.Green else Cf.Amber,
                    modifier = Modifier.weight(1f)
                )
                HeroStat(
                    value = if (busy) "Running" else "Workers",
                    label = if (busy) "Install" else "Runs on",
                    accent = if (busy) Cf.Blue else Cf.Text,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(value, color = accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, color = Cf.Muted, fontSize = 11.sp)
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Cf.Card)
            .border(1.dp, Cf.CardBorder, RoundedCornerShape(24.dp))
            .padding(18.dp)
    ) {
        Text("Deploy a BPB panel", color = Cf.Text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Text("Three steps, about two minutes.", color = Cf.Muted, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))

        TimelineStep(
            number = 1,
            done = tokenReady,
            active = !tokenReady,
            title = "Cloudflare account",
            body = "Create a free account and confirm your email. Skip this if you already have one."
        ) {
            StepButton(text = "Open sign-up", icon = Icons.Default.PersonAdd, filled = false, onClick = onSignUp)
        }

        TimelineStep(
            number = 2,
            done = tokenReady,
            active = !tokenReady,
            title = "API token",
            body = "Opens Cloudflare with the Workers Scripts and Workers KV permissions already chosen."
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Cf.Inset)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    MiniStep("Scroll down and tap Continue to summary")
                    MiniStep("Tap Create Token")
                    MiniStep("Copy the token and come back here")
                }
                StepButton(text = "Create token", icon = Icons.Default.Key, filled = true, onClick = onCreateToken)
            }
        }

        TimelineStep(
            number = 3,
            done = false,
            active = tokenReady,
            title = "Paste and install",
            body = "The panel's login link, username and password appear here when it finishes.",
            last = true
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CfField(
                    value = token,
                    onValueChange = onTokenChange,
                    placeholder = "Paste the Cloudflare API token",
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
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = Cf.Orange, modifier = Modifier.size(18.dp))
                        }
                    }
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { loginExpanded = !loginExpanded }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Lock, contentDescription = null, tint = Cf.Muted, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Custom login and account (optional)",
                        color = Cf.Muted,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        if (loginExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = Cf.Muted
                    )
                }

                AnimatedVisibility(visible = loginExpanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Leave any of these empty and Maximus fills it in for you.",
                            color = Cf.Dim,
                            fontSize = 11.5.sp
                        )
                        CfField(
                            value = username,
                            onValueChange = { username = it },
                            placeholder = "Username",
                            icon = Icons.Default.Person
                        )
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
                        CfField(
                            value = account,
                            onValueChange = onAccountChange,
                            placeholder = "Account ID",
                            icon = Icons.Default.Tag
                        )
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
}

@Composable
private fun TimelineStep(
    number: Int,
    done: Boolean,
    active: Boolean,
    title: String,
    body: String,
    last: Boolean = false,
    content: @Composable () -> Unit
) {
    val ringColor = when {
        done -> Cf.Green
        active -> Cf.Orange
        else -> Cf.CardBorder
    }
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Column(
            modifier = Modifier.width(34.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            done -> Cf.Green.copy(alpha = 0.15f)
                            active -> Cf.Orange.copy(alpha = 0.15f)
                            else -> Cf.Inset
                        }
                    )
                    .border(1.5.dp, ringColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (done) {
                    Icon(Icons.Default.Check, contentDescription = "Done", tint = Cf.Green, modifier = Modifier.size(16.dp))
                } else {
                    Text(
                        number.toString(),
                        color = if (active) Cf.Orange else Cf.Muted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            if (!last) {
                Box(
                    Modifier
                        .padding(vertical = 4.dp)
                        .width(2.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(1.dp))
                        .background(if (done) Cf.Green.copy(alpha = 0.5f) else Cf.CardBorder)
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (last) 0.dp else 22.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Column {
                Text(
                    title,
                    color = Cf.Text,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(2.dp))
                Text(body, color = Cf.Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
            }
            content()
        }
    }
}

@Composable
private fun MiniStep(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(Cf.Orange)
        )
        Spacer(Modifier.width(10.dp))
        Text(text, color = Cf.Text, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun StepButton(text: String, icon: ImageVector, filled: Boolean, onClick: () -> Unit) {
    if (filled) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Cf.Orange, contentColor = Color.White)
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Cf.CardBorder),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Cf.Text)
        ) {
            Icon(icon, contentDescription = null, tint = Cf.Orange, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = Cf.Muted, modifier = Modifier.size(14.dp))
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
private fun InstallButton(enabled: Boolean, busy: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    if (enabled) Brush.horizontalGradient(listOf(Cf.Amber, Cf.Orange))
                    else Brush.horizontalGradient(listOf(Cf.Inset, Cf.Inset)),
                    RoundedCornerShape(16.dp)
                )
                .border(1.dp, if (enabled) Color.Transparent else Cf.CardBorder, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Cf.Muted)
                } else {
                    Icon(
                        Icons.Default.RocketLaunch,
                        contentDescription = null,
                        tint = if (enabled) Color.White else Cf.Dim,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = when {
                        busy -> "Installing…"
                        enabled -> "Install BPB panel"
                        else -> "Paste a token to install"
                    },
                    color = if (enabled) Color.White else Cf.Dim,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
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
            .clip(RoundedCornerShape(24.dp))
            .background(Cf.Card)
            .border(1.dp, if (failed) Cf.Red.copy(alpha = 0.5f) else Cf.Blue.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
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
            .clip(RoundedCornerShape(24.dp))
            .background(Cf.Card)
            .border(1.dp, Cf.Green.copy(alpha = 0.45f), RoundedCornerShape(24.dp))
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
            .clip(RoundedCornerShape(24.dp))
            .background(Cf.Card)
            .border(1.dp, Cf.CardBorder, RoundedCornerShape(24.dp))
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
