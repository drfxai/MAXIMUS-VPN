package com.example.vpn.automation

import android.content.Context
import com.example.vpn.dnsfirst.DnsLadderMemory

/**
 * The upkeep's own persistent state, one instance per process, kept apart from the application object so the
 * automation can start before it (androidx.startup runs ahead of Application.onCreate).
 */
object AutomationStores {
    private const val PREFS = "dns_first_automation"
    private const val KEY_AUTO_DROP = "auto_drop_dead_subscription_configs"

    @Volatile private var status: AutomationStatusBook? = null
    @Volatile private var ladder: DnsLadderMemory? = null
    @Volatile private var health: ConfigHealthBook? = null

    fun status(context: Context): AutomationStatusBook = status ?: synchronized(this) {
        status ?: prefs(context).let { p ->
            AutomationStatusBook(load = { p.getString("status", null) }, save = { p.edit().putString("status", it).apply() })
        }.also { status = it }
    }

    fun ladder(context: Context): DnsLadderMemory = ladder ?: synchronized(this) {
        ladder ?: prefs(context).let { p ->
            DnsLadderMemory(load = { p.getString("ladder", null) }, save = { p.edit().putString("ladder", it).apply() })
        }.also { ladder = it }
    }

    fun health(context: Context): ConfigHealthBook = health ?: synchronized(this) {
        health ?: prefs(context).let { p ->
            ConfigHealthBook(load = { p.getString("health", null) }, save = { p.edit().putString("health", it).apply() })
        }.also { health = it }
    }

    /** Off by default: removing a saved config is the user's call. */
    fun autoDrop(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_DROP, false)

    fun setAutoDrop(context: Context, value: Boolean) { prefs(context).edit().putBoolean(KEY_AUTO_DROP, value).apply() }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
