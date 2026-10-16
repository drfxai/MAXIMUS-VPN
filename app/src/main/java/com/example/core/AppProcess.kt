package com.example.core

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

/**
 * The app runs Tor in its own process (":tor", see the manifest). That process must not start the
 * app's databases, sync jobs or diagnostics, and the main process must be able to end it so Tor's
 * next start gets a fresh process.
 */
object AppProcess {
    const val TOR_SUFFIX = ":tor"

    /** The current process's name, e.g. "com.drfxai.maximusvpn" or "com.drfxai.maximusvpn:tor". */
    fun currentName(): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName()
        else runCatching {
            File("/proc/self/cmdline").readText().substringBefore('\u0000').trim().ifBlank { null }
        }.getOrNull()

    fun isTorProcess(): Boolean = isTorProcessName(currentName())

    /** Pure, for tests. */
    fun isTorProcessName(name: String?): Boolean = name != null && name.endsWith(TOR_SUFFIX)

    /** Process id of this app's Tor process, or null when it is not running. */
    fun torPid(context: Context): Int? {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        val name = context.packageName + TOR_SUFFIX
        return runCatching { am.runningAppProcesses.orEmpty().firstOrNull { it.processName == name }?.pid }.getOrNull()
    }

    /**
     * Ends this app's Tor process, if any, and waits up to [waitMs] for it to be gone. Android lets an
     * app end its own processes; Tor holds nothing that needs a clean shutdown.
     */
    fun killTor(context: Context, waitMs: Long = 1_500): Boolean {
        val pid = torPid(context) ?: return true
        runCatching { Process.killProcess(pid) }
        val deadline = System.currentTimeMillis() + waitMs
        while (System.currentTimeMillis() < deadline) {
            if (torPid(context) == null) return true
            Thread.sleep(50)
        }
        return torPid(context) == null
    }
}
