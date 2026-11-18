package com.example.vpn.sidecar

import com.example.data.model.VlessProfile

/**
 * Stored in local SOCKS order: the first hop accepts application traffic, and the last hop
 * connects directly to its server. The on-wire route from the phone is read in reverse.
 */
object EngineChain {
    /** One hop: the engine and its few knobs (Psiphon's egress region, Tor's bridges). */
    data class Hop(val engineId: String, val region: String = "", val bridges: String = "", val profileId: String = "")

    data class Chain(val hops: List<Hop>)

    /** Engine ids that can be chained, in a stable order for the picker. */
    val CHAINABLE: List<String> = listOf("psiphon", "tor", "masque", "matrix-transport", "aether", ServerlessSidecar.ID, RadianceSidecar.id, SingBoxSidecar.id)

    private val carrierOnly = setOf("aether", "masque", "matrix-transport", ServerlessSidecar.ID, RadianceSidecar.id)

    const val MIN_HOPS = 2
    const val MAX_HOPS = 3

    /** Why [chain] cannot run, or null when it is valid. */
    fun problem(chain: Chain): String? {
        val hops = chain.hops
        if (hops.size < MIN_HOPS) return "A chain needs at least $MIN_HOPS engines."
        if (hops.size > MAX_HOPS) return "A chain can hold at most $MAX_HOPS engines."
        val relays = hops.filterNot { it.engineId == SingBoxSidecar.id }
        if (relays.distinctBy { it.engineId }.size != relays.size) return "Each relay can appear once in a chain."
        val saved = hops.filter { it.engineId == SingBoxSidecar.id }
        if (saved.any { it.profileId.isBlank() }) return "Choose a saved node for each saved-node hop."
        if (saved.distinctBy { it.profileId }.size != saved.size) return "Each saved node can appear once in a chain."
        if (hops.any { it.engineId != SingBoxSidecar.id && it.profileId.isNotEmpty() }) return "Unexpected saved node reference."
        val carrier = hops.firstOrNull { it.engineId in setOf("masque", "matrix-transport") }
        if (carrier != null && (hops.last() != carrier || hops[hops.lastIndex - 1].engineId !in setOf("psiphon", SingBoxSidecar.id))) {
            return "${titleOf(carrier.engineId)} must be the first hop, followed by Psiphon or a compatible saved config."
        }
        hops.dropLast(1).firstOrNull { it.engineId in carrierOnly }?.let {
            return "${titleOf(it.engineId)} must be last in the engine order. Move it to the first hop on the route."
        }
        for (hop in hops) {
            if (hop.engineId !in CHAINABLE) return "${hop.engineId} cannot be part of a chain."
        }
        return null
    }

    fun canUseSavedNode(profile: VlessProfile): Boolean =
        !ChainRunner.isChain(profile) && SingBoxSidecar.autoSelectProblem(profile) == null

    /** The saved profile that stands for one hop's engine (Psiphon with its region, Tor with its bridges). */
    fun hopProfile(hop: Hop): VlessProfile = when (hop.engineId) {
        "psiphon" -> PsiphonSidecar.profile(hop.region)
        "tor" -> TorSidecar.profile(hop.bridges)
        "masque" -> MasqueSidecar.profile()
        "matrix-transport" -> MatrixTransportSidecar.profile("h2")
        "aether" -> AetherSidecar.profile(transport = "h2")
        ServerlessSidecar.ID -> ServerlessSidecar.profile()
        RadianceSidecar.id -> RadianceSidecar.profile()
        SingBoxSidecar.id -> {
            val node = BuiltinExitNode.loadProfile?.invoke(hop.profileId)
                ?: error("The saved node is no longer available.")
            require(canUseSavedNode(node)) { "The saved node no longer supports this chain." }
            SingBoxSidecar.select(node)
        }
        else -> throw IllegalArgumentException("${hop.engineId} cannot be part of a chain.")
    }

    /**
     * One hop's place in the run. [upstreamHop] is the index of the hop this one dials through (the
     * next hop toward the exit), or null for the exit hop, which dials the internet directly.
     */
    data class Step(val index: Int, val hop: Hop, val upstreamHop: Int?)

    /**
     * The order the hops are started in and which hop each one dials. The exit hop is started first,
     * so its SOCKS port is already listening when the hop before it is told to dial through it; the
     * entry hop is started last. The returned steps are in start order (exit first).
     *
     * @throws IllegalArgumentException when [chain] is not valid.
     */
    fun plan(chain: Chain): List<Step> {
        require(problem(chain) == null) { problem(chain) ?: "invalid chain" }
        val hops = chain.hops
        return hops.indices.reversed().map { i ->
            Step(index = i, hop = hops[i], upstreamHop = if (i == hops.lastIndex) null else i + 1)
        }
    }

    fun titleOf(id: String): String = when (id) {
        "psiphon" -> "Psiphon"
        "tor" -> "Tor"
        "masque" -> "WARP MASQUE"
        "matrix-transport" -> "Matrix Transport"
        "aether" -> "Aether HTTP/2"
        ServerlessSidecar.ID -> "Direct Serverless"
        RadianceSidecar.id -> "Lantern / Radiance"
        SingBoxSidecar.id -> "Saved config"
        else -> id
    }

    /** A short human description of the path, e.g. "Psiphon → Tor". */
    fun describe(chain: Chain, titleOf: (String) -> String): String =
        chain.hops.joinToString("  →  ") { titleOf(it.engineId) }
}
