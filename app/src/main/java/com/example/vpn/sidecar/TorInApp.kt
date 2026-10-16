package com.example.vpn.sidecar

import android.content.Context
import android.content.Intent
import com.example.core.AppProcess
import org.torproject.jni.TorService

/**
 * Runs Tor from the tor-android library with the given torrc. Tor can run only once per process, so
 * TorService lives in its own ":tor" process (see the manifest): every start ends any earlier Tor
 * process first, and every stop ends it, so each run gets a fresh process and a Tor failure never
 * closes the app.
 */
object TorInApp {
    private const val NOTICE_LOG = "tor-notices.log"

    /** Each start gets a number; a late stop of an older run never ends the Tor that runs now. */
    @Volatile private var generation = 0L

    fun start(context: Context, torrc: String, socksPort: Int): RunningEngine {
        val app = context.applicationContext
        val mine = synchronized(this) { ++generation }
        stopProcess(app)
        val notices = java.io.File(app.filesDir, NOTICE_LOG).apply { delete() }
        TorService.getTorrc(app).writeText(torrc.trimEnd() + "\nLog notice file ${notices.absolutePath}\n")
        val progress = TorProgress(notices)
        app.startService(Intent(app, TorService::class.java).setAction(TorService.ACTION_START))
        return object : RunningEngine {
            @Volatile private var stopped = false

            override val isAlive: Boolean get() = !stopped && SidecarProcess.portOpen(socksPort)

            override fun awaitReady(timeoutMs: Long): Boolean {
                val deadline = System.currentTimeMillis() + timeoutMs
                while (!stopped && System.currentTimeMillis() < deadline) {
                    var aborted = false
                    progress.newLines().forEach {
                        com.example.xray.XrayLogManager.i("TOR", it)
                        // Tor writes "...; aborting" just before it exits: stop waiting for it.
                        if (it.contains("aborting")) aborted = true
                    }
                    if (aborted) return false
                    if (SidecarProcess.portOpen(socksPort) && TorSidecar.carriesTraffic(socksPort)) return true
                    Thread.sleep(500)
                }
                return false
            }

            override fun stop() {
                if (stopped) return
                stopped = true
                if (generation != mine) return
                // No wait here, so Disconnect stays instant; the next start waits for the old process to go.
                stopProcess(app, waitMs = 0)
            }
        }
    }

    /** Deletes Tor's notice log (panic). */
    fun wipeNotices(context: Context) {
        java.io.File(context.applicationContext.filesDir, NOTICE_LOG).delete()
    }

    /**
     * Stops TorService first, so Android does not restart it, then ends its process.
     */
    private fun stopProcess(context: Context, waitMs: Long = 1_500) {
        runCatching { context.stopService(Intent(context, TorService::class.java)) }
        AppProcess.killTor(context, waitMs)
    }
}
