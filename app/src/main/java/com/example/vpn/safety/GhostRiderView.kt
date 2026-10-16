package com.example.vpn.safety

import com.example.data.model.ConnectionState

/**
 * The Ghost Rider dashboard reads from real connection state, so it never claims a Tor circuit that
 * is not actually carrying traffic. This pure builder turns the live [ConnectionState] (and, when the
 * active profile is an engine chain, the chain's engine ids in path order) into the model the dashboard
 * draws. The protections are constant for the mode: the GHOST_RIDER preset enforces them the moment the
 * mode is active, whatever the path.
 */
object GhostRiderView {
    enum class Role { DEVICE, ENTRY, MIDDLE, EXIT }

    data class Hop(val label: String, val detail: String, val role: Role)

    data class Model(
        val connected: Boolean,
        val onTor: Boolean,
        val heroTitle: String,
        val exitLine: String,
        val hops: List<Hop>,
        // Enforced by the preset whenever Ghost Rider is the mode (see OperatingModePolicy.GHOST_RIDER).
        val killSwitch: Boolean = true,
        val failsClosed: Boolean = true,
        val leakBlocked: Boolean = true
    )

    private fun titleOf(engineId: String): String = when (engineId) {
        GhostRiderPath.PSIPHON -> "Psiphon"
        GhostRiderPath.TOR -> "Tor"
        else -> engineId.replaceFirstChar { it.uppercase() }
    }

    private fun roleAt(index: Int, size: Int): Role = when {
        index == size - 1 -> Role.EXIT
        index == 0 -> Role.ENTRY
        else -> Role.MIDDLE
    }

    private val DEVICE = Hop("This phone", "all apps · v4 + v6 captured", Role.DEVICE)

    /**
     * @param chainEngineIds the active chain's engine ids, entry first and exit last, or null when the
     *   active profile is not a chain.
     */
    fun of(state: ConnectionState, chainEngineIds: List<String>?): Model {
        val up = state.isTunnelUp
        val relays = chainEngineIds?.takeIf { it.isNotEmpty() }
        val torEngine = state.activeEngineName.equals("Tor", ignoreCase = true)
        val onTor = up && ((relays?.any { it == GhostRiderPath.TOR } == true) || torEngine)

        if (!up) {
            return Model(
                connected = false,
                onTor = false,
                heroTitle = "Not connected",
                exitLine = "Hardened and ready — route through Tor for a private exit.",
                hops = listOf(DEVICE)
            )
        }

        val hops = mutableListOf(DEVICE)
        val exitLine: String
        val heroTitle: String
        when {
            relays != null -> {
                relays.forEachIndexed { i, id ->
                    val role = roleAt(i, relays.size)
                    val detail = when (role) {
                        Role.ENTRY -> "entry relay"
                        Role.EXIT -> if (id == GhostRiderPath.TOR) "exit relay · onion circuit" else "exit relay"
                        else -> "relay"
                    }
                    hops += Hop(titleOf(id), detail, role)
                }
                heroTitle = if (onTor) "Protected · Tor exit" else "Protected · multi-hop"
                exitLine = "Identity hidden · " + relays.joinToString(" → ") { titleOf(it) }
            }
            torEngine -> {
                hops += Hop("Tor", "exit relay · onion circuit", Role.EXIT)
                heroTitle = "Protected · Tor exit"
                exitLine = "Identity hidden · Tor exit"
            }
            else -> {
                val name = state.activeProfile?.name?.takeIf { it.isNotBlank() } ?: "Secure server"
                val where = state.exitCountryCode?.takeIf { it.isNotBlank() }?.let { "$it · " } ?: ""
                hops += Hop(name, "exit server", Role.EXIT)
                heroTitle = "Protected"
                exitLine = "Exit " + where + (state.vpnIp ?: state.activeProfile?.address ?: "")
            }
        }
        return Model(connected = state.isConnected, onTor = onTor, heroTitle = heroTitle, exitLine = exitLine.trim(), hops = hops)
    }
}
