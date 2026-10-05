package com.example.vpn.hub

import com.example.data.model.VlessProfile
import com.example.xray.RealDelayProbe

/**
 * Syncs the hub: fetch each provider (through [fetch], which applies HTTPS-only, timeouts, size limits
 * and mirrors), run the [ConfigValidationPipeline], then send a real request through every accepted
 * node. Only nodes that carried traffic are approved; the rest stay listed with their states.
 */
class ConfigSyncManager(
    private val registry: ProviderRegistry,
    private val fetch: (FreeConfigProvider) -> String,
    private val probe: (List<VlessProfile>, Int) -> List<RealDelayProbe.Outcome> = { p, t -> RealDelayProbe.measure(p, t) },
    private val log: (String) -> Unit = {}
) {
    data class Sync(val nodes: List<HubNode>, val failedProviders: Map<String, String>) {
        val approved: List<HubNode> get() = nodes.filter { it.approved }
    }

    fun sync(timeoutSec: Int = 5): Sync {
        val nodes = mutableListOf<HubNode>()
        val failed = mutableMapOf<String, String>()
        val known = mutableSetOf<String>()
        for (provider in registry.providers) {
            val text = runCatching { fetch(provider) }.getOrElse {
                failed[provider.id] = it.message ?: it.javaClass.simpleName
                log("${provider.name}: not fetched (${failed[provider.id]}).")
                null
            } ?: continue
            val result = runCatching { ConfigValidationPipeline.process(text, provider, known) }.getOrElse {
                failed[provider.id] = it.message ?: it.javaClass.simpleName
                null
            } ?: continue
            (result.accepted + result.quarantined).forEach { known += it.profile.effectiveFingerprint }
            nodes += result.quarantined
            nodes += test(result.accepted, timeoutSec)
            log("${provider.name}: ${result.accepted.size} accepted, ${result.quarantined.size} quarantined, ${result.rejectedEntries} unreadable.")
        }
        return Sync(nodes, failed)
    }

    private fun test(candidates: List<HubNode>, timeoutSec: Int): List<HubNode> {
        if (candidates.isEmpty()) return emptyList()
        val outcomes = probe(candidates.map { it.profile }, timeoutSec)
        return candidates.zip(outcomes).map { (node, outcome) ->
            when (outcome) {
                is RealDelayProbe.Outcome.Delay -> node.copy(health = HealthState.HEALTHY, security = SecurityState.VERIFIED,
                    performance = ConfigHealthScorer.performance(outcome.latencyMs))
                is RealDelayProbe.Outcome.Failed -> node.copy(health = HealthState.OFFLINE)
                is RealDelayProbe.Outcome.NotRun -> node
            }
        }
    }
}
