package com.example.vpn.connections

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager

/**
 * Android side of [AetherPrewarm]: screen-on and default-network changes, while the app process lives.
 * Everything runs on one background thread; nothing here keeps the process or the radio awake.
 */
object AetherPrewarmMonitor {
    @Volatile private var installed = false
    private lateinit var handler: Handler

    fun install(context: Context) {
        if (installed) return
        installed = true
        val app = context.applicationContext
        handler = Handler(HandlerThread("aether-prewarm").apply { isDaemon = true; start() }.looper)
        runCatching {
            androidx.core.content.ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: Intent?) { schedule(app, "screen-on", 1_500L) }
            }, IntentFilter(Intent.ACTION_SCREEN_ON), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        runCatching {
            app.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                // The (callback) form exists since API 24; the (callback, handler) one needs 26.
                override fun onAvailable(network: Network) { schedule(app, "network-change", AetherPrewarm.NETWORK_SETTLE_MS) }
            })
        }
    }

    private fun schedule(app: Context, reason: String, delayMs: Long) {
        handler.removeCallbacksAndMessages(TOKEN)
        handler.postAtTime({ runOnce(app, reason) }, TOKEN, android.os.SystemClock.uptimeMillis() + delayMs)
    }

    private fun runOnce(app: Context, reason: String) = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val state = com.example.vpn.VpnController.connectionState.value
        // The VPN's own network (or a connect in progress) means the tunnel decides, not a prewarm.
        val vpnActive = state.isTunnelUp || state.isBusy || caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        val powerSave = app.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true
        val metered = cm?.isActiveNetworkMetered ?: true
        val network = runCatching { com.example.vpn.smart.NetworkIdentity.current(app) }.getOrNull()
        AetherPrewarm.run(network, reason, vpnActive = vpnActive, powerSave = powerSave, metered = metered)
    }.onFailure {
        // Only the exception type: never a message that could carry an address.
        com.example.xray.XrayLogManager.w("BUILTIN", "aether/prewarm · $reason · could not run: ${it.javaClass.simpleName}")
    }

    private val TOKEN = Any()
}
