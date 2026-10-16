package com.example.vpn.coordinator

import com.example.vpn.connectivity.TransportCapabilityEngine.Transport

/**
 * Turns ShadowLink's network read into a concrete order to try the user's own saved configs. It ranks
 * each config by the transport order ShadowLink recommended for this network, and drops any whose engine
 * is cooling down after repeated failures (the engine circuit breaker). This is the coordination layer
 * between the discovery brain and the multi-engine registry; it is pure (no Android, no I/O) so it can be
 * tested on its own, and it never connects anything — it only produces the order.
 */
object EngineCoordinator {
    /** One saved config, tagged with the transport family it uses and the engine that would run it. */
    data class Candidate(val id: String, val label: String, val transport: Transport, val engineId: String)

    data class PlannedConfig(val id: String, val label: String, val transport: Transport, val engineId: String, val reason: String)

    data class Skipped(val id: String, val label: String, val reason: String)

    data class ConnectionPlan(val ordered: List<PlannedConfig>, val skipped: List<Skipped>)

    /**
     * Ranks [candidates] by [recommended] (ShadowLink's ordered transport preference for this network).
     * A config whose engine [allows] returns false for is skipped with a reason. Configs whose transport
     * was not recommended go last, keeping their given order. Ties within a transport keep their given order.
     */
    fun plan(
        recommended: List<Transport>,
        candidates: List<Candidate>,
        allows: (engineId: String) -> Boolean = { true }
    ): ConnectionPlan {
        val skipped = mutableListOf<Skipped>()
        val usable = candidates.filter { c ->
            if (allows(c.engineId)) true
            else { skipped += Skipped(c.id, c.label, "its engine is cooling down after repeated failures"); false }
        }
        val rankOf: (Transport) -> Int = { t -> recommended.indexOf(t).let { if (it < 0) recommended.size else it } }
        val ordered = usable
            .mapIndexed { index, c -> Triple(c, rankOf(c.transport), index) }
            .sortedWith(compareBy({ it.second }, { it.third }))
            .map { (c, rank, _) ->
                val reason =
                    if (rank < recommended.size) "matches the recommended ${label(c.transport)} path (#${rank + 1})"
                    else "${label(c.transport)} was not in the recommended order for this network"
                PlannedConfig(c.id, c.label, c.transport, c.engineId, reason)
            }
        return ConnectionPlan(ordered, skipped)
    }

    private fun label(t: Transport) = when (t) {
        Transport.UDP -> "UDP / QUIC"
        Transport.TCP_TLS -> "TCP + TLS"
        Transport.TCP_PLAIN -> "plain TCP"
    }
}
