package com.example.ui.panels.servers

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
internal fun ServersTabHost(vm: ServersViewModel, state: ServersState, onInstallCenter: () -> Unit) {
    LaunchedEffect(state.servers.size) { vm.refreshReach() }
    MyServersTab(vm.rows(), onAdd = { vm.openAdd() }, onOpen = vm::openDetail, onInstallCenter = onInstallCenter)
}

/** The Install tab: the tool catalog plus suggestions for the user's servers. */
@Composable
internal fun InstallTabHost(vm: ServersViewModel, @Suppress("UNUSED_PARAMETER") state: ServersState) {
    InstallCenterTab(
        tools = ServerToolCatalog.all,
        installedOn = vm.installedCounts(),
        advice = vm.advice(),
        onOpen = { vm.openWizard(it) },
        onAdvice = { vm.openWizard(it.toolId, it.serverId) }
    )
}

private sealed interface Confirm {
    data class Uninstall(val serverId: String, val toolId: String) : Confirm
    data class Remove(val serverId: String) : Confirm
    data class PasswordOff(val serverId: String) : Confirm
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
                    onRemove = { confirm = Confirm.Remove(id) }
                )
            )
        }
    }

    when (val c = confirm) {
        null -> Unit
        is Confirm.Uninstall -> ConfirmDialog(
            "Remove ${toolTitle(c.toolId)}?",
            "It is stopped and deleted from the server, and its config is removed from this phone.",
            "Remove", { vm.uninstall(c.serverId, c.toolId) }, { confirm = null }
        )
        is Confirm.Remove -> ConfirmDialog(
            "Forget this server?",
            "The app forgets its sign-in details. Nothing on the server changes; installed tools keep running.",
            "Forget", { vm.remove(c.serverId) }, { confirm = null }
        )
        is Confirm.PasswordOff -> ConfirmDialog(
            "Turn password login off?",
            "Only this phone's key will be able to sign in. Keep your provider's web console as a backup way in.",
            "Turn off", { vm.setPasswordLogin(c.serverId, true) }, { confirm = null }
        )
    }
    return true
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
