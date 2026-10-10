package com.example.vpn.connectivity

import com.example.xray.ProbeTargets
import org.json.JSONObject

/**
 * The probe-target manifest: which lightweight international endpoints prove egress, each with its failure
 * domain, address family, expected response, size limit and timeout, plus a version and an expiry.
 *
 * Delivery and trust: the aggregator publishes `probes.json` next to the free list; the list's signed manifest
 * names its SHA-256 (ECDSA P-256, the key built into the app), and it is served from GitHub and its CDN copies
 * (several origins). The app keeps the last valid manifest, falls back to [ProbeTargets.BUILT_IN] when it has
 * none or it expired, and never lets the control plane block a connection: a missing or refused manifest only
 * means the built-in targets are used.
 */
object ProbeManifest {
    const val SCHEMA = 1
    /** A manifest may not live longer than this, whatever it claims. */
    const val MAX_TTL_MS = 30L * 24 * 60 * 60_000L
    const val MIN_TARGETS = 2

    data class Parsed(val version: Int, val createdAt: Long, val expiresAt: Long, val targets: List<ProbeTargets.Target>)

    class Invalid(message: String) : IllegalArgumentException(message)

    /**
     * Parses a manifest whose signature was already checked. Refuses: another schema, too few independent
     * targets, non-HTTPS URLs, unknown address families, and expiry beyond [MAX_TTL_MS].
     */
    fun parse(json: String, now: Long): Parsed {
        val root = runCatching { JSONObject(json) }.getOrElse { throw Invalid("unreadable probe manifest") }
        if (root.optInt("schema") != SCHEMA) throw Invalid("unknown probe manifest schema")
        val version = root.optInt("version", 0).takeIf { it > 0 } ?: throw Invalid("probe manifest has no version")
        val created = root.optLong("createdAt", 0)
        val ttl = root.optLong("ttlMs", 0).takeIf { it in 1..MAX_TTL_MS } ?: throw Invalid("probe manifest TTL out of range")
        val items = root.optJSONArray("targets") ?: throw Invalid("probe manifest lists no targets")
        val targets = (0 until items.length()).map { i ->
            val t = items.getJSONObject(i)
            val url = t.optString("url")
            if (!url.startsWith("https://")) throw Invalid("probe target ${t.optString("id")} is not HTTPS")
            ProbeTargets.Target(
                id = t.optString("id").ifBlank { throw Invalid("probe target without id") }.take(40),
                url = url,
                failureDomain = t.optString("failureDomain").ifBlank { throw Invalid("probe target without failure domain") },
                expected = t.optString("expected").ifBlank { "HTTPS response after a verified TLS handshake" }.take(120),
                addressFamily = runCatching { ProbeTargets.AddressFamily.valueOf(t.optString("addressFamily", "ANY")) }
                    .getOrElse { throw Invalid("unknown address family") },
                maxResponseBytes = t.optInt("maxResponseBytes", 0).coerceIn(0, 65_536),
                timeoutSec = t.optInt("timeoutSec", 0).takeIf { it in 1..30 },
                freshnessMs = t.optLong("freshnessMs", ProbeTargets.TARGET_HEALTH_MS).coerceIn(60_000L, 60 * 60_000L)
            )
        }
        if (targets.distinctBy { it.failureDomain }.size < MIN_TARGETS) throw Invalid("probe manifest needs targets in at least $MIN_TARGETS failure domains")
        val createdAt = if (created in 1..now + 5 * 60_000L) created else now
        return Parsed(version, createdAt, createdAt + ttl, targets)
    }

    /** The targets to use now: [lastGood] while unexpired, else the built-in list. */
    fun choose(lastGood: Parsed?, now: Long): List<ProbeTargets.Target> =
        lastGood?.takeIf { now < it.expiresAt }?.targets ?: ProbeTargets.BUILT_IN

    /** A newer manifest replaces the last good one; an older or equal version never does. */
    fun newer(current: Parsed?, candidate: Parsed, now: Long = System.currentTimeMillis()): Boolean =
        current == null || candidate.version > current.version || now >= current.expiresAt
}

/** Keeps the last valid probe manifest on the phone and installs its targets. */
object ProbeManifestStore {
    private const val PREFS = "probe_manifest"
    private const val KEY = "v1"
    @Volatile private var prefs: android.content.SharedPreferences? = null
    @Volatile private var current: ProbeManifest.Parsed? = null

    fun install(context: android.content.Context) {
        val p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        prefs = p
        current = p.getString(KEY, null)?.let { runCatching { ProbeManifest.parse(it, System.currentTimeMillis()) }.getOrNull() }
        ProbeTargets.active = ProbeManifest.choose(current, System.currentTimeMillis())
    }

    /** A signature-checked manifest body; ignored when invalid or not newer. */
    fun accept(json: String) {
        val now = System.currentTimeMillis()
        val parsed = runCatching { ProbeManifest.parse(json, now) }.getOrNull() ?: return
        if (!ProbeManifest.newer(current, parsed, now)) return
        current = parsed
        prefs?.edit()?.putString(KEY, json)?.apply()
        ProbeTargets.active = ProbeManifest.choose(parsed, now)
    }
}
