package com.example.vpn.smart

import org.json.JSONObject

/**
 * An A/B test of the tunnel MTU, run only when a bulk transfer stalled while small requests passed (a
 * path MTU black hole looks exactly like that). The next connect on that network uses [LOW_MTU]; the
 * bulk check on it decides: if it passes, the lower MTU is kept there for [TTL_MS]; if it stalls too, MTU
 * was not the cause, the configured value comes back and the trial is not repeated for [TTL_MS].
 *
 * The running tunnel is never rebuilt for the test, and only checks made through the tunnel itself
 * count (an engine's own port does not go through it). Per network ([NetworkIdentity] key), bounded.
 */
class TunMtuTrial(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    enum class State { TRYING_LOW, LOW_HELPED, LOW_DID_NOT_HELP }

    private data class Entry(val state: State, val at: Long)

    private val entries: MutableMap<String, Entry> by lazy { parse(load()) }

    /** The MTU to build the tunnel with on [network]. */
    @Synchronized
    fun mtuFor(network: String, configured: Int): Int {
        val e = live(network) ?: return configured
        return if (e.state != State.LOW_DID_NOT_HELP && configured > LOW_MTU) LOW_MTU else configured
    }

    /**
     * A bulk check through the tunnel built with [mtuUsed] on [network] ended: [stalled] when small
     * requests passed but the bulk transfer stopped part-way, [passed] when both directions went through.
     * Returns the state it moved to, or null when nothing changed.
     */
    @Synchronized
    fun onBulkCheck(network: String, mtuUsed: Int, configured: Int, stalled: Boolean, passed: Boolean): State? {
        val e = live(network)
        val next = when {
            mtuUsed == LOW_MTU && e?.state == State.TRYING_LOW && passed -> State.LOW_HELPED
            mtuUsed == LOW_MTU && e?.state == State.TRYING_LOW && stalled -> State.LOW_DID_NOT_HELP
            mtuUsed == LOW_MTU && e?.state == State.LOW_HELPED && stalled -> State.LOW_DID_NOT_HELP
            mtuUsed == configured && configured > LOW_MTU && stalled && e == null -> State.TRYING_LOW
            else -> null
        } ?: return null
        entries[network] = Entry(next, clock())
        trim()
        persist()
        return next
    }

    @Synchronized
    fun state(network: String): State? = live(network)?.state

    @Synchronized
    fun clear() {
        entries.clear()
        persist()
    }

    private fun live(network: String): Entry? {
        val e = entries[network] ?: return null
        return if (clock() - e.at in 0 until TTL_MS) e else null
    }

    private fun trim() {
        val now = clock()
        entries.entries.removeAll { now - it.value.at !in 0 until TTL_MS }
        entries.entries.sortedByDescending { it.value.at }.drop(MAX_NETWORKS).forEach { entries.remove(it.key) }
    }

    private fun persist() {
        val o = JSONObject().put("schema", SCHEMA)
        val n = JSONObject()
        entries.forEach { (k, e) -> n.put(k, JSONObject().put("s", e.state.name).put("at", e.at)) }
        save(o.put("networks", n).toString())
    }

    companion object {
        /** IPv6's minimum link MTU: every path must carry it. */
        const val LOW_MTU = 1280
        const val TTL_MS = 3 * 24 * 60 * 60 * 1000L
        const val MAX_NETWORKS = 16
        const val SCHEMA = 1

        private fun parse(text: String?): MutableMap<String, Entry> {
            val root = runCatching { JSONObject(text ?: return mutableMapOf()) }.getOrNull() ?: return mutableMapOf()
            if (root.optInt("schema") != SCHEMA) return mutableMapOf()
            val n = root.optJSONObject("networks") ?: return mutableMapOf()
            val out = mutableMapOf<String, Entry>()
            for (k in n.keys()) {
                val o = n.optJSONObject(k) ?: continue
                val s = runCatching { State.valueOf(o.getString("s")) }.getOrNull() ?: continue
                out[k] = Entry(s, o.optLong("at"))
            }
            return out
        }
    }
}
