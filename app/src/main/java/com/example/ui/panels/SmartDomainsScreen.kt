package com.example.ui.panels

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.RayApplication
import com.example.data.model.ProfileType
import com.example.panels.CloudflareTokenHelper
import com.example.panels.cloudflare.CloudflareDnsApi
import com.example.panels.cloudflare.SmartDomains
import com.example.panels.cloudflare.SmartDomains.Action
import com.example.panels.cloudflare.SmartDomains.Use
import com.example.panels.cloudflare.SmartDomains.Zone
import com.example.panels.cloudflare.SmartDomainsStore
import com.example.panels.cloudflare.SmartFill
import com.example.panels.cloudflare.SmartNames
import com.example.panels.cloudflare.TokenTemplate
import com.example.ui.panels.servers.Badge
import com.example.ui.panels.servers.GhostButton
import com.example.ui.panels.servers.GroupCard
import com.example.ui.panels.servers.Mono
import com.example.ui.panels.servers.Notice
import com.example.ui.panels.servers.NoticeKind
import com.example.ui.panels.servers.PrimaryButton
import com.example.ui.panels.servers.RowDivider
import com.example.ui.panels.servers.Rows
import com.example.ui.panels.servers.ScreenFrame
import com.example.ui.panels.servers.SectionLabel
import com.example.ui.panels.servers.Sv
import com.example.ui.panels.servers.SvCard
import com.example.ui.panels.servers.SvField
import com.example.ui.panels.servers.TextAction
import com.example.ui.panels.servers.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Smart domains screen shows. The token itself is never held here, only what is being typed. */
data class SmartDomainsUi(
    val hasToken: Boolean = false,
    val tokenInput: String = "",
    val checking: Boolean = false,
    val zones: List<Zone> = emptyList(),
    val zone: Zone? = null,
    val names: List<SmartNames.Saved> = emptyList(),
    val use: Use = Use.CDN,
    val label: String = "",
    val address: String = "",
    val port: String = "443",
    /** "" when idle, else what is running ("Creating…"). */
    val busy: String = "",
    val outcome: String = "",
    /** Name to plain-words reason, when the last create found the name in use. */
    val conflict: Pair<String, String>? = null,
    val error: String = "",
    /** What was filled in from the selected config once the token worked; null shows the form. */
    val fill: SmartFill.Fill? = null
) {
    val connected get() = zone != null
}

/**
 * Smart domains: checks the user's DNS token, then creates and removes names on their zone. Every
 * Cloudflare call blocks, so it runs on [Dispatchers.IO]. The token is read from the store per call.
 */
class SmartDomainsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = SmartDomainsStore(app)
    private val api = CloudflareDnsApi()
    private val _state = MutableStateFlow(SmartDomainsUi())
    val state: StateFlow<SmartDomainsUi> = _state.asStateFlow()

    /** On open: the saved names, and with a saved token its zones again. */
    fun open() {
        viewModelScope.launch {
            val (hasToken, names) = withContext(Dispatchers.IO) { store.token().isNotBlank() to store.names() }
            _state.update { it.copy(hasToken = hasToken, names = names) }
            if (hasToken) checkToken()
        }
    }

    fun setToken(text: String) = _state.update { it.copy(tokenInput = text, error = "") }

    /** Lists the zones with the typed token (or the saved one), and saves a typed token once it works. */
    fun checkToken() {
        val typed = _state.value.tokenInput.trim()
        _state.update { it.copy(checking = true, error = "") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val token = typed.ifEmpty { store.token() }
                    require(token.isNotBlank()) { "Paste the token first" }
                    val zones = api.zones(token)
                    check(zones.isNotEmpty()) { "This token sees no domains. Give it Zone Read on your domain." }
                    if (typed.isNotEmpty()) store.saveToken(typed)
                    // Fill in only for a new token or before the first name; later opens go straight to the list.
                    val fill = if (typed.isNotEmpty() || _state.value.names.isEmpty()) SmartFill.fill(selectedServer()) else null
                    Triple(zones, store.zoneId(), fill)
                }
            }
            result.fold(
                onSuccess = { (zones, savedId, fill) ->
                    val zone = SmartFill.zone(zones, savedId) ?: zones.first()
                    _state.update {
                        if (fill != null) {
                            it.copy(
                                checking = false, hasToken = true, tokenInput = "", zones = zones, zone = zone, fill = fill,
                                use = fill.use, label = fill.label, address = fill.server.address, port = fill.server.port.toString()
                            )
                        } else {
                            it.copy(
                                checking = false, hasToken = true, tokenInput = "", zones = zones, zone = zone,
                                label = it.label.ifBlank { SmartDomains.randomLabel(SmartNames.prefix(it.use)) }
                            )
                        }
                    }
                },
                onFailure = { e -> _state.update { it.copy(checking = false, error = e.message ?: "Could not reach Cloudflare") } }
            )
        }
    }

    /** "Use copied token": the token from the clipboard, checked straight away. It is not kept unless it works. */
    fun useCopiedToken(token: String) {
        if (_state.value.checking || !TokenTemplate.looksLikeToken(token)) return
        _state.update { it.copy(tokenInput = token.trim(), error = "") }
        checkToken()
    }

    /** "Edit details": the form, with what was filled in. */
    fun editDetails() = _state.update { it.copy(fill = null) }

    /**
     * The config selected in Nodes, as the fill needs it, or null. A subscription entry has no single
     * server, so it fills nothing. Runs on [Dispatchers.IO].
     */
    private suspend fun selectedServer(): SmartFill.Server? {
        val p = runCatching {
            val app = RayApplication.instance
            app.settingsRepository.getSettings().selectedProfileId?.let { app.serverRepository.getProfileById(it) }
        }.getOrNull() ?: return null
        if (p.profileType == ProfileType.SUBSCRIPTION) return null
        return SmartFill.Server(p.name, p.protocolType.displayName, p.transport, p.security, p.address, p.port, p.countryCode)
    }

    fun pickZone(zone: Zone) {
        store.saveZoneId(zone.id)
        _state.update { it.copy(zone = zone, outcome = "", conflict = null) }
    }

    fun setUse(use: Use) = _state.update {
        if (it.use == use) it
        else it.copy(use = use, label = SmartDomains.randomLabel(SmartNames.prefix(use)), outcome = "", conflict = null)
    }

    fun newLabel() = _state.update { it.copy(label = SmartDomains.randomLabel(SmartNames.prefix(it.use))) }

    fun setLabel(text: String) = _state.update { it.copy(label = text.trim().lowercase(), conflict = null) }

    fun setAddress(text: String) = _state.update { it.copy(address = text.trim()) }

    fun setPort(text: String) = _state.update { it.copy(port = text.filter(Char::isDigit).take(5)) }

    /** Makes the name in the chosen zone, then says what happened. A conflict changes nothing. */
    fun create() {
        val s = _state.value
        val zone = s.zone ?: return
        if (formProblem(s) != null || s.busy.isNotBlank()) return
        val name = "${s.label}.${zone.name}"
        val port = if (s.use == Use.DNS_TUNNEL) null else SmartNames.parsePort(s.port)
        _state.update { it.copy(busy = "Creating…", outcome = "", conflict = null, error = "") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val token = store.token()
                    require(token.isNotBlank()) { "The token is gone; check it again" }
                    val want = SmartDomains.desired(name, s.address.lowercase(), s.use)
                    want to api.ensure(token, zone, want)
                }
            }
            result.fold(
                onSuccess = { (want, action) ->
                    if (action is Action.Conflict) {
                        _state.update { it.copy(busy = "", fill = null, conflict = want.name to SmartNames.conflict(want.name, action.why)) }
                    } else {
                        val saved = SmartNames.Saved(zone.id, zone.name, want.name, want.type, want.content, port, s.use, want.proxied)
                        val names = SmartNames.upsert(_state.value.names, saved)
                        withContext(Dispatchers.IO) { store.saveNames(names) }
                        _state.update {
                            it.copy(busy = "", fill = null, names = names, outcome = SmartNames.outcome(want.name, action),
                                label = SmartDomains.randomLabel(SmartNames.prefix(it.use)))
                        }
                    }
                },
                onFailure = { e -> _state.update { it.copy(busy = "", error = e.message ?: "Could not reach Cloudflare") } }
            )
        }
    }

    /** Deletes the record in Cloudflare (only if it still points where the app put it) and drops it from the list. */
    fun remove(n: SmartNames.Saved) {
        if (_state.value.busy.isNotBlank()) return
        _state.update { it.copy(busy = "Removing…", outcome = "", conflict = null, error = "") }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val token = store.token()
                    require(token.isNotBlank()) { "The token is gone; check it again" }
                    api.remove(token, Zone(n.zoneId, n.zone, ""), n.record())
                }
            }
            result.fold(
                onSuccess = { removed ->
                    val names = _state.value.names.filterNot { it == n }
                    withContext(Dispatchers.IO) { store.saveNames(names) }
                    _state.update {
                        it.copy(busy = "", names = names, outcome = if (removed) "Removed ${n.name}."
                        else "${n.name} no longer points at ${n.address} in Cloudflare, so it was left as it is and only taken off this list.")
                    }
                },
                onFailure = { e -> _state.update { it.copy(busy = "", error = e.message ?: "Could not reach Cloudflare") } }
            )
        }
    }

    /** Deletes the token from the phone. Names already made stay in Cloudflare and in the list. */
    fun forgetToken() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { store.forgetToken() }
            _state.update { SmartDomainsUi(names = it.names) }
        }
    }

    companion object {
        /** The first problem with the New name form, or null when it can be created. */
        fun formProblem(s: SmartDomainsUi): String? =
            SmartDomains.labelProblem(s.label) ?: SmartNames.addressProblem(s.address) ?: SmartNames.portFieldProblem(s.use, s.port)
    }
}

/**
 * Panels → Cloudflare → Smart domains: connect a DNS token, then make names on the user's own domain
 * with the right proxy setting for each use. Without a token it shows the three one-tap steps; right
 * after the token works it shows what was filled in from the selected config, to confirm in one tap.
 */
@Composable
internal fun SmartDomainsScreen(onBack: () -> Unit, vm: SmartDomainsViewModel = viewModel()) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmRemove by remember { mutableStateOf<SmartNames.Saved?>(null) }
    var confirmForget by remember { mutableStateOf(false) }
    // The clipboard is looked at each time the screen comes back (from Cloudflare's page). Only a short
    // masked hint is kept here; the token is read again when the user taps "Use copied token".
    var resumes by remember { mutableIntStateOf(0) }
    var copiedHint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.open() }
    LifecycleResumeEffect(Unit) {
        resumes++
        onPauseOrDispose { }
    }
    LaunchedEffect(resumes, ui.connected) {
        if (ui.connected) {
            copiedHint = null
        } else {
            delay(300) // Android 10+ only hands the clipboard to the app once its window has focus.
            copiedHint = copiedToken(context)?.let(TokenTemplate::masked)
        }
    }

    val openTokenPage = {
        runCatching { CloudflareTokenHelper.openTokenUrl(context, CloudflareTokenHelper.buildSmartDomainsTokenUrl()) }
            .onFailure { Toast.makeText(context, "Cannot open Cloudflare in a browser", Toast.LENGTH_SHORT).show() }
        Unit
    }
    val useCopied = {
        val token = copiedToken(context)
        if (token != null) vm.useCopiedToken(token)
        else {
            copiedHint = null
            Toast.makeText(context, "No token on the clipboard. Copy it in Cloudflare first.", Toast.LENGTH_SHORT).show()
        }
    }

    ScreenFrame(
        title = "Smart domains",
        subtitle = when {
            ui.connected && ui.fill != null -> "Ready to create"
            ui.connected -> "Names on your own Cloudflare domain"
            else -> "Panels → Cloudflare"
        },
        onBack = onBack
    ) {
        val fill = ui.fill
        when {
            ui.connected && fill != null -> FillContent(ui, fill, vm)
            ui.connected -> ConnectedContent(ui, vm, onRemove = { confirmRemove = it }, onForget = { confirmForget = true })
            else -> ConnectContent(ui, vm, copiedHint, openTokenPage, useCopied, onForget = { confirmForget = true })
        }
    }

    confirmRemove?.let { n ->
        ConfirmDialog(
            title = "Remove ${n.name}?",
            text = "The DNS record is deleted in Cloudflare, only if it still points at ${n.address}. Configs that use this name stop working.",
            action = "Remove",
            onConfirm = { vm.remove(n) },
            onDismiss = { confirmRemove = null }
        )
    }
    if (confirmForget) {
        ConfirmDialog(
            title = "Forget the token?",
            text = "The DNS token is deleted from this phone. Names already made stay in Cloudflare.",
            action = "Forget",
            onConfirm = { vm.forgetToken() },
            onDismiss = { confirmForget = false }
        )
    }
}

/** No working token yet: create it on Cloudflare's page, paste it back in one tap, confirm. */
@Composable
private fun ConnectContent(
    ui: SmartDomainsUi,
    vm: SmartDomainsViewModel,
    copiedHint: String?,
    openTokenPage: () -> Unit,
    useCopied: () -> Unit,
    onForget: () -> Unit
) {
    var manual by remember { mutableStateOf(false) }
    GroupCard {
        TokenStep(1, "Create token", active = true) {
            CloudflarePageText()
            TokenChips(TokenTemplate.SMART_DOMAINS_NAME, TokenTemplate.SMART_DOMAINS.map { it.label } + "Your zones")
            PrimaryButton("Create token in Cloudflare", openTokenPage, Modifier.fillMaxWidth(), icon = Icons.AutoMirrored.Rounded.OpenInNew)
        }
        RowDivider(0.dp)
        TokenStep(2, "Paste it back", active = copiedHint != null) {
            StepText("When you come back, Maximus offers the copied token. Tap once to check it.")
            if (copiedHint != null) {
                PrimaryButton(
                    if (ui.checking) "Checking…" else "Use copied token $copiedHint", useCopied, Modifier.fillMaxWidth(),
                    icon = Icons.Rounded.ContentPaste, enabled = !ui.checking
                )
            }
            if (manual || ui.tokenInput.isNotBlank() || ui.hasToken) {
                SvField(
                    value = ui.tokenInput, onValueChange = vm::setToken, label = "API token", secret = true,
                    placeholder = if (ui.hasToken) "A token is saved; paste a new one to replace it" else ""
                )
                GhostButton(
                    if (ui.checking) "Checking…" else "Check token", vm::checkToken, Modifier.fillMaxWidth(),
                    enabled = !ui.checking && (ui.tokenInput.isNotBlank() || ui.hasToken)
                )
            } else {
                TextAction("Paste it yourself", { manual = true }, color = Sv.Muted)
            }
        }
        RowDivider(0.dp)
        TokenStep(3, "Confirm", active = false) {
            StepText("Maximus fills in the domain, the subdomain, the server and the Cloudflare setting. You tap Confirm.")
        }
    }
    if (ui.error.isNotBlank()) Notice(ui.error, NoticeKind.ERROR)
    TokenFootnote("The token is limited to DNS on your domains and is stored encrypted on the phone. It is sent only to api.cloudflare.com.")
    if (ui.hasToken) TextAction("Forget token", onForget, color = Sv.Muted, enabled = !ui.checking)
}

/** Step 3: the token works; what was filled in, with one Confirm and a way to the full form. */
@Composable
private fun FillContent(ui: SmartDomainsUi, fill: SmartFill.Fill, vm: SmartDomainsViewModel) {
    val zone = ui.zone ?: return
    val idle = ui.busy.isBlank()
    val active = ui.zones.filter { it.status == "active" }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Sv.GreenSoft)
            .border(1.dp, Sv.Green.copy(alpha = 0.35f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Badge("Token works", Tone.GOOD, dot = true)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val count = ui.zones.size
            Text(if (count == 1) "1 domain found" else "$count domains found", color = Sv.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "${zone.name} · ${SmartNames.zoneStatus(zone.status).lowercase()}", color = Sv.Muted, fontSize = 13.sp,
                fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (ui.zones.size > 1 && active.size != 1) {
        Text("This token sees ${ui.zones.size} domains. Pick one:", color = Sv.Muted, fontSize = 12.5.sp)
        Choices(ui.zones, zone, { it.name }, mono = true, enabled = idle) { vm.pickZone(it) }
    }

    SectionLabel("Filled in for you")
    GroupCard {
        FillRow("Server", fill.title) { Text(fill.server.address, color = Sv.Muted, fontSize = 13.sp, fontFamily = Mono, maxLines = 1) }
        RowDivider()
        FillRow("Subdomain", "${ui.label}.${zone.name}", mono = true) { TextAction("change", vm::newLabel, color = Sv.Muted, enabled = idle) }
        RowDivider()
        FillRow("Cloudflare setting", if (ui.use.proxied) "Proxied (orange cloud)" else "DNS only (grey cloud)") {
            Badge(SmartNames.chip(ui.use), if (ui.use.proxied) Tone.ACCENT else Tone.NEUTRAL, dot = true)
        }
        RowDivider()
        FillRow(SmartFill.recordCaption(fill), SmartFill.recordLine(fill), mono = true) { }
    }
    Text(fill.reason, color = Sv.Muted, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 2.dp))
    if (ui.error.isNotBlank()) Notice(ui.error, NoticeKind.ERROR)
    PrimaryButton(
        ui.busy.ifBlank { "Confirm" }, vm::create, Modifier.fillMaxWidth(),
        enabled = idle && SmartDomainsViewModel.formProblem(ui) == null
    )
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        TextAction("Edit details", vm::editDetails, color = Sv.Muted, enabled = idle)
    }
}

/** One line of the filled-in summary: a small caption, the value, and something on the right. */
@Composable
private fun FillRow(caption: String, value: String, mono: Boolean = false, trailing: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(caption, color = Sv.Muted, fontSize = 13.sp)
            Text(
                value, color = Sv.Text, fontSize = if (mono) 14.sp else 15.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = if (mono) Mono else null, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

/** A working token: the zone, the names made, and the New name form. */
@Composable
private fun ConnectedContent(ui: SmartDomainsUi, vm: SmartDomainsViewModel, onRemove: (SmartNames.Saved) -> Unit, onForget: () -> Unit) {
    val zone = ui.zone ?: return
    val idle = ui.busy.isBlank()

    GroupCard {
        InfoRow(zone.name, "Token can read zones and edit DNS here only") {
            Badge(SmartNames.zoneStatus(zone.status), if (zone.status == "active") Tone.GOOD else Tone.WARN, dot = true)
        }
        if (ui.zones.size > 1) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("This token sees ${ui.zones.size} domains. Pick one:", color = Sv.Muted, fontSize = 12.5.sp)
                Choices(ui.zones, zone, { it.name }, mono = true, enabled = idle) { vm.pickZone(it) }
            }
        }
        RowDivider()
        InfoRow(
            "SSL/TLS mode",
            "Set it to Full (strict) in Cloudflare. The token cannot read this setting, so the app does not show it as checked."
        ) { Badge("Set it yourself", Tone.WARN, dot = true) }
    }

    SectionLabel("Your names")
    GroupCard {
        if (ui.names.isEmpty()) {
            Text(
                "No names yet. Names you make here are listed on this phone.",
                color = Sv.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
            )
        } else {
            Rows(ui.names) { n ->
                InfoRow(n.name, SmartNames.detail(n), mono = true) {
                    Badge(SmartNames.badge(n.proxied), if (n.proxied) Tone.ACCENT else Tone.NEUTRAL, dot = true)
                    IconButton(onClick = { onRemove(n) }, enabled = idle, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Delete, "Remove ${n.name}", tint = if (idle) Sv.Muted else Sv.Dim, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }

    SectionLabel("New name")
    SvCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("What is it for?", color = Sv.Muted, fontSize = 13.sp)
            Choices(Use.values().toList(), ui.use, SmartNames::chip, enabled = idle) { vm.setUse(it) }
            NameField(ui.label, zone.name, vm::setLabel, vm::newLabel)
            SmartDomains.labelProblem(ui.label)?.let { FieldError(it) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SvField(
                    value = ui.address, onValueChange = vm::setAddress, label = "Server address", modifier = Modifier.weight(1f),
                    placeholder = "203.0.113.24", keyboard = KeyboardType.Uri, mono = true
                )
                if (ui.use != Use.DNS_TUNNEL) {
                    SvField(
                        value = ui.port, onValueChange = vm::setPort, label = "Port", modifier = Modifier.width(104.dp),
                        keyboard = KeyboardType.Number, mono = true
                    )
                }
            }
            if (ui.address.isNotBlank()) SmartNames.addressProblem(ui.address)?.let { FieldError(it) }
            SmartNames.portFieldProblem(ui.use, ui.port)?.let { FieldError(it) }
            Text(SmartNames.hint(ui.use), color = Sv.Muted, fontSize = 12.5.sp, lineHeight = 17.sp)
        }
    }
    PrimaryButton(
        ui.busy.ifBlank { "Create name" }, vm::create, Modifier.fillMaxWidth(),
        enabled = idle && SmartDomainsViewModel.formProblem(ui) == null
    )

    ui.conflict?.let { (name, text) ->
        SectionLabel("When a name is already used")
        GroupCard { InfoRow(name, text, mono = true) { Badge("In use", Tone.WARN, dot = true) } }
    }
    if (ui.outcome.isNotBlank()) Notice(ui.outcome, NoticeKind.OK)
    if (ui.error.isNotBlank()) Notice(ui.error, NoticeKind.ERROR)
    TextAction("Forget token", onForget, color = Sv.Muted, enabled = idle)
}

/** A row with a title, a wrapping description and a trailing badge (unlike ListRow, the text is not cut). */
@Composable
private fun InfoRow(title: String, text: String, mono: Boolean = false, trailing: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title, color = Sv.Text, fontSize = if (mono) 14.sp else 15.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = if (mono) Mono else null, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(text, color = Sv.Muted, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(
    options: List<T>,
    selected: T?,
    label: (T) -> String,
    mono: Boolean = false,
    enabled: Boolean = true,
    onPick: (T) -> Unit
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { o ->
            val on = o == selected
            Text(
                label(o), color = if (on) Sv.Blue else Sv.Muted, fontSize = 13.sp,
                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, fontFamily = if (mono) Mono else null,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Sv.AccentSoft else Sv.Raised)
                    .border(1.dp, if (on) Sv.Accent.copy(alpha = 0.4f) else Sv.CardBorder, RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled) { onPick(o) }.padding(horizontal = 11.dp, vertical = 7.dp)
            )
        }
    }
}

/** The label, the zone shown after it, and ↻ for a new random label. */
@Composable
private fun NameField(label: String, zone: String, onChange: (String) -> Unit, onRegenerate: () -> Unit) {
    OutlinedTextField(
        value = label, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
        label = { Text("Name") }, singleLine = true,
        textStyle = TextStyle(fontSize = 15.sp, fontFamily = Mono),
        suffix = { Text(".$zone", color = Sv.Dim, fontSize = 15.sp, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingIcon = { IconButton(onClick = onRegenerate) { Icon(Icons.Rounded.Refresh, "New random name", tint = Sv.Blue) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrect = false),
        shape = RoundedCornerShape(10.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Sv.Text, unfocusedTextColor = Sv.Text,
            focusedBorderColor = Sv.Accent, unfocusedBorderColor = Sv.CardBorder,
            focusedLabelColor = Sv.Blue, unfocusedLabelColor = Sv.Muted,
            cursorColor = Sv.Blue, focusedContainerColor = Sv.Card, unfocusedContainerColor = Sv.Card
        )
    )
}

@Composable
private fun FieldError(text: String) = Text(text, color = Sv.Red, fontSize = 12.5.sp, lineHeight = 17.sp)

@Composable
private fun ConfirmDialog(title: String, text: String, action: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Sv.Card,
        title = { Text(title, color = Sv.Text) },
        text = { Text(text, color = Sv.Muted) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(action, color = Sv.Red) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = Sv.Muted) } }
    )
}
