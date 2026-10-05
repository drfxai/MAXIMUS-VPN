package com.example.vpn.sidecar

import android.content.Context
import android.content.Intent
import org.torproject.jni.TorService

/** Runs Tor from the tor-android library inside the app's own process, with the given torrc. */
object TorInApp {
    fun start(context: Context, torrc: String, socksPort: Int): RunningEngine {
        TorService.getTorrc(context).writeText(torrc)
        context.startService(Intent(context, TorService::class.java).setAction(TorService.ACTION_START))
        return object : RunningEngine {
            @Volatile private var stopped = false

            override val isAlive: Boolean get() = !stopped && SidecarProcess.portOpen(socksPort)

            override fun awaitReady(timeoutMs: Long): Boolean {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (!stopped && System.currentTimeMillis() < deadline) {
                    if (SidecarProcess.portOpen(socksPort) && TorSidecar.carriesTraffic(socksPort)) return true
                    Thread.sleep(1_000)
                }
                return false
            }

            override fun stop() {
                stopped = true
                context.stopService(Intent(context, TorService::class.java))
            }
        }
    }
}
