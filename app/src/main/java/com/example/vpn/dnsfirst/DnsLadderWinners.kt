package com.example.vpn.dnsfirst

/**
 * Hands the DNS tunnels the ladder verified on a network to Smart Connect's per-network memory of verified paths
 * ([com.example.vpn.smart.VerifiedWinnerBook], `NetworkMemory.winners`). Automatic Aether on a Cloudflare-filtered
 * link and a pinned config that stopped carrying traffic both fall back to (or offer) the best path verified there;
 * a DNS tunnel the ladder just carried a real request through is such a path. When it is started, the remembered
 * resolver variant is applied by [DnsLadderHook] (DnsTunnelResolverOrder), so the fallback runs what was verified.
 *
 * Only wins are passed on: one failed variant says nothing about the tunnel's other variants. No rate is recorded
 * (the ladder measures latency, not throughput), so a path with a measured download rate still ranks first. Pure.
 */
object DnsLadderWinners {
    /** Records each tunnel that carried traffic in [report]; returns how many were recorded. */
    fun publish(
        report: DnsLadderRunner.Report,
        network: String,
        nameOf: (profileId: String) -> String?,
        record: (network: String, profileId: String, name: String) -> Unit
    ): Int {
        if (network.isBlank()) return 0
        var n = 0
        report.wins.map { it.rung.profileId }.distinct().forEach { id ->
            val name = nameOf(id) ?: return@forEach
            if (runCatching { record(network, id, name) }.isSuccess) n++
        }
        return n
    }
}
