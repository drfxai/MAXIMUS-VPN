package com.example.vpn.stealth

import com.example.core.SecretRedactor
import com.example.data.model.VlessProfile
import com.example.vpn.engine.RuntimeCapabilities
import com.example.xray.RealDelayProbe
import com.example.xray.XrayLogManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Picks the path a connection actually takes, before the VPN core starts, with real requests
 * (libXray pingBatch) rather than handshakes:
 *
 * 1. The profile as saved, or the alternate that worked for it last time.
 * 2. If that carried no traffic: its stealth alternates ([StealthVariants]) together with other kinds
 *    of connection to the same server (REALITY, then CDN/XHTTP, TLS, QUIC, WireGuard), in parallel, so
 *    a filter that blocks one disguise does not take the server down with it. The user's own profile
 *    wins over a switch when both work.
 * 3. If still nothing: the same server's other kinds with their own stealth alternates.
 *
 * When nothing works, or the probe cannot run (the libXray probe is unavailable or busy), the saved
 * profile is used unchanged and the existing failover takes over as before.
 */
class StealthPathFinder(
    private val probe: (List<VlessProfile>, Int) -> List<RealDelayProbe.Outcome> = { p, t -> RealDelayProbe.measure(p, t) },
    private val log: (String) -> Unit = { XrayLogManager.i("STEALTH", it) },
    /** Profile id -> alternate that last worked; the app passes the current network's (see NetworkMemory). */
    private val memory: MutableMap<String, String> = Companion.memory,
    private val firstTimeoutSec: Int = FIRST_TIMEOUT_SEC,
    private val alternateTimeoutSec: Int = ALTERNATE_TIMEOUT_SEC
) {
    data class Choice(
        /** What the engine runs. */
        val profile: VlessProfile,
        /** The saved profile this path belongs to (differs from the requested one after a same-server switch). */
        val owner: VlessProfile,
        val label: String,
        val latencyMs: Long? = null,
        /** The [StealthVariants] key, when this is an alternate of the requested profile. */
        val variantKey: String? = null,
        /** False when no test ran (no alternates, or the probe was unavailable). */
        val tested: Boolean = true
    ) {
        /** Every path was tested and none carried traffic. */
        val nothingWorked: Boolean get() = tested && latencyMs == null
    }

    /** The path tried first for [requested] ([resolved] is its host turned into an address): the alternate that worked last time, or the profile as saved. */
    fun firstPath(requested: VlessProfile, resolved: VlessProfile): Choice {
        val remembered = memory[requested.id]?.let { key -> StealthVariants.of(resolved).firstOrNull { it.key == key } }
        return remembered?.let { Choice(it.profile, requested, it.label, variantKey = it.key) } ?: Choice(resolved, requested, "as saved")
    }

    /**
     * [requested] is the profile the user picked, [resolve] turns a profile's host into an address
     * outside the tunnel (identity in tests), [siblings] are the other saved profiles. [firstFailed]:
     * the caller already tried [firstPath] and it carried no traffic.
     */
    fun choose(
        requested: VlessProfile,
        resolve: (VlessProfile) -> VlessProfile,
        siblings: List<VlessProfile>,
        firstFailed: Boolean = false
    ): Choice {
        val resolved = resolve(requested)
        val variants = StealthVariants.of(resolved)
        val asSaved = Choice(resolved, requested, "as saved")
        if (variants.isEmpty()) return asSaved.copy(tested = firstFailed)

        // Stage 1: the last winner (if any) or the saved profile, alone, so a working path costs one request.
        val first = firstPath(requested, resolved)
        val remembered = first.variantKey?.let { key -> variants.firstOrNull { it.key == key } }
        if (!firstFailed) when (val outcome = probe(listOf(first.profile), firstTimeoutSec).first()) {
            is RealDelayProbe.Outcome.Delay -> return first.copy(latencyMs = outcome.latencyMs)
            is RealDelayProbe.Outcome.NotRun -> {
                log("Path test unavailable (${outcome.reason}); connecting as saved.")
                return asSaved.copy(tested = false)
            }
            is RealDelayProbe.Outcome.Failed -> log("No traffic through ${first.label}: ${SecretRedactor.redact(outcome.reason)}")
        }

        // Stage 2: every other way of sending this profile and other kinds of connection to the same
        // server, together, so a filter that blocks the whole kind (all UDP, say) costs one round, not two.
        val alternates = buildList {
            if (remembered != null) add(asSaved)
            variants.filter { it.key != remembered?.key }.forEach { add(Choice(it.profile, requested, it.label, variantKey = it.key)) }
        }
        val sameServer = siblings.filter {
            it.id != requested.id && it.address.equals(requested.address, ignoreCase = true) &&
                RuntimeCapabilities.unsupportedReason(it) == null && kind(it) != kind(requested)
        }.sortedBy { KIND_ORDER.indexOf(kind(it)).let { i -> if (i < 0) KIND_ORDER.size else i } }
            .take(RealDelayProbe.MAX_BATCH)
            .mapNotNull { sib -> runCatching { Choice(resolve(sib), sib, "${kind(sib)} on the same server (${sib.name})") }.getOrNull() }
        val firstRound = alternates.take(ALTERNATES_IN_FIRST_ROUND).let { alt ->
            alt + sameServer.take(RealDelayProbe.MAX_BATCH - alt.size)
        }
        val ordered = firstRound + (alternates + sameServer).filter { it !in firstRound }
        best(ordered, requested.id)?.let { win ->
            if (win.owner.id == requested.id) {
                if (win.variantKey == null) memory.remove(requested.id) else memory[requested.id] = win.variantKey
                log("Connected through an alternate path: ${win.label} (${win.latencyMs} ms).")
            } else {
                log("Switched to ${SecretRedactor.redact(win.label)} (${win.latencyMs} ms).")
            }
            return win
        }

        // Stage 3: the same server's other kinds in disguise (a split REALITY handshake, say), for networks
        // that filter several things at once. Only reached when everything above failed.
        val disguisedSiblings = sameServer.flatMap { sib ->
            StealthVariants.of(sib.profile).take(2).map { v ->
                Choice(v.profile, sib.owner, "${sib.label}, ${v.label.replaceFirstChar(Char::lowercase)}", variantKey = v.key)
            }
        }.take(RealDelayProbe.MAX_BATCH)
        best(disguisedSiblings, requested.id)?.let { win ->
            win.variantKey?.let { memory[win.owner.id] = it }
            log("Switched to ${SecretRedactor.redact(win.label)} (${win.latencyMs} ms).")
            return win
        }

        log("No working path found for '${SecretRedactor.redact(requested.name)}'; connecting as saved.")
        return asSaved
    }

    /** First batch that has a working path wins; the user's own profile is preferred over a switch. */
    private fun best(candidates: List<Choice>, ownId: String): Choice? {
        for (batch in parallelSafeBatches(candidates)) {
            val working = batch.zip(probe(batch.map { it.profile }, alternateTimeoutSec))
                .mapNotNull { (c, o) -> (o as? RealDelayProbe.Outcome.Delay)?.let { c.copy(latencyMs = it.latencyMs) } }
            (working.filter { it.owner.id == ownId }.ifEmpty { working })
                .minByOrNull { it.latencyMs ?: Long.MAX_VALUE }
                ?.let { return it }
        }
        return null
    }

    companion object {
        /**
         * Splits [candidates] so no batch holds two WireGuard paths with the same private key: the
         * server follows the newest source address of a key, so parallel handshakes knock each other
         * out (seen in the censorship simulator). Order is kept.
         */
        internal fun parallelSafeBatches(candidates: List<Choice>): List<List<Choice>> = parallelSafeBatches(candidates) { it.profile }

        fun <T> parallelSafeBatches(candidates: List<T>, profileOf: (T) -> VlessProfile): List<List<T>> {
            fun wgKey(p: VlessProfile) = p.uuid.takeIf { p.protocolType == com.example.data.model.ProtocolType.WIREGUARD }
            val batches = mutableListOf<MutableList<T>>()
            for (c in candidates) {
                val key = wgKey(profileOf(c))
                val target = batches.firstOrNull { batch ->
                    batch.size < RealDelayProbe.MAX_BATCH && (key == null || batch.none { wgKey(profileOf(it)) == key })
                }
                if (target != null) target += c else batches += mutableListOf(c)
            }
            return batches
        }

        const val FIRST_TIMEOUT_SEC = 4
        const val ALTERNATE_TIMEOUT_SEC = 5

        /** Alternates of the user's profile in the first parallel round; the rest of it goes to the same server's other kinds. */
        const val ALTERNATES_IN_FIRST_ROUND = 3

        /** Profile id -> stealth variant key that last carried traffic, for this app run. */
        internal val memory = ConcurrentHashMap<String, String>()

        internal val KIND_ORDER = ConnectionKind.ORDER

        internal fun kind(profile: VlessProfile): String = ConnectionKind.of(profile)
    }
}
