package com.example.vpn.smart

import com.example.data.model.VlessProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * The exact route that carried verified traffic on one network: which saved config, which stealth
 * alternate or recovered copy of it, and which server address. Smart Connect tries it first the next time
 * the phone is on that network, and it still has to carry a real request there before the tunnel uses it.
 *
 * It holds references and non-secret settings only. The config itself (and its credentials) stays in the
 * server list; a recipe whose config changed or was deleted is dropped instead of replayed.
 */
data class RouteRecipe(
    /** The network it worked on ([NetworkIdentity] key: carrier code, or a salted Wi-Fi fingerprint). */
    val network: String,
    val profileId: String,
    /** The saved config's fingerprint when it worked; a changed config makes the recipe stale. */
    val profileFingerprint: String,
    /** The [com.example.vpn.stealth.StealthVariants] key, when an alternate carried the traffic. */
    val variantKey: String? = null,
    /** The recovered copy's recovery profile key, when a derived copy carried the traffic. */
    val recoveryKey: String? = null,
    /** The server address the config's name resolved to, when that address carried the traffic. */
    val endpoint: String? = null,
    /** ConnectionKind of the path, for logs and ordering. */
    val kind: String,
    val latencyMs: Long,
    val verifiedAt: Long,
    val expiresAt: Long,
    /** Upload and download were also verified through it (see TrafficCheck). */
    val bidirectional: Boolean = false,
    /** Replays that failed in a row since it last worked. */
    val failures: Int = 0
) {
    /** Two recipes are the same route when every part that decides the path is the same. */
    val id: String get() = listOf(profileId, variantKey.orEmpty(), recoveryKey.orEmpty(), endpoint.orEmpty()).joinToString("|")

    fun toJson(): JSONObject = JSONObject()
        .put("n", network).put("p", profileId).put("fp", profileFingerprint)
        .put("k", kind).put("l", latencyMs).put("at", verifiedAt).put("exp", expiresAt)
        .put("bi", bidirectional).put("f", failures)
        .apply {
            variantKey?.let { put("v", it) }
            recoveryKey?.let { put("r", it) }
            endpoint?.let { put("e", it) }
        }

    companion object {
        fun fromJson(o: JSONObject): RouteRecipe? = runCatching {
            RouteRecipe(
                network = o.getString("n"), profileId = o.getString("p"), profileFingerprint = o.getString("fp"),
                variantKey = o.optString("v").takeIf { it.isNotEmpty() },
                recoveryKey = o.optString("r").takeIf { it.isNotEmpty() },
                endpoint = o.optString("e").takeIf { it.isNotEmpty() },
                kind = o.getString("k"), latencyMs = o.getLong("l"), verifiedAt = o.getLong("at"),
                expiresAt = o.getLong("exp"), bidirectional = o.optBoolean("bi"), failures = o.optInt("f")
            )
        }.getOrNull()

        /**
         * [profile] dialled at the literal address [ip] instead of its name. TLS keeps the name as SNI and
         * WebSocket-style transports keep it as the Host header, so the server's identity is checked exactly
         * as before; only the DNS lookup is skipped.
         */
        fun pinEndpoint(profile: VlessProfile, ip: String): VlessProfile {
            val hostName = profile.address.trim().removePrefix("[").removeSuffix("]")
            val usesTls = profile.security.equals("tls", true) || profile.security.equals("reality", true)
            val usesHostHeader = profile.transport.lowercase() in setOf("ws", "xhttp", "httpupgrade", "splithttp", "h2", "http")
            return profile.copy(
                address = ip,
                // The copy is the same path: evidence about it belongs to the saved config.
                canonicalFingerprint = profile.effectiveFingerprint,
                sni = if (usesTls && profile.sni.isBlank()) hostName else profile.sni,
                host = if (usesHostHeader && profile.host.isBlank()) hostName else profile.host
            )
        }
    }
}

/**
 * Verified routes per network, best first. Bounded: a few routes per network, a few networks, each route
 * expiring after [TTL_MS] and dropped after [MAX_FAILURES] failed replays in a row. [load] and [save] hold
 * the JSON under its own schema version, so an older app ignores it and a newer one can migrate it.
 */
class RouteBook(private val load: () -> String?, private val save: (String) -> Unit) {
    private val routes: MutableList<RouteRecipe> by lazy { parse(load()) }

    /** Routes for [network] that have not expired, best first: both directions verified, then newest. */
    @Synchronized
    fun forNetwork(network: String, now: Long = System.currentTimeMillis()): List<RouteRecipe> =
        routes.filter { it.network == network && it.expiresAt > now }
            .sortedWith(compareByDescending<RouteRecipe> { it.bidirectional }.thenBy { it.failures }.thenByDescending { it.verifiedAt })

    /** How many unexpired routes each network has, for Settings: network keys and counts only. */
    @Synchronized
    fun countsByNetwork(now: Long = System.currentTimeMillis()): Map<String, Int> =
        routes.filter { it.expiresAt > now }.groupingBy { it.network }.eachCount()

    /** [recipe] carried verified traffic: it replaces the same route and becomes the newest. */
    @Synchronized
    fun recordVerified(recipe: RouteRecipe) {
        val old = routes.firstOrNull { it.network == recipe.network && it.id == recipe.id }
        routes.removeAll { it.network == recipe.network && it.id == recipe.id }
        routes += recipe.copy(failures = 0, bidirectional = recipe.bidirectional || (old?.bidirectional == true && old.profileFingerprint == recipe.profileFingerprint))
        trim(recipe.verifiedAt)
        persist()
    }

    /** Upload and download passed through the route as well. */
    @Synchronized
    fun markBidirectional(network: String, id: String) {
        val i = routes.indexOfFirst { it.network == network && it.id == id }
        if (i < 0) return
        routes[i] = routes[i].copy(bidirectional = true)
        persist()
    }

    /** A replay or the live tunnel on this route stopped carrying traffic. */
    @Synchronized
    fun recordFailure(network: String, id: String) {
        val i = routes.indexOfFirst { it.network == network && it.id == id }
        if (i < 0) return
        val r = routes[i]
        if (r.failures + 1 >= MAX_FAILURES) routes.removeAt(i) else routes[i] = r.copy(failures = r.failures + 1)
        persist()
    }

    /** The recipe can no longer be rebuilt (its config changed or was deleted). */
    @Synchronized
    fun drop(network: String, id: String) {
        if (routes.removeAll { it.network == network && it.id == id }) persist()
    }

    /** Forgets every learned route. */
    @Synchronized
    fun clear() {
        routes.clear()
        persist()
    }

    private fun trim(now: Long) {
        routes.removeAll { it.expiresAt <= now }
        routes.groupBy { it.network }.forEach { (_, list) ->
            list.sortedByDescending { it.verifiedAt }.drop(MAX_PER_NETWORK).forEach { routes.remove(it) }
        }
        val networks = routes.groupBy { it.network }.mapValues { (_, l) -> l.maxOf { it.verifiedAt } }
            .entries.sortedByDescending { it.value }.map { it.key }
        networks.drop(MAX_NETWORKS).forEach { n -> routes.removeAll { it.network == n } }
    }

    private fun persist() {
        save(JSONObject().put("schema", SCHEMA).put("routes", JSONArray().apply { routes.forEach { put(it.toJson()) } }).toString())
    }

    companion object {
        const val SCHEMA = 1
        const val MAX_PER_NETWORK = 5
        const val MAX_NETWORKS = 16
        const val MAX_FAILURES = 2
        const val TTL_MS = 3 * 24 * 60 * 60 * 1000L

        private fun parse(text: String?): MutableList<RouteRecipe> {
            val root = runCatching { JSONObject(text ?: return mutableListOf()) }.getOrNull() ?: return mutableListOf()
            // A schema this version does not know is ignored rather than misread.
            if (root.optInt("schema") != SCHEMA) return mutableListOf()
            val arr = root.optJSONArray("routes") ?: return mutableListOf()
            return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { RouteRecipe.fromJson(it) } }.toMutableList()
        }
    }
}
