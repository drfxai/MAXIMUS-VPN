package com.example.vpn.smart

import org.json.JSONArray
import org.json.JSONObject

/**
 * Post-connect speed pick for Smart Connect: a race picks the winner on one small request, which says little
 * about bulk speed. After the connection passed its transfer check, a ~2 MB download is timed through the
 * server in use (the TUN) and through up to [MAX_ALTERNATES] other servers that also carried traffic in the
 * same race. Smart Connect moves to an alternate only when it was more than [SWITCH_MARGIN] faster
 * ([decide]); otherwise nothing changes.
 *
 * The alternates are measured through the running Xray core itself, so their traffic leaves on the phone's
 * own network exactly as it would after a switch (the core protects its sockets): each one gets a
 * password-protected SOCKS inbound on the loopback interface routed to its own outbound ([addProbes]).
 * Downloads run one after another so they do not share the link.
 *
 * Skipped entirely on a metered network unless the user turned on
 * [com.example.data.model.AppSettings.speedPickOnMetered] (off by default), and at most once per Connect.
 */
object PostConnectSpeedPick {
    /** Alternates measured besides the server in use. */
    const val MAX_ALTERNATES = 2
    /** An alternate must be more than this much faster (10%) before Smart Connect switches. */
    const val SWITCH_MARGIN = 0.10
    /** Size of each timed download. */
    const val DOWNLOAD_BYTES = 2 * 1024 * 1024
    /** One download's own deadline: a slower route is not ranked (2 MB in 20 s is ~0.8 Mbit/s). */
    const val STEP_TIMEOUT_MS = 20_000
    /** Inbound and outbound tag prefix in the Xray config. */
    const val TAG_PREFIX = "speed-pick-"

    /** One alternate as the running core carries it: [port]/[user]/[pass] reach [outbound] from the app. */
    data class Probe(val ownerId: String, val tag: String, val port: Int, val user: String, val pass: String,
                     val outbound: JSONObject)

    /**
     * Whether the speed pick runs for this connection: a Smart pick carried by the Xray core through the TUN, with
     * at least one other verified server, and either an unmetered network or the user's permission on metered ones.
     */
    fun applies(smartPick: Boolean, tunPath: Boolean, metered: Boolean, allowMetered: Boolean,
                alternates: Int, alreadyRan: Boolean): Boolean =
        smartPick && tunPath && alternates > 0 && !alreadyRan && (!metered || allowMetered)

    /**
     * Ids of the alternates to measure, from the race's other verified servers (best first): never the server in
     * use, nothing in [exclude] (frozen or stalled here), one per saved server, at most [MAX_ALTERNATES].
     */
    fun pickAlternates(runnersUp: List<String>, current: String, exclude: Set<String> = emptySet()): List<String> =
        runnersUp.asSequence().filter { it != current && it !in exclude }.distinct().take(MAX_ALTERNATES).toList()

    /**
     * The alternate to switch to, or null to stay: the fastest measured alternate, only when its rate is more than
     * [SWITCH_MARGIN] above [currentBps]. With no measured rate for the server in use nothing is compared; an
     * alternate that could not be measured (null) never wins.
     */
    fun decide(currentBps: Long?, alternates: Map<String, Long?>): String? {
        val base = currentBps?.takeIf { it > 0 } ?: return null
        val best = alternates.entries.filter { (it.value ?: 0) > 0 }.maxByOrNull { it.value!! } ?: return null
        return best.key.takeIf { fasterEnough(base, best.value!!) }
    }

    /** True when [candidateBps] is more than [SWITCH_MARGIN] above [currentBps]. */
    fun fasterEnough(currentBps: Long, candidateBps: Long): Boolean =
        currentBps > 0 && candidateBps * 100 > currentBps * (100 + (SWITCH_MARGIN * 100).toLong())

    /**
     * An alternate's Xray outbound, taken from a full config built for it alone: usable only when it stands on its
     * own (no dialer proxy or proxy chain that names another outbound of that config). Null when it cannot be used.
     */
    fun standaloneOutbound(fullConfig: JSONObject, tag: String): JSONObject? {
        val out = fullConfig.optJSONArray("outbounds")?.optJSONObject(0) ?: return null
        val text = out.toString()
        if ("dialerProxy" in text || "proxySettings" in text) return null
        if (out.optString("protocol") in setOf("", "freedom", "blackhole", "dns")) return null
        return JSONObject(text).put("tag", tag)
    }

    /**
     * Adds each probe to [config]: an authenticated SOCKS inbound on 127.0.0.1, its outbound, and a routing rule
     * (first, ahead of every other rule) that sends that inbound, and only it, to that outbound.
     */
    fun addProbes(config: JSONObject, probes: List<Probe>): JSONObject = config.apply {
        if (probes.isEmpty()) return@apply
        val inbounds = optJSONArray("inbounds") ?: JSONArray().also { put("inbounds", it) }
        val outbounds = optJSONArray("outbounds") ?: JSONArray().also { put("outbounds", it) }
        val routing = optJSONObject("routing") ?: JSONObject().also { put("routing", it) }
        val rules = JSONArray()
        probes.forEach { p ->
            inbounds.put(JSONObject().put("tag", p.tag).put("listen", "127.0.0.1").put("port", p.port)
                .put("protocol", "socks").put("settings", JSONObject().put("auth", "password").put("udp", false)
                    .put("accounts", JSONArray().put(JSONObject().put("user", p.user).put("pass", p.pass)))))
            outbounds.put(JSONObject(p.outbound.toString()).put("tag", p.tag))
            rules.put(JSONObject().put("type", "field").put("inboundTag", JSONArray().put(p.tag)).put("outboundTag", p.tag))
        }
        routing.optJSONArray("rules")?.let { old -> (0 until old.length()).forEach { rules.put(old.get(it)) } }
        routing.put("rules", rules)
    }

    /**
     * Probes handed from the VPN service to the next Xray start ([consume]); set just before the core starts and
     * taken by it, so a later start (failover, reconnect) never carries stale probes. [active] holds the probes
     * the running core actually accepted (empty when it started without them).
     */
    @Volatile private var pending: List<Probe> = emptyList()
    @Volatile var active: List<Probe> = emptyList()
        private set

    fun stage(probes: List<Probe>) { pending = probes }

    fun consume(): List<Probe> = pending.also { pending = emptyList() }

    fun markActive(probes: List<Probe>) { active = probes }

    fun clear() { pending = emptyList(); active = emptyList() }
}
