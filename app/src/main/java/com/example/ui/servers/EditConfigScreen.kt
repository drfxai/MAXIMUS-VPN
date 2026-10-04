package com.example.ui.servers

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.VlessProfile
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.launch

private val FLOWS = listOf("", "xtls-rprx-vision", "xtls-rprx-vision-udp443")
private val NETWORKS = listOf("tcp", "ws", "grpc", "xhttp", "httpupgrade", "http")
private val SECURITIES = listOf("none", "tls", "reality")
private val FINGERPRINTS = listOf("", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized", "unsafe")
private val ALPNS = listOf("", "h2", "http/1.1", "h2,http/1.1", "h3", "h3,h2,http/1.1")
private val TARGET_STRATEGIES = listOf(
    "AsIs", "UseIP", "UseIPv4", "UseIPv6", "UseIPv4v6", "UseIPv6v4",
    "ForceIP", "ForceIPv4", "ForceIPv6", "ForceIPv4v6", "ForceIPv6v4"
)

/**
 * Full editor for one node: every field the Xray outbound is built from, grouped like the
 * outbound JSON (server, transport, security, certificate).
 */
@Composable
fun EditConfigScreen(
    profile: VlessProfile,
    onBack: () -> Unit,
    onSave: suspend (VlessProfile) -> String?,
    onDelete: () -> Unit,
    onFetchFingerprint: suspend (address: String, port: Int, sni: String) -> Result<String>
) {
    val scope = rememberCoroutineScope()
    var draft by remember(profile.id) { mutableStateOf(profile) }
    var portText by remember(profile.id) { mutableStateOf(profile.port.toString()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var fetching by remember { mutableStateOf(false) }
    var fetchNote by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val edited = draft.copy(port = portText.trim().toIntOrNull() ?: -1)
    val dirty = edited != profile
    val security = draft.security.lowercase().ifBlank { "none" }
    val network = draft.transport.lowercase().ifBlank { "tcp" }

    fun leave() = if (dirty) confirmDiscard = true else onBack()

    fun fetchFingerprint() {
        if (fetching) return
        fetching = true
        fetchNote = null
        scope.launch {
            onFetchFingerprint(draft.address.trim(), edited.port, draft.sni.trim())
                .onSuccess {
                    draft = draft.copy(pinnedPeerCertSha256 = it)
                    fetchNote = "Fetched. Check it matches your server before saving."
                }
                .onFailure { fetchNote = "Could not fetch: ${it.localizedMessage ?: it.javaClass.simpleName}" }
            fetching = false
        }
    }

    BackHandler { leave() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppTheme.colors.background)
            .testTag("edit_config_screen")
    ) {
        // Top bar: back, title, delete, save.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopBarIcon(Icons.AutoMirrored.Filled.ArrowBack, "Back", AppTheme.colors.textPrimary) { leave() }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text("Edit Configuration", color = AppTheme.colors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = draft.name.ifBlank { "${draft.address}:$portText" },
                    color = AppTheme.colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            TopBarIcon(Icons.Default.Delete, "Delete", AppTheme.colors.statusError, Modifier.testTag("edit_config_delete")) {
                confirmDelete = true
            }
            if (saving) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = AppTheme.colors.primary)
                }
            } else {
                TopBarIcon(Icons.Default.Check, "Save", AppTheme.colors.primary, Modifier.testTag("edit_config_save")) {
                    error = null
                    saving = true
                    scope.launch {
                        val trimmed = edited.copy(
                            name = edited.name.trim(),
                            address = edited.address.trim(),
                            uuid = edited.uuid.trim(),
                            sni = edited.sni.trim(),
                            host = edited.host.trim(),
                            pinnedPeerCertSha256 = edited.pinnedPeerCertSha256.trim()
                        )
                        val refused = if (trimmed.port !in 1..65535) "Port must be between 1 and 65535." else onSave(trimmed)
                        saving = false
                        if (refused == null) onBack() else error = refused
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            error?.let { ErrorBanner(it) }

            EditSection("Server") {
                EditField("Remarks", draft.name) { draft = draft.copy(name = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditField("Address", draft.address, Modifier.weight(1f)) { draft = draft.copy(address = it) }
                    EditField(
                        "Port", portText, Modifier.width(92.dp),
                        keyboardType = KeyboardType.Number
                    ) { portText = it.filter(Char::isDigit).take(5) }
                }
                val secretLabel = when (draft.protocolType) {
                    com.example.data.model.ProtocolType.VLESS, com.example.data.model.ProtocolType.VMESS -> "UUID (id)"
                    com.example.data.model.ProtocolType.WIREGUARD -> "Private key"
                    else -> "Password"
                }
                EditField(secretLabel, draft.uuid, monospace = true) { draft = draft.copy(uuid = it) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditField("Encryption", draft.encryption, Modifier.weight(1f)) { draft = draft.copy(encryption = it) }
                    EditDropdown("Flow", draft.flow, FLOWS, Modifier.weight(1.4f)) { draft = draft.copy(flow = it) }
                }
            }

            EditSection("Transport") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val networks = if (draft.protocolType == com.example.data.model.ProtocolType.HYSTERIA2) listOf("hysteria") else NETWORKS
                    EditDropdown("Network", network, networks, Modifier.weight(1f)) { draft = draft.copy(transport = it) }
                    EditDropdown("Target strategy", draft.targetStrategy.ifBlank { "AsIs" }, TARGET_STRATEGIES, Modifier.weight(1f)) {
                        draft = draft.copy(targetStrategy = if (it == "AsIs") "" else it)
                    }
                }
                when (network) {
                    "grpc" -> EditField("Service name", draft.serviceName) { draft = draft.copy(serviceName = it) }
                    "tcp", "hysteria", "udp" -> Unit
                    else -> {
                        EditField("Host", draft.host) { draft = draft.copy(host = it) }
                        EditField("Path", draft.path, monospace = true) { draft = draft.copy(path = it) }
                        if (network == "xhttp" || network == "splithttp") {
                            val mode = com.example.vpn.engine.ProfileExtras.read(draft)
                                .optString(com.example.vpn.engine.ProfileExtras.XHTTP_MODE).ifBlank { "auto" }
                            EditDropdown("XHTTP mode", mode, com.example.vpn.engine.ProfileExtras.XHTTP_MODES) {
                                draft = com.example.vpn.engine.ProfileExtras.with(
                                    draft, com.example.vpn.engine.ProfileExtras.XHTTP_MODE, if (it == "auto") null else it
                                )
                            }
                        }
                    }
                }
                EditField(
                    "finalMask (JSON)", draft.finalMask, monospace = true, minLines = 3,
                    placeholder = "{ \"tcp\": [ … ] }"
                ) { draft = draft.copy(finalMask = it) }
            }

            EditSection("Security") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    EditDropdown("TLS", security, SECURITIES, Modifier.weight(1f)) { draft = draft.copy(security = it) }
                    if (security != "none") {
                        EditDropdown("Fingerprint", draft.fingerprint, FINGERPRINTS, Modifier.weight(1f)) {
                            draft = draft.copy(fingerprint = it)
                        }
                    }
                }
                if (security != "none") {
                    EditField("SNI", draft.sni) { draft = draft.copy(sni = it) }
                }
                if (security == "tls") {
                    EditDropdown("ALPN", draft.alpn, ALPNS) { draft = draft.copy(alpn = it) }
                    EditField("Cipher suites", draft.cipherSuites, monospace = true) { draft = draft.copy(cipherSuites = it) }
                    EditSwitch(
                        title = "allowInsecure",
                        subtitle = "Trust the server certificate pinned below",
                        checked = draft.allowInsecure
                    ) {
                        draft = draft.copy(allowInsecure = it)
                        if (it && draft.pinnedPeerCertSha256.isBlank()) fetchFingerprint()
                    }
                }
                if (security == "reality") {
                    EditField("Public key (pbk)", draft.publicKey, monospace = true) { draft = draft.copy(publicKey = it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EditField("Short ID", draft.shortId, Modifier.weight(1f), monospace = true) { draft = draft.copy(shortId = it) }
                        EditField("SpiderX", draft.spiderX, Modifier.weight(1f), monospace = true) { draft = draft.copy(spiderX = it) }
                    }
                }
            }

            if (security == "tls") {
                EditSection("Certificate & ECH") {
                    EditField("ECH config list", draft.echConfigList, monospace = true) { draft = draft.copy(echConfigList = it) }
                    EditField("ECH sockopt (JSON)", draft.echSockopt, monospace = true, minLines = 2) {
                        draft = draft.copy(echSockopt = it)
                    }
                    EditField(
                        "Verify peer certificate by name", draft.verifyPeerCertByName,
                        placeholder = "example.com, cdn.example.com"
                    ) { draft = draft.copy(verifyPeerCertByName = it) }
                    EditField(
                        "Certificate fingerprint (SHA-256)", draft.pinnedPeerCertSha256,
                        monospace = true, minLines = 2
                    ) { draft = draft.copy(pinnedPeerCertSha256 = it) }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .border(1.dp, AppTheme.colors.primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .clickable(enabled = !fetching) { fetchFingerprint() }
                            .testTag("fetch_cert_fingerprint"),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (fetching) {
                            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = AppTheme.colors.primary)
                        } else {
                            Icon(Icons.Default.Fingerprint, null, tint = AppTheme.colors.primary, modifier = Modifier.size(16.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (fetching) "Fetching…" else "Fetch certificate fingerprint",
                            color = AppTheme.colors.primary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                    fetchNote?.let { Text(it, color = AppTheme.colors.textMuted, fontSize = 11.sp) }
                }
            }
        }
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = "Delete node",
            text = "Remove '${profile.name}'? This cannot be undone.",
            confirm = "Delete",
            onConfirm = { confirmDelete = false; onDelete() },
            onDismiss = { confirmDelete = false }
        )
    }
    if (confirmDiscard) {
        ConfirmDialog(
            title = "Discard changes?",
            text = "Your edits to this node have not been saved.",
            confirm = "Discard",
            onConfirm = { confirmDiscard = false; onBack() },
            onDismiss = { confirmDiscard = false }
        )
    }
}

@Composable
private fun TopBarIcon(icon: ImageVector, description: String, tint: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AppTheme.colors.statusError.copy(alpha = 0.12f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.ErrorOutline, null, tint = AppTheme.colors.statusError, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(message, color = AppTheme.colors.statusError, fontSize = 12.sp)
    }
}

@Composable
private fun EditSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(AppTheme.colors.surfaceCard)
            .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            title.uppercase(),
            color = AppTheme.colors.textMuted,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        content()
    }
}

@Composable
private fun FieldLabel(label: String) {
    Text(
        label,
        color = AppTheme.colors.textSecondary,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 2.dp, bottom = 3.dp)
    )
}

@Composable
private fun EditField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    minLines: Int = 1,
    placeholder: String = "",
    keyboardType: KeyboardType = KeyboardType.Text,
    onValueChange: (String) -> Unit
) {
    Column(modifier = modifier.fillMaxWidth()) {
        FieldLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = minLines == 1,
            minLines = minLines,
            maxLines = if (minLines == 1) 1 else 8,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = TextStyle(
                color = AppTheme.colors.textPrimary,
                fontSize = if (monospace) 12.sp else 13.sp,
                fontFamily = if (monospace) FontFamily.Monospace else FontFamily.Default
            ),
            cursorBrush = SolidColor(AppTheme.colors.primary),
            modifier = Modifier.fillMaxWidth().testTag("edit_field_$label"),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 38.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AppTheme.colors.surfaceInput)
                        .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(10.dp))
                        .padding(horizontal = 10.dp, vertical = 9.dp),
                    contentAlignment = if (minLines == 1) Alignment.CenterStart else Alignment.TopStart
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, color = AppTheme.colors.textFaint, fontSize = 12.sp)
                    }
                    inner()
                }
            }
        )
    }
}

@Composable
private fun EditDropdown(
    label: String,
    value: String,
    options: List<String>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val choices = if (value in options) options else listOf(value) + options
    Column(modifier = modifier.fillMaxWidth()) {
        FieldLabel(label)
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(AppTheme.colors.surfaceInput)
                    .border(1.dp, AppTheme.colors.borderSubtle, RoundedCornerShape(10.dp))
                    .clickable(role = Role.DropdownList) { expanded = true }
                    .padding(start = 10.dp, end = 6.dp)
                    .testTag("edit_dropdown_$label"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    value.ifBlank { "none" },
                    color = if (value.isBlank()) AppTheme.colors.textMuted else AppTheme.colors.textPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(Icons.Default.ExpandMore, null, tint = AppTheme.colors.textMuted, modifier = Modifier.size(18.dp))
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(AppTheme.colors.surfaceCard)
            ) {
                choices.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option.ifBlank { "none" },
                                color = if (option == value) AppTheme.colors.primary else AppTheme.colors.textPrimary,
                                fontSize = 13.sp,
                                fontWeight = if (option == value) FontWeight.SemiBold else FontWeight.Normal
                            )
                        },
                        onClick = { expanded = false; onSelect(option) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EditSwitch(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = AppTheme.colors.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = AppTheme.colors.textMuted, fontSize = 11.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.scale(0.8f),
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AppTheme.colors.primary,
                uncheckedThumbColor = AppTheme.colors.toggleThumb,
                uncheckedTrackColor = AppTheme.colors.toggleTrack,
                uncheckedBorderColor = AppTheme.colors.borderSubtle
            )
        )
    }
}

@Composable
private fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = AppTheme.colors.textPrimary) },
        text = { Text(text, color = AppTheme.colors.textSecondary) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirm, color = AppTheme.colors.statusError, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = AppTheme.colors.textSecondary) }
        },
        containerColor = AppTheme.colors.surfaceCard
    )
}
