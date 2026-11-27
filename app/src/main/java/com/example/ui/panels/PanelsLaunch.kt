package com.example.ui.panels

/** A one-shot request to open Panels on a given tab (Build 181 Phase 7: the relay sheet opens the Cloudflare tab). */
object PanelsLaunch {
    const val TAB_CLOUDFLARE = 2
    @Volatile private var pending: Int? = null

    fun request(tab: Int) { pending = tab }

    fun consume(): Int? = pending.also { pending = null }
}
