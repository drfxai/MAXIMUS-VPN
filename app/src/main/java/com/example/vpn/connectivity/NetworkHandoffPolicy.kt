package com.example.vpn.connectivity

/** Only a change of the selected physical network invalidates a connection, not another VPN's callback. */
class NetworkHandoffPolicy {
    private var observed = false
    private var selected: String? = null

    @Synchronized fun observe(next: String?): Boolean {
        val changed = observed && selected != next
        selected = next
        observed = true
        return changed
    }
}
