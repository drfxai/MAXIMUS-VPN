package com.example.vpn.dnsfirst

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the DNS tunnel ladder learned on each network (keyed like the rest of the per-network memory,
 * NetworkIdentity: "cell:43235", "wifi:…"): per saved tunnel, the variant that carried a real request
 * ([TTL_MS]) and the variants that failed ([FAIL_TTL_MS]). [load]/[save] hold the JSON.
 */
class DnsLadderMemory(
    private val load: () -> String? = { null },
    private val save: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Winner(
        val via: DnsTunnelLadder.Via,
        val resolvers: List<String>,
        val qpsPerResolver: Int?,
        val latencyMs: Long?,
        val at: Long,
        val settingsHash: String
    )

    private class Failure(var count: Int, var at: Long)
    private class Net(var touched: Long, val winners: MutableMap<String, Winner> = linkedMapOf(), val failures: MutableMap<String, Failure> = linkedMapOf())

    private val nets: LinkedHashMap<String, Net> by lazy { parse(load()) }

    /** The variant that worked for [profileId] on [network], while the profile still has [settingsHash]. */
    @Synchronized
    fun winner(network: String, profileId: String, settingsHash: String): Winner? =
        nets[network]?.winners?.get(profileId)?.takeIf { clock() - it.at in 0 until TTL_MS && it.settingsHash == settingsHash }

    @Synchronized
    fun failures(network: String, profileId: String, key: String): Int =
        nets[network]?.failures?.get("$profileId|$key")?.takeIf { clock() - it.at in 0 until FAIL_TTL_MS }?.count ?: 0

    @Synchronized
    fun recordWin(network: String, profileId: String, settingsHash: String, rung: DnsTunnelLadder.Rung, latencyMs: Long?) {
        val n = net(network)
        n.winners[profileId] = Winner(rung.via, rung.resolvers, rung.qpsPerResolver, latencyMs, clock(), settingsHash)
        n.failures.remove("$profileId|${rung.key}")
        persist()
    }

    @Synchronized
    fun recordFailure(network: String, profileId: String, key: String, forgetWinner: Boolean = false) {
        val n = net(network)
        val now = clock()
        val f = n.failures.getOrPut("$profileId|$key") { Failure(0, now) }
        if (now - f.at !in 0 until FAIL_TTL_MS) f.count = 0
        f.count++; f.at = now
        if (forgetWinner) n.winners.remove(profileId)
        while (n.failures.size > MAX_FAILURES) n.failures.remove(n.failures.keys.first())
        persist()
    }

    /** How many tunnels have a fresh winner on [network]. */
    @Synchronized
    fun winners(network: String): Int = nets[network]?.winners?.values?.count { clock() - it.at in 0 until TTL_MS } ?: 0

    @Synchronized
    fun clear() { nets.clear(); persist() }

    private fun net(network: String): Net {
        val n = nets.remove(network) ?: Net(clock())
        n.touched = clock()
        nets[network] = n
        while (nets.size > MAX_NETWORKS) nets.remove(nets.keys.first())
        return n
    }

    private fun persist() {
        val root = JSONObject().put("schema", SCHEMA)
        val all = JSONObject()
        nets.forEach { (network, n) ->
            val w = JSONObject()
            n.winners.forEach { (id, v) ->
                w.put(id, JSONObject().put("via", v.via.name).put("r", JSONArray(v.resolvers)).put("q", v.qpsPerResolver ?: 0)
                    .put("ms", v.latencyMs ?: -1).put("at", v.at).put("h", v.settingsHash))
            }
            val f = JSONObject()
            n.failures.forEach { (k, v) -> f.put(k, JSONObject().put("c", v.count).put("at", v.at)) }
            all.put(network, JSONObject().put("t", n.touched).put("w", w).put("f", f))
        }
        save(root.put("networks", all).toString())
    }

    private fun parse(raw: String?): LinkedHashMap<String, Net> {
        val out = linkedMapOf<String, Net>()
        val root = runCatching { JSONObject(raw ?: return out) }.getOrNull() ?: return out
        if (root.optInt("schema") != SCHEMA) return out
        val all = root.optJSONObject("networks") ?: return out
        all.keys().forEach { network ->
            val o = all.optJSONObject(network) ?: return@forEach
            val n = Net(o.optLong("t"))
            o.optJSONObject("w")?.let { w ->
                w.keys().forEach { id ->
                    val v = w.optJSONObject(id) ?: return@forEach
                    val via = runCatching { DnsTunnelLadder.Via.valueOf(v.optString("via")) }.getOrNull() ?: return@forEach
                    val r = v.optJSONArray("r") ?: JSONArray()
                    n.winners[id] = Winner(via, (0 until r.length()).map { r.optString(it) }.filter { it.isNotBlank() },
                        v.optInt("q").takeIf { it > 0 }, v.optLong("ms", -1).takeIf { it >= 0 }, v.optLong("at"), v.optString("h"))
                }
            }
            o.optJSONObject("f")?.let { f ->
                f.keys().forEach { k -> f.optJSONObject(k)?.let { n.failures[k] = Failure(it.optInt("c"), it.optLong("at")) } }
            }
            out[network] = n
        }
        return out
    }

    companion object {
        /** Three days: a resolver set that carried the tunnel tends to keep doing so on that network. */
        const val TTL_MS = 3 * 24 * 60 * 60 * 1000L
        /** Twelve hours: carriers change filtering often; a failed variant gets another chance after that. */
        const val FAIL_TTL_MS = 12 * 60 * 60 * 1000L
        private const val MAX_NETWORKS = 8
        private const val MAX_FAILURES = 64
        private const val SCHEMA = 1
    }
}
