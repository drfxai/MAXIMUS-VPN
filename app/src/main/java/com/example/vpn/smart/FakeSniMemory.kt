package com.example.vpn.smart

import org.json.JSONObject

/**
 * Per-network memory for Smart Connect's fake-SNI recovery ([FakeSniRecovery]): the lowest TTL (with its decoy)
 * and the MD5-signed decoy that carried real traffic through the tunnel, the MD5 decoys that failed, and where
 * the TTL ladder stopped. Each entry expires after [MEMORY_MS], since routes and filtering change.
 *
 * Keyed by [NetworkIdentity] ("cell:43235", or a salted Wi-Fi fingerprint). Kept in memory and in
 * [com.example.vpn.connections.BuiltinMethodMemory]'s store, so it survives a service restart. Only an order:
 * a remembered winner is tried first, it is never used without passing a real request again.
 */
object FakeSniMemory {
    const val MEMORY_MS = 3 * 24 * 60 * 60_000L

    data class TtlWinner(val decoy: String, val ttl: Int, val at: Long)

    data class State(
        val ttlWinner: TtlWinner? = null,
        val md5Winner: String? = null,
        val md5WinnerAt: Long = 0L,
        /** MD5-signed decoys that failed here, with when. */
        val md5Failed: Map<String, Long> = emptyMap(),
        /** Next ladder step to try ([FakeSniRecovery.ladderStep]). */
        val cursor: Int = 0,
        val cursorAt: Long = 0L
    ) {
        /** Entries older than [MEMORY_MS] dropped. Pure. */
        fun live(now: Long): State = State(
            ttlWinner = ttlWinner?.takeIf { now - it.at in 0 until MEMORY_MS },
            md5Winner = md5Winner?.takeIf { now - md5WinnerAt in 0 until MEMORY_MS },
            md5WinnerAt = if (md5Winner != null && now - md5WinnerAt in 0 until MEMORY_MS) md5WinnerAt else 0L,
            md5Failed = md5Failed.filterValues { now - it in 0 until MEMORY_MS },
            cursor = if (now - cursorAt in 0 until MEMORY_MS) cursor else 0,
            cursorAt = if (now - cursorAt in 0 until MEMORY_MS) cursorAt else 0L
        )

        fun toJson(): String = JSONObject().apply {
            ttlWinner?.let { put("ttl", JSONObject().put("decoy", it.decoy).put("ttl", it.ttl).put("at", it.at)) }
            md5Winner?.let { put("md5", JSONObject().put("decoy", it).put("at", md5WinnerAt)) }
            if (md5Failed.isNotEmpty()) put("md5Failed", JSONObject(md5Failed.mapValues { it.value }))
            put("cursor", cursor).put("cursorAt", cursorAt)
        }.toString()

        companion object {
            fun parse(raw: String?): State {
                if (raw.isNullOrBlank()) return State()
                return runCatching {
                    val o = JSONObject(raw)
                    val ttl = o.optJSONObject("ttl")?.let { t ->
                        val decoy = t.optString("decoy"); val value = t.optInt("ttl")
                        if (decoy.isBlank() || value !in 1..32) null else TtlWinner(decoy, value, t.optLong("at"))
                    }
                    val md5 = o.optJSONObject("md5")
                    val failed = o.optJSONObject("md5Failed")?.let { f -> f.keys().asSequence().associateWith { f.optLong(it) } }.orEmpty()
                    State(ttl, md5?.optString("decoy")?.takeIf { it.isNotBlank() }, md5?.optLong("at") ?: 0L, failed,
                        Math.floorMod(o.optInt("cursor"), FakeSniRecovery.LADDER_SIZE), o.optLong("cursorAt"))
                }.getOrDefault(State())
            }
        }
    }

    /**
     * What one real test of [variant] on this network changes. Pure.
     * - TTL pass: kept when nothing lower is remembered (the ladder goes up, so this is the lowest tried).
     * - TTL fail: a remembered winner that failed is forgotten.
     * - A tested ladder rung moves the cursor past it, so the next search tries the next rungs.
     * - MD5 pass: kept; MD5 fail: that decoy is not tried again for [MEMORY_MS].
     */
    fun after(state: State, variant: FakeSniRecovery.Variant, passed: Boolean, now: Long): State {
        val s = state.live(now)
        if (variant.md5sig) return if (passed) s.copy(md5Winner = variant.decoy, md5WinnerAt = now, md5Failed = s.md5Failed - variant.decoy)
            else s.copy(md5Winner = s.md5Winner.takeUnless { it == variant.decoy }, md5Failed = s.md5Failed + (variant.decoy to now))
        val winner = s.ttlWinner
        val sameAsWinner = winner != null && winner.decoy == variant.decoy && winner.ttl == variant.ttl
        val nextWinner = when {
            passed && (winner == null || variant.ttl <= winner.ttl) -> TtlWinner(variant.decoy, variant.ttl, now)
            !passed && sameAsWinner -> null
            else -> winner
        }
        val step = if (sameAsWinner) null else FakeSniRecovery.stepOf(variant, s.cursor)
        val cursor = step?.let { Math.floorMod(it + 1, FakeSniRecovery.LADDER_SIZE) } ?: s.cursor
        return s.copy(ttlWinner = nextWinner, cursor = cursor, cursorAt = if (step != null) now else s.cursorAt)
    }

    @Volatile var read: (String) -> String? = { com.example.vpn.connections.BuiltinMethodMemory.read(it) }
    @Volatile var write: (String, String?) -> Unit = { k, v -> com.example.vpn.connections.BuiltinMethodMemory.write(k, v) }

    private val cache = java.util.concurrent.ConcurrentHashMap<String, State>()
    private fun key(network: String) = "fakeSni#$network"

    fun state(network: String?, now: Long = System.currentTimeMillis()): State {
        if (network.isNullOrBlank()) return State()
        val s = cache[network] ?: State.parse(runCatching { read(key(network)) }.getOrNull()).also { cache[network] = it }
        return s.live(now)
    }

    @Synchronized
    fun record(network: String?, variant: FakeSniRecovery.Variant, passed: Boolean, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank()) return
        val next = after(state(network, now), variant, passed, now)
        cache[network] = next
        runCatching { write(key(network), next.toJson()) }
    }

    fun describe(network: String?, now: Long = System.currentTimeMillis()): String {
        val s = state(network, now)
        return listOfNotNull(
            s.md5Winner?.let { "MD5-signed decoy $it worked here" },
            s.ttlWinner?.let { "lowest working decoy TTL here: ${it.ttl} (${it.decoy})" },
            "next ladder step ${s.cursor + 1}/${FakeSniRecovery.LADDER_SIZE}"
        ).joinToString("; ")
    }

    internal fun resetForTest() { cache.clear() }
}
