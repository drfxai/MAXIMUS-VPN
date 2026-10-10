package com.example.ui.panels.servers

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import com.example.panels.servers.ServerToolCatalog

/** Toasts, haptics and panel reloads from [ServersViewModel] events. Call once while the Panels screen is shown. */
@Composable
internal fun ServersEffects(vm: ServersViewModel, onPanelsChanged: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(vm) {
        vm.events.collect { e ->
            when (e) {
                is ServersEvent.Message -> Toast.makeText(context, e.text, Toast.LENGTH_SHORT).show()
                ServersEvent.Success -> haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                ServersEvent.PanelsChanged -> onPanelsChanged()
            }
        }
    }
}

/** The Servers tab: My Servers list with live reachability. */
@Composable
internal fun ServersTabHost(vm: ServersViewModel, state: ServersState, onInstallCenter: () -> Unit, onTunnel: () -> Unit = {}) {
    LaunchedEffect(state.servers.size) { vm.refreshReach() }
    MyServersTab(vm.rows(), onAdd = { vm.openAdd() }, onOpen = vm::openDetail, onInstallCenter = onInstallCenter,
        tunnels = vm.tunnel.tabUi().tunnels.size, onTunnel = onTunnel)
}

/** The Tunnel tab: Maximus Tunnel between a server in Iran and one abroad. */
@Composable
internal fun TunnelTabHost(
    vm: ServersViewModel,
    state: ServersState,
    // Passed so the tab recomposes when a tunnel's status changes.
    @Suppress("UNUSED_PARAMETER") tunnelState: TunnelState,
    onServers: () -> Unit
) {
    LaunchedEffect(state.servers.size) { vm.tunnel.checkAll() }
    TunnelTab(vm.tunnel.tabUi(), TunnelActions(
        onSetup = { vm.tunnel.openSetup() },
        onOpen = vm.tunnel::openDetail,
        onAddServer = { onServers(); vm.openAdd() }
    ))
}

/** The Install tab: the tool catalog plus suggestions for the user's servers. */
@Composable
internal fun InstallTabHost(vm: ServersViewModel, @Suppress("UNUSED_PARAMETER") state: ServersState) {
    InstallCenterTab(
        tools = ServerToolCatalog.all,
        installedOn = vm.installedCounts(),
        advice = vm.advice(),
        onOpen = { if (it == ServerToolCatalog.MAXIMUS_TUNNEL) vm.tunnel.openSetup() else vm.openWizard(it) },
        onAdvice = { if (it.toolId == ServerToolCatalog.MAXIMUS_TUNNEL) vm.tunnel.openSetup() else vm.openWizard(it.toolId, it.serverId) }
    )
}

private sealed interface Confirm {
    data class Uninstall(val serverId: String, val toolId: String) : Confirm
    data class Remove(val serverId: String) : Confirm
    data class PasswordOff(val serverId: String) : Confirm
    data class RemoveTunnel(val iranId: String) : Confirm
    data class Reseed(val iranId: String) : Confirm
}

/**
 * Full-screen pages over the Panels tabs (Add server, install wizard, server page).
 * Returns false when nothing is open so the caller draws its tabs.
 */
@Composable
internal fun ServersOverlay(
    vm: ServersViewModel,
    state: ServersState,
    onOpenPanel: (String) -> Unit,
    onInstallCenter: () -> Unit
): Boolean {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val copy: (String) -> Unit = {
        clipboard.setText(AnnotatedString(it))
        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    val tunnelState by vm.tunnel.state.collectAsState()
    if (tunnelState.route != TunnelRoute.None) {
        TunnelOverlay(vm, tunnelState, copy) { confirm = it }
        ConfirmHost(vm, confirm) { confirm = null }
        return true
    }

    when (val route = state.route) {
        ServersRoute.None -> return false
        ServersRoute.Add -> {
            BackHandler { vm.close() }
            AddServerScreen(state.add, AddServerActions(vm::changeAdd, vm::continueAdd, vm::trustAdd, vm::close))
        }
        ServersRoute.Wizard -> {
            val w = state.wizard ?: return false
            BackHandler { vm.wizardBack() }
            InstallWizardScreen(
                w,
                WizardActions(
                    onBack = vm::wizardBack,
                    onPickServer = vm::pickServer,
                    onChange = vm::changeWizard,
                    onNext = vm::wizardNext,
                    onRandomPort = vm::randomPort,
                    onCheckDns = vm::checkDns,
                    onInstall = vm::install,
                    onTest = vm::wizardTest,
                    onCopy = copy,
                    onOpenServer = vm::openServerFromWizard,
                    onDone = vm::close,
                    onRetry = vm::retry
                )
            )
        }
        is ServersRoute.Detail -> {
            val ui = vm.detailUi(route.id) ?: return false
            val id = route.id
            BackHandler { vm.close() }
            ServerDetailScreen(
                ui,
                ServerDetailActions(
                    onBack = vm::close,
                    onRefresh = { vm.refresh(id) },
                    onSignIn = { vm.openAdd(id) },
                    onKeyLogin = { vm.addKeyLogin(id) },
                    onPasswordLogin = { off -> if (off) confirm = Confirm.PasswordOff(id) else vm.setPasswordLogin(id, false) },
                    onTest = { vm.test(id, it) },
                    onRestart = { vm.restart(id, it) },
                    onLogs = { vm.logs(id, it) },
                    onRotatePort = { vm.rotatePort(id, it) },
                    onRotateKey = { vm.rotateKey(id, it) },
                    onOpenPanel = { vm.panelIdFor(id)?.let { pid -> vm.close(); onOpenPanel(pid) } },
                    onCopy = copy,
                    onUninstall = { confirm = Confirm.Uninstall(id, it) },
                    onInstall = { vm.openWizard(it, id) },
                    onInstallCenter = { vm.close(); onInstallCenter() },
                    onRemove = { confirm = Confirm.Remove(id) },
                    onOpenTunnel = { vm.tunnelIranIdFor(id)?.let { tid -> vm.close(); vm.tunnel.openDetail(tid) } }
                )
            )
        }
    }
    ConfirmHost(vm, confirm) { confirm = null }
    return true
}

@Composable
private fun TunnelOverlay(vm: ServersViewModel, st: TunnelState, copy: (String) -> Unit, ask: (Confirm) -> Unit) {
    val t = vm.tunnel
    when (val route = st.route) {
        TunnelRoute.None -> Unit
        TunnelRoute.Setup -> {
            val ui = st.setup ?: return
            BackHandler { t.back() }
            TunnelSetupScreen(ui, TunnelActions(
                onBack = t::back, onPickIran = t::pickIran, onPickAbroad = t::pickAbroad, onNext = t::next,
                onChange = t::change, onInstall = t::install, onRetry = t::retry, onDone = t::close,
                onOpenTunnel = t::openFromSetup, onTest = t::testSetup, onCopy = copy
            ))
        }
        is TunnelRoute.Detail -> {
            val id = route.iranId
            val ui = t.detailUi(id) ?: return
            BackHandler { t.close() }
            TunnelDetailScreen(ui, TunnelActions(
                onBack = t::close, onCheck = { t.check(id) }, onTest = { t.test(id) }, onCopy = copy,
                onReseed = { ask(Confirm.Reseed(id)) }, onRestart = { t.restart(id) }, onLogs = { t.logs(id) },
                onEdit = { t.openSetup(id) }, onRemove = { ask(Confirm.RemoveTunnel(id)) }
            ))
        }
    }
}

@Composable
private fun ConfirmHost(vm: ServersViewModel, confirm: Confirm?, dismiss: () -> Unit) {
    when (val c = confirm) {
        null -> Unit
        is Confirm.Uninstall -> ConfirmDialog(
            "Remove ${toolTitle(c.toolId)}?",
            "It is stopped and deleted from the server, and its config is removed from this phone.",
            "Remove", { vm.uninstall(c.serverId, c.toolId) }, dismiss
        )
        is Confirm.Remove -> ConfirmDialog(
            "Forget this server?",
            "The app forgets its sign-in details. Nothing on the server changes; installed tools keep running.",
            "Forget", { vm.remove(c.serverId) }, dismiss
        )
        is Confirm.PasswordOff -> ConfirmDialog(
            "Turn password login off?",
            "Only this phone's key will be able to sign in. Keep your provider's web console as a backup way in.",
            "Turn off", { vm.setPasswordLogin(c.serverId, true) }, dismiss
        )
        is Confirm.RemoveTunnel -> ConfirmDialog(
            "Remove this tunnel?",
            "It is stopped and deleted on both servers, and its config is removed from this phone. The servers stay.",
            "Remove", { vm.tunnel.remove(c.iranId) }, dismiss
        )
        is Confirm.Reseed -> ConfirmDialog(
            "Move the ports now?",
            "Both servers switch to new ports at once. Connections through the tunnel drop for a few seconds.",
            "Move", { vm.tunnel.reseed(c.iranId) }, dismiss
        )
    }
}

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
