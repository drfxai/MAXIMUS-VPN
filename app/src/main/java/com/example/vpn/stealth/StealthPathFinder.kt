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
 * 2. If that carried no traffic: its stealth alternates ([StealthVariants]) in one parallel batch.
 * 3. If none did: other kinds of connection to the same server (REALITY, then CDN/XHTTP, TLS, QUIC,
 *    WireGuard), so a filter that blocks one disguise does not take the server down with it.
 *
 * When nothing works, or the probe cannot run (the libXray probe is unavailable or busy), the saved
 * profile is used unchanged and the existing failover takes over as before.
 */
class StealthPathFinder(
    private val probe: (List<VlessProfile>, Int) -> List<RealDelayProbe.Outcome> = { p, t -> RealDelayProbe.measure(p, t) },
    private val log: (String) -> Unit = { XrayLogManager.i("STEALTH", it) }
) {
    data class Choice(
        /** What the engine runs. */
        val profile: VlessProfile,
        /** The saved profile this path belongs to (differs from the requested one after a same-server switch). */
        val owner: VlessProfile,
        val label: String,
        val latencyMs: Long? = null,
        /** The [StealthVariants] key, when this is an alternate of the requested profile. */
        val variantKey: String? = null
    )

    /**
     * [requested] is the profile the user picked, [resolve] turns a profile's host into an address
     * outside the tunnel (identity in tests), [siblings] are the other saved profiles.
     */
    fun choose(
        requested: VlessProfile,
        resolve: (VlessProfile) -> VlessProfile,
        siblings: List<VlessProfile>
    ): Choice {
        val resolved = resolve(requested)
        val variants = StealthVariants.of(resolved)
        val asSaved = Choice(resolved, requested, "as saved")
        if (variants.isEmpty()) return asSaved

        // Stage 1: the last winner (if any) or the saved profile, alone, so a working path costs one request.
        val remembered = memory[requested.id]?.let { key -> variants.firstOrNull { it.key == key } }
        val first = remembered?.let { Choice(it.profile, requested, it.label, variantKey = it.key) } ?: asSaved
        when (val outcome = probe(listOf(first.profile), FIRST_TIMEOUT_SEC).first()) {
            is RealDelayProbe.Outcome.Delay -> return first.copy(latencyMs = outcome.latencyMs)
            is RealDelayProbe.Outcome.NotRun -> {
                log("Path test unavailable (${outcome.reason}); connecting as saved.")
                return asSaved
            }
            is RealDelayProbe.Outcome.Failed -> log("No traffic through ${first.label}: ${SecretRedactor.redact(outcome.reason)}")
        }

        // Stage 2: every other way of sending this profile, in parallel.
        val stage2 = buildList {
            if (remembered != null) add(asSaved)
            variants.filter { it.key != remembered?.key }.forEach { add(Choice(it.profile, requested, it.label, variantKey = it.key)) }
        }.take(RealDelayProbe.MAX_BATCH)
        best(stage2)?.let { win ->
            if (win.variantKey == null) memory.remove(requested.id) else memory[requested.id] = win.variantKey
            log("Connected through an alternate path: ${win.label} (${win.latencyMs} ms).")
            return win
        }

        // Stage 3: another kind of connection to the same server.
        val sameServer = siblings.filter {
            it.id != requested.id && it.address.equals(requested.address, ignoreCase = true) &&
                RuntimeCapabilities.unsupportedReason(it) == null && kind(it) != kind(requested)
        }.sortedBy { KIND_ORDER.indexOf(kind(it)).let { i -> if (i < 0) KIND_ORDER.size else i } }
            .take(RealDelayProbe.MAX_BATCH)
            .mapNotNull { sib -> runCatching { Choice(resolve(sib), sib, "${kind(sib)} on the same server (${sib.name})") }.getOrNull() }
        best(sameServer)?.let { win ->
            log("Switched to ${SecretRedactor.redact(win.label)} (${win.latencyMs} ms).")
            return win
        }

        log("No working path found for '${SecretRedactor.redact(requested.name)}'; connecting as saved.")
        return asSaved
    }

    private fun best(candidates: List<Choice>): Choice? {
        for (batch in parallelSafeBatches(candidates)) {
            val outcomes = probe(batch.map { it.profile }, ALTERNATE_TIMEOUT_SEC)
            batch.zip(outcomes)
                .mapNotNull { (c, o) -> (o as? RealDelayProbe.Outcome.Delay)?.let { c.copy(latencyMs = it.latencyMs) } }
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
        internal fun parallelSafeBatches(candidates: List<Choice>): List<List<Choice>> {
            val batches = mutableListOf<MutableList<Choice>>()
            for (c in candidates) {
                val key = c.profile.takeIf { it.protocolType == com.example.data.model.ProtocolType.WIREGUARD }?.uuid
                val target = batches.firstOrNull { batch ->
                    batch.size < RealDelayProbe.MAX_BATCH &&
                        (key == null || batch.none { it.profile.protocolType == com.example.data.model.ProtocolType.WIREGUARD && it.profile.uuid == key })
                }
                if (target != null) target += c else batches += mutableListOf(c)
            }
            return batches
        }

        const val FIRST_TIMEOUT_SEC = 4
        const val ALTERNATE_TIMEOUT_SEC = 5

        /** Profile id -> stealth variant key that last carried traffic, for this app run. */
        internal val memory = ConcurrentHashMap<String, String>()

        internal val KIND_ORDER = ConnectionKind.ORDER

        internal fun kind(profile: VlessProfile): String = ConnectionKind.of(profile)
    }
}
