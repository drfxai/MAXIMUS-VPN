package com.example.vpn.connections

import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.NetworkFirewalls
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherTlsProfiles
import org.json.JSONObject

/**
 * Which MASQUE TLS client profile Aether sends when the user left the fingerprint at Auto
 * ([AetherTlsProfiles.AUTO]), per network.
 *
 * patterniha (2026-11): on the Irancell firewall pick a non-Chrome fingerprint for MASQUE; Chrome's post-quantum,
 * ALPS and ECH-GREASE ClientHello is the one that fails there. So on an Irancell-type firewall Auto tries
 * semi-python, then firefox, then go, and only then chrome ([IRANCELL_ORDER]); on Hamrah-e-Aval and every other
 * network Auto stays chrome ([DEFAULT_ORDER]), as before.
 *
 * The profile that carried verified traffic on a network is remembered for it ([win], [WIN_TTL_MS]) and sent first
 * there next time. A profile that failed there is passed over for [FAIL_TTL_MS] ([fail]), so each connect (or the
 * second attempt of the same connect) moves on to the next one; when every profile has failed, the one that failed
 * longest ago is tried again. A concrete fingerprint the user picked never comes here: it is sent exactly as chosen.
 *
 * Stored per network key ("cell:43235"), the profile names and times only.
 */
object AetherFingerprintBook {
    @Volatile var read: (String) -> String? = { null }
    @Volatile var write: (String, String?) -> Unit = { _, _ -> }

    val IRANCELL_ORDER = listOf("semi-python", "firefox", "go", "chrome")
    val DEFAULT_ORDER = listOf("chrome")

    const val WIN_TTL_MS = 3L * 24 * 60 * 60 * 1000
    const val FAIL_TTL_MS = 12L * 60 * 60 * 1000

    /** Extras key: this attempt's fingerprint was picked here (so its outcome is recorded here). */
    const val AUTO_PICKED = "fingerprintAuto"

    /** Carriers whose handshake is MASQUE TLS (the only ones a fingerprint changes). */
    private val MASQUE = setOf("h2", "h3", "gool", "mim")

    fun order(firewall: NetworkFirewalls.Firewall): List<String> =
        if (firewall == NetworkFirewalls.Firewall.IRANCELL) IRANCELL_ORDER else DEFAULT_ORDER

    fun order(network: String?): List<String> = order(NetworkFirewalls.detect(network))

    private data class State(val winner: String?, val wonAt: Long, val failures: Map<String, Long>)

    private fun key(network: String) = "aetherFp|$network"

    private fun load(network: String, now: Long): State {
        val json = runCatching { JSONObject(read(key(network)) ?: return State(null, 0L, emptyMap())) }.getOrNull()
            ?: return State(null, 0L, emptyMap())
        val wonAt = json.optLong("wonAt")
        val winner = json.optString("winner").takeIf { it in AetherTlsProfiles.names && now - wonAt in 0 until WIN_TTL_MS }
        val failed = json.optJSONObject("failed")
        val failures = failed?.keys()?.asSequence()?.filter { it in AetherTlsProfiles.names }
            ?.associateWith { failed.optLong(it) }?.filterValues { now - it in 0 until FAIL_TTL_MS }.orEmpty()
        return State(winner, wonAt, failures)
    }

    private fun save(network: String, state: State) {
        if (state.winner == null && state.failures.isEmpty()) { write(key(network), null); return }
        val json = JSONObject()
        state.winner?.let { json.put("winner", it).put("wonAt", state.wonAt) }
        json.put("failed", JSONObject().apply { state.failures.forEach { (k, v) -> put(k, v) } })
        write(key(network), json.toString())
    }

    /** The profile Auto sends next on [network]. */
    @Synchronized
    fun pick(network: String?, now: Long = System.currentTimeMillis()): String {
        val order = order(network)
        if (order.size == 1 || network.isNullOrBlank()) return order.first()
        val state = load(network, now)
        state.winner?.takeIf { it in order && it !in state.failures }?.let { return it }
        return order.firstOrNull { it !in state.failures }
            ?: order.minByOrNull { state.failures[it] ?: Long.MIN_VALUE }!!
    }

    /** [fingerprint] carried verified traffic on [network]: sent first there from now on. */
    @Synchronized
    fun win(network: String?, fingerprint: String, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank() || fingerprint !in AetherTlsProfiles.names) return
        val state = load(network, now)
        save(network, State(fingerprint, now, state.failures - fingerprint))
    }

    /** An attempt with [fingerprint] failed on [network]: the next attempt there moves on to the next profile. */
    @Synchronized
    fun fail(network: String?, fingerprint: String, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank() || fingerprint !in AetherTlsProfiles.names || order(network).size == 1) return
        val state = load(network, now)
        val winner = state.winner.takeIf { it != fingerprint }
        save(network, State(winner, if (winner == null) 0L else state.wonAt, state.failures + (fingerprint to now)))
    }

    /** The extras carry a fingerprint left at Auto on a carrier that sends MASQUE TLS. */
    fun appliesTo(extras: JSONObject): Boolean =
        extras.optString("carrier") in MASQUE && AetherSettings.read(extras).fingerprint == AetherTlsProfiles.AUTO

    /**
     * [candidate] with Auto replaced by this network's pick, marked [AUTO_PICKED] so [record] knows the outcome is
     * ours. A concrete fingerprint, or a carrier without MASQUE TLS, is returned unchanged.
     */
    fun resolve(candidate: VlessProfile, network: String?, now: Long = System.currentTimeMillis()): VlessProfile {
        val e = ProfileExtras.read(candidate)
        if (!appliesTo(e)) return candidate
        return candidate.copy(extraSettings = JSONObject(e.toString()).put("fingerprint", pick(network, now))
            .put(AetherSettings.FINGERPRINT_CHOSEN, true).put(AUTO_PICKED, true).toString())
    }

    /** The fingerprint [resolve] picked for [candidate], or null when the user's own choice was sent. */
    fun picked(candidate: VlessProfile): String? = ProfileExtras.read(candidate)
        .takeIf { it.optBoolean(AUTO_PICKED) }?.optString("fingerprint")?.takeIf { it in AetherTlsProfiles.names }

    /** Records the outcome of an attempt made with a fingerprint [resolve] picked. Nothing for a user's own choice. */
    fun record(network: String?, candidate: VlessProfile, verified: Boolean, now: Long = System.currentTimeMillis()) {
        val fingerprint = picked(candidate) ?: return
        if (verified) win(network, fingerprint, now) else fail(network, fingerprint, now)
    }

    /** One log line naming the profile and why. */
    fun describe(network: String?, fingerprint: String): String =
        if (order(network).size == 1) "TLS fingerprint Auto: $fingerprint"
        else "TLS fingerprint Auto on an Irancell-type firewall: $fingerprint (order ${order(network).joinToString(" → ")})"
}
