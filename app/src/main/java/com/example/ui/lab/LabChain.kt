package com.example.ui.lab

import androidx.compose.ui.res.stringResource
import com.example.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.LabText
import com.example.vpn.sidecar.EngineChain
import com.example.vpn.sidecar.SingBoxSidecar
import kotlin.math.roundToInt

data class ChainUi(
    val hopIds: List<String> = listOf("tor", "psiphon"),
    val region: String = "",
    val readyEngines: Set<String> = emptySet(),
    val availabilityLoaded: Boolean = false,
    val saving: Boolean = false,
    val testing: Boolean = false,
    val testPassed: Boolean? = null,
    val testMessage: String? = null,
    val savedName: String? = null
) {
    val busy: Boolean get() = saving || testing

    // The editor shows the wire path from the device; saved profiles keep the existing local SOCKS order.
    fun engineChain() = EngineChain.Chain(hopIds.asReversed().map { id ->
        if (id.startsWith("saved:")) {
            EngineChain.Hop(SingBoxSidecar.id, profileId = id.removePrefix("saved:"))
        } else EngineChain.Hop(id, region = if (id == "psiphon") region else "")
    })

    fun problem(configs: List<LabConfigOption>): String? {
        if (!availabilityLoaded) return "Checking installed engines…"
        val chain = engineChain()
        EngineChain.problem(chain)?.let { return it }
        for (hop in chain.hops) {
            if (hop.engineId !in readyEngines) return "${EngineChain.titleOf(hop.engineId)} is unavailable on this device."
            if (hop.profileId.isNotEmpty() && configs.none { it.id == hop.profileId && it.supportsSavedNodeChain }) {
                return "A saved config is unavailable or incompatible with chaining."
            }
        }
        return null
    }
}

private val chainRegions = listOf("", "US", "DE", "NL", "GB", "FR", "CA")

private enum class HopSource(val title: String, val icon: ImageVector, val ids: List<String> = emptyList()) {
    SAVED("Saved configs", Icons.Rounded.Layers),
    WARP("WARP", Icons.Rounded.CellTower, listOf("masque", "aether")),
    PSIPHON("Psiphon", Icons.Rounded.VpnKey, listOf("psiphon")),
    TOR("Tor", Icons.Rounded.Hub, listOf("tor")),
    SERVERLESS("Serverless", Icons.Rounded.Dns, listOf("serverless")),
    MORE("More", Icons.Rounded.MoreHoriz, listOf("matrix-transport", "radiance"))
}

private val panelShape = RoundedCornerShape(18.dp)
private val rowShape = RoundedCornerShape(12.dp)

@Composable
internal fun ChainPage(
    c: LabColors,
    s: ChainUi,
    configs: List<LabConfigOption>,
    vpnOn: Boolean,
    onHops: (List<String>) -> Unit,
    onRegion: (String) -> Unit,
    onTest: () -> Unit,
    onCancelTest: () -> Unit,
    onSave: () -> Unit,
    onPsiphonSettings: (String) -> Unit
) {
    var source by rememberSaveable { mutableStateOf<HopSource?>(null) }
    var checkingCompatibility by rememberSaveable { mutableStateOf(false) }
    var psiphonSettings by rememberSaveable { mutableStateOf(false) }
    val problem = s.problem(configs)
    val optionsAvailable = !s.busy && s.hopIds.size < EngineChain.MAX_HOPS

    fun move(from: Int, to: Int) {
        if (from != to && to in s.hopIds.indices && !s.busy) {
            onHops(s.hopIds.toMutableList().apply { add(to, removeAt(from)) })
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Gutter)
            .padding(top = 12.dp, bottom = 24.dp).testTag("connection_chain"),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        ChainPanel(c) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabText(stringResource(R.string.ui_traffic_route), c.text, 20.sp, FontWeight.Bold, Modifier.weight(1f))
                val (label, color) = when {
                    s.testing -> "Testing…" to c.accent
                    s.testPassed == true -> "Verified" to c.good
                    s.testPassed == false -> "Test failed" to c.bad
                    else -> "Not tested" to c.text2
                }
                val pill = RoundedCornerShape(50)
                LabText(label, color, 11.sp, modifier = Modifier.border(1.dp, color.copy(alpha = .55f), pill)
                    .padding(horizontal = 9.dp, vertical = 4.dp), maxLines = 1)
            }
            Endpoint(c, Icons.Rounded.Smartphone, stringResource(R.string.ui_this_device))
            s.hopIds.forEachIndexed { index, id ->
                val node = configs.firstOrNull { "saved:${it.id}" == id }
                val isSaved = id.startsWith("saved:")
                val position = when {
                    index == 0 -> "Entry"
                    index == s.hopIds.lastIndex -> "Exit"
                    else -> "Relay"
                }
                ChainConnector(c)
                key(id) {
                    HopRow(c, node?.name ?: if (isSaved) "Saved config unavailable" else EngineChain.titleOf(id),
                        "${if (isSaved) "Saved config" else "Engine"} · $position",
                        if (isSaved) Icons.Rounded.Layers else HopSource.entries.firstOrNull { id in it.ids }?.icon
                            ?: Icons.Rounded.Hub,
                        index, s.hopIds.lastIndex, s.busy, onMove = { move(index, it) },
                        onRemove = { onHops(s.hopIds.filterIndexed { at, _ -> at != index }) },
                        onOptions = if (id == "psiphon") ({ source = HopSource.PSIPHON }) else null)
                }
            }
            if (s.hopIds.isEmpty()) LabText(stringResource(R.string.ui_add_at_least_two_hops), c.text2, 13.sp)
            ChainConnector(c)
            Endpoint(c, Icons.Rounded.Public, stringResource(R.string.ui_internet))
        }

        ChainPanel(c) {
            Row(Modifier.fillMaxWidth().clip(rowShape)
                .clickable(enabled = optionsAvailable, role = Role.Button) { source = HopSource.SAVED },
                verticalAlignment = Alignment.CenterVertically) {
                LabText(stringResource(R.string.ui_add_a_hop), c.text, 20.sp, FontWeight.Bold, Modifier.weight(1f))
                Icon(Icons.Rounded.Add, null, tint = c.accent, modifier = Modifier.size(24.dp))
            }
            HopSource.entries.forEach { item ->
                ChainRow(c, item.title, item.icon, enabled = optionsAvailable) { source = item }
            }
            if (s.hopIds.size == EngineChain.MAX_HOPS) {
                LabText(stringResource(R.string.ui_this_route_has_the_maximum, EngineChain.MAX_HOPS), c.text2, 12.sp)
            }
        }

        ChainRow(c, "Compatibility", Icons.Rounded.Security, subtitle = when {
            !s.availabilityLoaded -> stringResource(R.string.ui_checking_installed_engines)
            problem != null -> stringResource(R.string.ui_review_route)
            else -> stringResource(R.string.ui_ready_to_test)
        }) { checkingCompatibility = true }
        s.testMessage?.let {
            LabText(it, if (s.testPassed == true) c.good else c.text2, 13.sp, modifier = Modifier.padding(horizontal = 2.dp),
                lineHeight = 18.sp, maxLines = 5)
        }
        if (vpnOn) LabText(stringResource(R.string.ui_disconnect_the_active_vpn_to), c.text2, 12.sp)
        Box(
            Modifier.fillMaxWidth().heightIn(min = 51.dp).clip(rowShape)
                .background(Brush.horizontalGradient(if (!vpnOn && problem == null && !s.busy)
                    listOf(c.accent, c.accent2) else listOf(c.cardAlt, c.card)))
                .clickable(enabled = !vpnOn && problem == null && !s.busy, role = Role.Button, onClick = onTest)
                .testTag("test_connection_chain"),
            contentAlignment = Alignment.Center
        ) {
            if (s.testing) CircularProgressIndicator(Modifier.size(20.dp), color = c.accent, strokeWidth = 2.dp)
            else LabText(stringResource(R.string.ui_test_chain), if (!vpnOn && problem == null) c.onAccent else c.text2, 15.sp, FontWeight.Bold)
        }
        if (s.testing) TextButton(onClick = onCancelTest, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.ui_cancel_chain_test))
        }
        OutlinedButton(onClick = onSave, enabled = problem == null && !s.busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 51.dp).testTag("save_connection_chain"), shape = rowShape,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (problem == null) c.accent else c.stroke)) {
            LabText(if (s.saving) stringResource(R.string.ui_saving) else stringResource(R.string.ui_save_chain), c.text, 15.sp, FontWeight.Bold)
        }
        s.savedName?.let { LabText(stringResource(R.string.ui_saved_to_nodes_as, it), c.good, 13.sp, maxLines = 3) }
    }

    source?.let { item ->
        HopPicker(c, item, s, configs, onDismiss = { source = null }, onRegion = onRegion,
            onPsiphonSettings = { source = null; psiphonSettings = true },
            onAdd = { id ->
                onHops(if (EngineChain.entryOnly(id)) listOf(id) + s.hopIds else s.hopIds + id)
                source = null
            })
    }
    if (checkingCompatibility) AlertDialog(
        onDismissRequest = { checkingCompatibility = false },
        title = { Text(stringResource(R.string.ui_compatibility)) },
        text = { Text(problem ?: "The selected hops can be run in this order. Test the chain to verify traffic on this network.") },
        confirmButton = { TextButton(onClick = { checkingCompatibility = false }) { Text(stringResource(R.string.ui_done)) } }
    )
    if (psiphonSettings) {
        var json by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { psiphonSettings = false },
            title = { Text(stringResource(R.string.ui_psiphon_network_settings)) },
            text = { OutlinedTextField(json, { json = it }, label = { Text(stringResource(R.string.ui_authorized_settings_json)) }, minLines = 2, maxLines = 5) },
            confirmButton = {
                TextButton(enabled = json.isNotBlank(), onClick = { onPsiphonSettings(json); psiphonSettings = false }) {
                    Text(stringResource(R.string.ui_save_securely))
                }
            },
            dismissButton = { TextButton(onClick = { psiphonSettings = false }) { Text(stringResource(R.string.ui_cancel)) } }
        )
    }
}

@Composable
private fun ChainPanel(c: LabColors, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(panelShape)
            .background(Brush.verticalGradient(listOf(c.cardAlt, c.card)))
            .border(1.dp, c.accent.copy(alpha = if (c.dark) .32f else .22f), panelShape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) { content() }
}

@Composable
private fun ChainRow(c: LabColors, label: String, icon: ImageVector, enabled: Boolean = true,
                     subtitle: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(rowShape).background(c.cardAlt)
        .border(1.dp, c.stroke, rowShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = if (enabled) c.accent else c.text3, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f)) {
            LabText(label, if (enabled) c.text else c.text2, 14.sp, FontWeight.SemiBold, maxLines = 2)
            subtitle?.let { LabText(it, c.text2, 12.sp, maxLines = 3) }
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Endpoint(c: LabColors, icon: ImageVector, title: String) {
    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, tint = c.text, modifier = Modifier.size(22.dp))
        LabText(title, c.text, 14.sp, FontWeight.Medium)
    }
}

@Composable
private fun ChainConnector(c: LabColors) {
    Icon(Icons.Rounded.ArrowDownward, null, tint = c.accent,
        modifier = Modifier.padding(start = 13.dp).size(20.dp))
}

@Composable
private fun HopRow(
    c: LabColors, name: String, subtitle: String, icon: ImageVector, index: Int, last: Int, busy: Boolean,
    onMove: (Int) -> Unit, onRemove: () -> Unit, onOptions: (() -> Unit)?
) {
    var menu by remember { mutableStateOf(false) }
    var dragOffset by remember { mutableStateOf(0f) }
    var rowHeight by remember { mutableStateOf(0) }
    val gap = with(LocalDensity.current) { 34.dp.toPx() }
    val travel = rowHeight + gap
    val shape = rowShape
    Row(
        Modifier.fillMaxWidth().heightIn(min = 59.dp).onSizeChanged { rowHeight = it.height }
            .zIndex(if (dragOffset == 0f) 0f else 1f).graphicsLayer { translationY = dragOffset }
            .clip(shape).background(c.cardAlt)
            .border(1.dp, c.accent.copy(alpha = .38f), shape).padding(start = 12.dp, top = 5.dp, bottom = 5.dp, end = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(25.dp))
        Column(Modifier.weight(1f)) {
            LabText(name, c.text, 15.sp, FontWeight.Bold, maxLines = 2)
            LabText(subtitle, c.text2, 12.sp, maxLines = 2)
        }
        Box {
            Box(
                Modifier.size(48.dp).clip(shape)
                    .pointerInput(index, last, busy, travel) {
                        if (!busy) {
                            detectVerticalDragGestures(
                                onDragStart = { dragOffset = 0f },
                                onDragCancel = { dragOffset = 0f },
                                onDragEnd = {
                                    val destination = (index + (dragOffset / travel).roundToInt()).coerceIn(0, last)
                                    dragOffset = 0f
                                    onMove(destination)
                                }
                            ) { change, amount ->
                                change.consume()
                                dragOffset = (dragOffset + amount).coerceIn(-index * travel, (last - index) * travel)
                            }
                        }
                    }
                    .clickable(enabled = !busy, role = Role.Button, onClickLabel = "Edit ${name} hop") { menu = true },
                contentAlignment = Alignment.Center
            ) { Icon(Icons.Rounded.DragIndicator, "Reorder $name", tint = c.text2) }
            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.ui_move_up)) }, enabled = index > 0, onClick = { menu = false; onMove(index - 1) })
                DropdownMenuItem(text = { Text(stringResource(R.string.ui_move_down)) }, enabled = index < last, onClick = { menu = false; onMove(index + 1) })
                onOptions?.let {
                    DropdownMenuItem(text = { Text(stringResource(R.string.ui_psiphon_options)) }, onClick = { menu = false; it() })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.ui_remove_hop)) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun HopPicker(
    c: LabColors, source: HopSource, state: ChainUi, configs: List<LabConfigOption>, onDismiss: () -> Unit,
    onRegion: (String) -> Unit, onPsiphonSettings: () -> Unit, onAdd: (String) -> Unit
) {
    var search by remember { mutableStateOf("") }
    fun offer(id: String, label: String, detail: String?, engineId: String, compatible: Boolean): Triple<String, String, Boolean> {
        val reason = when {
            id in state.hopIds -> "Already in this route"
            !compatible -> "This config needs its original engine"
            EngineChain.entryOnly(id) && state.hopIds.any(EngineChain::entryOnly) -> "This route already has an entry-only carrier"
            engineId !in state.readyEngines -> "Unavailable on this device"
            state.hopIds.size >= EngineChain.MAX_HOPS -> "Route is full"
            else -> null
        }
        return Triple(label, reason ?: detail.orEmpty(), reason == null && !state.busy)
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(source.title) }, text = {
        Column(Modifier.widthIn(min = 220.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (source == HopSource.SAVED) {
                OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.ui_search_configs)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                val visible = configs.filter { it.name.contains(search.trim(), true) || it.detail.contains(search.trim(), true) }
                    .sortedByDescending { it.supportsSavedNodeChain }
                if (visible.isEmpty()) Text(stringResource(R.string.ui_no_saved_configs_found))
                visible.forEach { config ->
                    val id = "saved:${config.id}"
                    val (title, detail, enabled) = offer(id, config.name, config.detail, SingBoxSidecar.id, config.supportsSavedNodeChain)
                    ChainRow(c, title, source.icon, enabled = enabled, subtitle = detail) { onAdd(id) }
                }
            } else {
                source.ids.forEach { id ->
                    val (title, detail, enabled) = offer(id, EngineChain.titleOf(id), if (EngineChain.entryOnly(id)) "First hop from this device" else null, id, true)
                    ChainRow(c, title, source.icon, enabled = enabled, subtitle = detail.ifBlank { null }) { onAdd(id) }
                }
            }
            if (source == HopSource.PSIPHON) {
                var regions by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { regions = true }) { Text(stringResource(R.string.ui_exit_country, state.region.ifBlank { "Any" })) }
                    DropdownMenu(regions, onDismissRequest = { regions = false }) {
                        chainRegions.forEach { code ->
                            DropdownMenuItem(text = { Text(code.ifBlank { "Any" }) }, onClick = {
                                onRegion(code)
                                regions = false
                            })
                        }
                    }
                }
                TextButton(onClick = onPsiphonSettings) { Text(stringResource(R.string.ui_psiphon_network_settings)) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ui_done)) } })
}
