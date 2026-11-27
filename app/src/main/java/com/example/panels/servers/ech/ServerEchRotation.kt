package com.example.panels.servers.ech

import android.content.Context
import com.example.panels.servers.ActionProgress
import com.example.panels.servers.ServerStore
import com.example.panels.servers.ServerToolCatalog
import com.example.xray.XrayLogManager

/**
 * Build 181 (Phase 6): optional periodic ECH key rotation (Panels → My Servers → ECH → "Rotate automatically").
 * Checked at app start, at most once a day; a server whose record is older than its interval gets a new key with the
 * same outer name (the previous key stays served beside it, so other devices keep working until they refresh).
 * Failures are logged and retried the next day; nothing is changed on the server when a rotation fails (rollback).
 */
object ServerEchRotation {
    private const val PREFS = "server_ech"
    private const val LAST_CHECK = "lastRotationCheck"
    const val CHECK_EVERY_MS = 24 * 60 * 60_000L

    /** Whether the daily check may run. Pure. */
    fun mayCheck(lastCheck: Long, now: Long): Boolean = lastCheck <= 0 || now - lastCheck !in 0 until CHECK_EVERY_MS

    fun runDue(context: Context, now: Long = System.currentTimeMillis()) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!mayCheck(prefs.getLong(LAST_CHECK, 0L), now)) return
        prefs.edit().putLong(LAST_CHECK, now).apply()
        val store = ServerStore(context)
        val due = store.load().filter { ServerEch.rotationDue(ServerEch.record(it.tool(ServerToolCatalog.XUI)?.settings.orEmpty()), now) }
        if (due.isEmpty()) return
        val manager = ServerEchManager(context)
        val quiet = object : ActionProgress {
            override fun step(index: Int) = Unit
            override fun log(line: String) = XrayLogManager.i("SERVER-ECH", line)
        }
        for (server in due) {
            runCatching { manager.rotate(server, quiet) }
                .onSuccess { out ->
                    store.save(out.server)
                    XrayLogManager.i("SERVER-ECH", "Periodic ECH key rotation done for ${server.displayName}")
                }
                .onFailure {
                    XrayLogManager.w("SERVER-ECH", "Periodic ECH key rotation failed for ${server.displayName}: " +
                        com.example.panels.servers.ServerLogSanitizer.redact(it.message.orEmpty()).take(200))
                }
        }
        com.example.vpn.smartsni.PersonalEch.reload()
    }
}
