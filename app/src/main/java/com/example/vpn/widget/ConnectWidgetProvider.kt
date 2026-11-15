package com.example.vpn.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import com.example.vpn.RayVpnService
import com.example.vpn.VpnController
import com.example.vpn.boot.QuickConnect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/*
 * Home-screen connect widget. The idea (one-tap connect widget refreshed from the service state) follows
 * ZedSecure's ZedWidgetProvider (CluvexStudio/ZedSecure 201e82ce18a4) and WhiteAestherMobile's
 * AetherWidgetProvider (WhiteDNS/WhiteAestherMobile 6e2ade9e86c5), both AGPL-3.0; the code is Maximus's own.
 */
class ConnectWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        render(context, manager, ids, RayVpnService.vpnState.value)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return
        val app = context.applicationContext
        val pending = goAsync()
        scope.launch {
            try {
                val outcome = runCatching { QuickConnect.toggle(app) }.getOrNull()
                if (outcome == QuickConnect.Outcome.NEEDS_PERMISSION || outcome == QuickConnect.Outcome.NO_PROFILE) {
                    runCatching { app.startActivity(openApp(app)) }
                }
                refresh(app)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.widget.TOGGLE"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        @Volatile private var observing = false

        /** Keeps placed widgets in step with the connection. Called once from the Application. */
        fun observe(context: Context) {
            if (observing) return
            observing = true
            val app = context.applicationContext
            scope.launch {
                RayVpnService.vpnState
                    .map { WidgetLabels.of(it) }
                    .distinctUntilChanged()
                    .collect { refresh(app) }
            }
        }

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = runCatching {
                manager.getAppWidgetIds(ComponentName(context, ConnectWidgetProvider::class.java))
            }.getOrNull() ?: return
            if (ids.isEmpty()) return
            render(context, manager, ids, RayVpnService.vpnState.value)
        }

        private fun render(context: Context, manager: AppWidgetManager, ids: IntArray, state: ConnectionState) {
            val labels = WidgetLabels.of(state)
            // Without VPN permission the tap must open the app: only an activity can ask for it.
            val click = if (VpnController.isPermissionGranted(context)) {
                PendingIntent.getBroadcast(
                    context, 0,
                    Intent(context, ConnectWidgetProvider::class.java).setAction(ACTION_TOGGLE),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            } else {
                PendingIntent.getActivity(context, 1, openApp(context), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            ids.forEach { id ->
                val views = RemoteViews(context.packageName, R.layout.widget_connect).apply {
                    setTextViewText(R.id.widget_status, labels.status)
                    setTextViewText(R.id.widget_detail, labels.detail)
                    setTextViewText(R.id.widget_action, labels.action)
                    setOnClickPendingIntent(R.id.widget_root, click)
                }
                runCatching { manager.updateAppWidget(id, views) }
            }
        }

        private fun openApp(context: Context): Intent =
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    }
}

/** What the widget says for a connection state. Pure, so it is tested on the JVM. */
data class WidgetLabels(val status: String, val detail: String, val action: String) {
    companion object {
        fun of(state: ConnectionState): WidgetLabels {
            val name = state.activeProfile?.name?.takeIf { it.isNotBlank() } ?: "Maximus VPN"
            return when (state.status) {
                ConnectionStatus.CONNECTED -> WidgetLabels("Connected", name, "Disconnect")
                ConnectionStatus.DEGRADED -> WidgetLabels("Connected · checks failing", name, "Disconnect")
                ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED -> WidgetLabels("No traffic yet", name, "Disconnect")
                else -> when {
                    state.isBusy -> WidgetLabels("Connecting…", name, if (state.isTunnelUp) "Stop" else "Wait")
                    state.isTunnelUp -> WidgetLabels("Connected", name, "Disconnect")
                    else -> WidgetLabels("Disconnected", "Tap to connect", "Connect")
                }
            }
        }
    }
}
