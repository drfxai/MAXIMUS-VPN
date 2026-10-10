package com.example.vpn.sidecar

import com.example.data.model.VlessProfile

/**
 * A chain of bundled relay engines, so traffic passes through more than one before it reaches the
 * internet (spec item 8). The hops are listed entry first (closest to the phone) and exit last: the
 * phone's traffic enters [Chain.hops]`[0]`, which dials the next hop, and so on, and the last hop
 * reaches the site.
 *
 * Only engines that both accept a local SOCKS5 inbound (Xray proxies to it) and can dial out through
 * an upstream SOCKS5 proxy can be chained. Here that is Psiphon (`UpstreamProxyURL`) and Tor
 * (`Socks5Proxy`), so either may sit at either position: Psiphon then Tor, or Tor then Psiphon.
 * Cloudflare WARP is an Xray WireGuard outbound rather than a SOCKS sidecar, so it is not a hop here.
 *
 * Nothing about security changes: each hop still offers its SOCKS port on 127.0.0.1 with a random
 * per-connection login, Xray still owns the TUN, the DNS and the kill switch, and every hop's own
 * server authentication is unchanged. Chaining only sets each hop's existing upstream-proxy option to
 * the next hop's loopback port.
 */
object EngineChain {
    /** One hop: the engine and its few knobs (Psiphon's egress region, Tor's bridges). */
    data class Hop(val engineId: String, val region: String = "", val bridges: String = "")

    data class Chain(val hops: List<Hop>)

    /** Engine ids that can be chained, in a stable order for the picker. */
    val CHAINABLE: List<String> = listOf("psiphon", "tor")

    const val MIN_HOPS = 2
    const val MAX_HOPS = 2

    /** Why [chain] cannot run, or null when it is valid. */
    fun problem(chain: Chain): String? {
        val hops = chain.hops
        if (hops.size < MIN_HOPS) return "A chain needs at least $MIN_HOPS engines."
        if (hops.size > MAX_HOPS) return "A chain can hold at most $MAX_HOPS engines."
        if (hops.map { it.engineId }.distinct().size != hops.size) return "Each engine can appear once in a chain."
        for (hop in hops) {
            if (hop.engineId !in CHAINABLE) return "${hop.engineId} cannot be part of a chain."
        }
        return null
    }

    /** The saved profile that stands for one hop's engine (Psiphon with its region, Tor with its bridges). */
    fun hopProfile(hop: Hop): VlessProfile = when (hop.engineId) {
        "psiphon" -> PsiphonSidecar.profile(hop.region)
        "tor" -> TorSidecar.profile(hop.bridges)
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

    /** A short human description of the path, e.g. "Psiphon → Tor". */
    fun describe(chain: Chain, titleOf: (String) -> String): String =
        chain.hops.joinToString("  →  ") { titleOf(it.engineId) }
}
