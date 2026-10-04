package com.example.vpn.lab

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.panels.InboundGenerationResult
import com.example.panels.ThreeXUiProtocol
import com.example.panels.ThreeXUiSecurity

/**
 * A kind of traffic a censor sees differently from the others. The protocol test tries one config
 * of each family, because when one family is blocked on a network the others often still work.
 */
enum class LabFamily(
    val displayName: String,
    val transportLabel: String,
    /** How the family is created on a 3X-UI server; null for families that only come from saved configs. */
    val serverProtocol: ThreeXUiProtocol?,
    val serverSecurity: ThreeXUiSecurity?
) {
    REALITY("REALITY", "TCP · looks like a real website", ThreeXUiProtocol.REALITY, ThreeXUiSecurity.REALITY),
    XHTTP("XHTTP + REALITY", "TCP · split HTTP", ThreeXUiProtocol.XHTTP, ThreeXUiSecurity.REALITY),
    HYSTERIA2("Hysteria2", "UDP · QUIC", ThreeXUiProtocol.HYSTERIA2, ThreeXUiSecurity.TLS),
    WIREGUARD("WireGuard", "UDP · VPN tunnel", ThreeXUiProtocol.WIREGUARD, ThreeXUiSecurity.NONE),
    HTTPUPGRADE("HTTPUpgrade", "TCP · CDN ready", ThreeXUiProtocol.HTTPUPGRADE, ThreeXUiSecurity.NONE),
    WEBSOCKET("WebSocket", "TCP · CDN ready", ThreeXUiProtocol.WEBSOCKET, ThreeXUiSecurity.NONE),
    CDN("CDN / Worker", "TLS through Cloudflare", null, null),
    OTHER("Other", "TCP", null, null);

    val canCreateOnServer: Boolean get() = serverProtocol != null

    companion object {
        /** Families a 3X-UI server can create, in the order they are tried. */
        val serverFamilies: List<LabFamily> = values().filter { it.canCreateOnServer }

        private val CDN_TRANSPORTS = setOf("ws", "grpc", "xhttp", "splithttp", "httpupgrade", "http", "h2")

        fun of(profile: VlessProfile): LabFamily {
            val security = profile.security.lowercase()
            val transport = profile.transport.lowercase()
            return when {
                profile.protocolType == ProtocolType.HYSTERIA2 -> HYSTERIA2
                profile.protocolType == ProtocolType.WIREGUARD -> WIREGUARD
                security == "reality" && transport == "xhttp" -> XHTTP
                security == "reality" -> REALITY
                security == "tls" && transport in CDN_TRANSPORTS -> CDN
                transport == "httpupgrade" -> HTTPUPGRADE
                transport == "ws" -> WEBSOCKET
                else -> OTHER
            }
        }
    }
}

/** What the user cares about most; it changes how latency, jitter and success are weighed. */
enum class LabPriority(val title: String) {
    BALANCED("Balanced"),
    FASTEST("Fastest"),
    MOST_STABLE("Most stable")
}

/** The outcome for one family. [latenciesMs] holds one entry per round; null means that round failed. */
data class LabResult(
    val family: LabFamily,
    val profile: VlessProfile?,
    val latenciesMs: List<Long?>,
    val score: Int,
    val note: String = "",
    /** Set when the config was created on the user's server for this test. */
    val generated: InboundGenerationResult? = null
) {
    val successes: Int get() = latenciesMs.count { it != null }
    val works: Boolean get() = successes > 0
    val medianMs: Long? get() = latenciesMs.filterNotNull().sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
    val jitterMs: Long? get() = latenciesMs.filterNotNull().let { if (it.size < 2) null else it.max() - it.min() }
}

/**
 * Tests the user's network with one config per protocol family and ranks the families that work.
 *
 * With a 3X-UI server, [provision] creates a short-lived inbound per family on it; without one the
 * user's saved configs are grouped by family and the best of each is tested. Every family is
 * measured with real requests through the proxy ([measure]), several rounds, so a family that
 * works only now and then ranks below one that always answers.
 */
class ProtocolLab(
    /** Real-request latency for each profile, null when the request failed. */
    private val measure: (List<VlessProfile>) -> List<Long?>,
    /** Creates a test config for [LabFamily] on the server. */
    private val provision: ((LabFamily) -> InboundGenerationResult)? = null,
    /** Removes a test config from the server. */
    private val discard: ((InboundGenerationResult) -> Unit)? = null
) {
    data class Progress(val step: String, val done: Int, val total: Int)

    /** Creates one config per family on the server, measures them all and returns them best first. */
    fun runOnServer(
        families: List<LabFamily> = LabFamily.serverFamilies,
        rounds: Int = DEFAULT_ROUNDS,
        priority: LabPriority = LabPriority.BALANCED,
        onProgress: (Progress) -> Unit = {}
    ): List<LabResult> {
        val create = requireNotNull(provision) { "No server to create test configs on" }
        val wanted = families.filter { it.canCreateOnServer }
        val total = wanted.size + rounds
        val created = mutableListOf<Pair<LabFamily, InboundGenerationResult>>()
        val failed = mutableListOf<LabResult>()
        wanted.forEachIndexed { i, family ->
            onProgress(Progress("Creating ${family.displayName} on your server", i, total))
            runCatching { create(family) }
                .onSuccess { created += family to it }
                .onFailure { failed += LabResult(family, null, emptyList(), 0, note = "Could not create it on the server: ${it.message}") }
        }
        val measured = measureRounds(created.map { it.second.profile }, rounds, priority) { round ->
            onProgress(Progress("Testing your internet, round ${round + 1} of $rounds", wanted.size + round, total))
        }
        onProgress(Progress("Ranking", total, total))
        return rank(created.mapIndexed { i, (family, gen) -> measured[i].copy(family = family, generated = gen) } + failed)
    }

    /** Measures the user's saved configs, the best [perFamily] of each family, and returns the families best first. */
    fun runOnSaved(
        profiles: List<VlessProfile>,
        rounds: Int = DEFAULT_ROUNDS,
        priority: LabPriority = LabPriority.BALANCED,
        perFamily: Int = 3,
        onProgress: (Progress) -> Unit = {}
    ): List<LabResult> {
        val candidates = profiles.groupBy { LabFamily.of(it) }.mapValues { it.value.take(perFamily) }
        val flat = candidates.values.flatten()
        val measured = measureRounds(flat, rounds, priority) { round ->
            onProgress(Progress("Testing your internet, round ${round + 1} of $rounds", round, rounds))
        }
        onProgress(Progress("Ranking", rounds, rounds))
        // Each family is represented by its best config.
        val best = flat.indices.groupBy { LabFamily.of(flat[it]) }
            .map { (family, indices) -> measured[indices.maxBy { measured[it].score }].copy(family = family) }
        return rank(best)
    }

    /** Keeps [chosen] on the server and removes every other test config this run created. */
    fun keepOnly(chosen: LabResult?, results: List<LabResult>) {
        val remove = discard ?: return
        results.filter { it !== chosen }.mapNotNull { it.generated }.forEach { runCatching { remove(it) } }
    }

    private fun measureRounds(
        profiles: List<VlessProfile>,
        rounds: Int,
        priority: LabPriority,
        onRound: (Int) -> Unit
    ): List<LabResult> {
        val samples = List(profiles.size) { mutableListOf<Long?>() }
        if (profiles.isNotEmpty()) {
            repeat(rounds) { round ->
                onRound(round)
                val delays = measure(profiles)
                profiles.indices.forEach { samples[it] += delays.getOrNull(it) }
            }
        }
        return profiles.mapIndexed { i, profile ->
            val result = LabResult(LabFamily.of(profile), profile, samples[i], 0)
            result.copy(
                score = score(result, priority),
                note = if (result.works) "" else "No answer on this network"
            )
        }
    }

    companion object {
        const val DEFAULT_ROUNDS = 3

        /** Working families first, best score first; ties go to the lower median latency. */
        fun rank(results: List<LabResult>): List<LabResult> =
            results.sortedWith(
                compareByDescending<LabResult> { it.works }
                    .thenByDescending { it.score }
                    .thenBy { it.medianMs ?: Long.MAX_VALUE }
            )

        /** 0..100 from success rate, median latency and jitter, weighed by [priority]. */
        fun score(result: LabResult, priority: LabPriority): Int {
            val median = result.medianMs ?: return 0
            val rounds = result.latenciesMs.size.coerceAtLeast(1)
            val success = result.successes * 100.0 / rounds
            val latency = when {
                median <= 80 -> 100.0
                median >= 1500 -> 0.0
                else -> 100.0 * (1500 - median) / (1500 - 80)
            }
            val jitter = (100.0 - (result.jitterMs ?: 0L) / 4.0).coerceIn(0.0, 100.0)
            val (ws, wl, wj) = when (priority) {
                LabPriority.BALANCED -> Triple(0.45, 0.4, 0.15)
                LabPriority.FASTEST -> Triple(0.3, 0.6, 0.1)
                LabPriority.MOST_STABLE -> Triple(0.6, 0.15, 0.25)
            }
            return (success * ws + latency * wl + jitter * wj).toInt().coerceIn(1, 100)
        }
    }
}
