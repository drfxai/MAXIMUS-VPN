package com.example.vpn.lab

/**
 * The text the LAB Agent reads (spec section 48): the LAB's own measurements only, as counts and categories.
 * No address, edge IP, server or config name, UUID, key, URL or carrier code is included.
 */
object LabBrief {
    fun of(s: LabSnapshot, now: Long = System.currentTimeMillis()): String = buildString {
        val n = s.network
        appendLine("Network kind: ${n?.let { kindOf(it.networkKey) } ?: "none"}; address families: ${n?.families ?: "unknown"}.")
        s.capability?.observations()?.let { appendLine("Measured: " + it.joinToString("; ")) }
        s.networkState?.let { appendLine("Network state (rule-based, not proof): " + it.summary()) }
        s.analysis?.let { r ->
            appendLine("Full analysis ${hoursAgo(r.finishedAt, now)}h ago: ${r.state}, plan ${r.mode}.")
            r.paths.filter { it.status != PathStatus.NOT_TESTED }.forEach { appendLine("  - path ${it.title}: ${it.status.name}" + (it.latencyMs?.let { l -> ", $l ms" } ?: "")) }
            appendLine("  Not tested: " + r.untested.size + " checks.")
        }
        if (s.transactions.isNotEmpty()) appendLine("Config changes: " + s.transactions.groupingBy { it.state.name }.eachCount().entries.joinToString { "${it.value} ${it.key.lowercase()}" } + ".")
        val here = s.experiments.filter { it.contextKey == n?.contextKey }.sortedByDescending { it.startTime }.take(6)
        if (here.isEmpty()) appendLine("No experiments on this network yet.")
        here.forEach { e ->
            appendLine("Experiment ${hoursAgo(e.startTime, now)}h ago: baseline ${e.baselineFailure?.name ?: "recheck"}, result ${e.state.name}.")
            e.candidates.filter { it.stats.attempts > 0 || !it.securityPassed }.forEach { c ->
                appendLine("  - ${strategy(c.mutationProfileId)}: ${if (!c.securityPassed) "refused by security gate" else "${c.stats.successes}/${c.stats.attempts} passed" +
                    (c.stats.medianLatencyMs?.let { ", median $it ms" } ?: "") + (c.stats.failures.keys.firstOrNull()?.let { ", failures: ${it.name}" } ?: "")}")
            }
        }
        s.verified.filter { it.contextKey == n?.contextKey }.forEach { v ->
            appendLine("Profile ${v.strategy}: ${v.state.name}, ${(v.stats.successRate * 100).toInt()}% over ${v.stats.attempts} requests, last ${hoursAgo(v.lastVerifiedAt, now)}h ago.")
        }
    }.trim()

    private fun kindOf(key: String) = when {
        // Never the fingerprint: the AI sees the kind of link only.
        key.startsWith("wifi") -> "Wi-Fi"
        key.startsWith("ethernet") -> "Ethernet"
        key.startsWith("cell") -> "mobile data"
        else -> key.substringBefore(':')
    }

    private fun hoursAgo(t: Long, now: Long) = ((now - t) / 3_600_000L).coerceAtLeast(0)

    /** A mutation id without endpoints: the reviewed profile key names the strategy only. */
    private fun strategy(mutation: String) = mutation.substringBefore('@')
}
