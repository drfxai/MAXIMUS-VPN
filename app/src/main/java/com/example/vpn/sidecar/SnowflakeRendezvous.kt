package com.example.vpn.sidecar

/**
 * Snowflake's rendezvous ladder (Build 181). Build 180 shipped only the CDN77 domain-fronted rendezvous; on Irancell
 * Snowflake connected in 131 s. Tor's anti-censorship team recommends the AMP cache rendezvous (fronted by Google,
 * which "tends to be allowlisted") for Iran, with Amazon SQS and a Netlify-fronted broker as further ways in
 * (Tor forum, "Tor not working in Iran", 2026-01-29; anti-censorship notes 2026-02-05).
 *
 * Each rendezvous has the same two Snowflake bridges (fingerprints [FP_A], [FP_B]) behind its own broker channel. A start
 * races two rendezvous: the first one's [FP_A] line and the second one's [FP_B] line, so the two Bridge lines never share a
 * fingerprint or an address and Tor tries both at once. The order is [LADDER]: the one that last worked on this network
 * first; after a failed start the next pair in the ladder. Which rendezvous won is read from Tor's notice
 * "new bridge descriptor … $<fingerprint>" ([winnerOf]).
 *
 * The bridge lines are Tor's own built-in ones, verbatim (the SQS credentials are the public, send-only ones Tor ships
 * in its built-in bridge lines; they are not a secret of this app).
 */
object SnowflakeRendezvous {
    const val FP_A = "2B280B23E1107BB62ABFC40DDCC8824814F80A72"
    const val FP_B = "8838024498816A039FCBBAB14E6F40A0843051FA"

    private const val ICE = "stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478," +
        "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
        "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443"
    /** The SQS lines omit stun.mixvoip.com, as Tor's do. */
    private const val ICE_SQS = "stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478," +
        "stun:stun.voipgate.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443"
    private const val TAIL = "utls-imitate=hellorandomizedalpn"

    enum class Method(val key: String, val label: String, private val addressA: String, private val addressB: String,
                      private val channel: String, private val ice: String) {
        AMP("amp", "AMP cache (Google)", "192.0.2.5:80", "192.0.2.6:80",
            "url=https://snowflake-broker.torproject.net/ ampcache=https://cdn.ampproject.org/ front=www.google.com", ICE),
        SQS("sqs", "Amazon SQS", "192.0.2.5:80", "192.0.2.6:80",
            "sqsqueue=https://sqs.us-east-1.amazonaws.com/893902434899/snowflake-broker " +
                "sqscreds=eyJhd3MtYWNjZXNzLWtleS1pZCI6IkFLSUE1QUlGNFdKSlhTN1lIRUczIiwiYXdzLXNlY3JldC1rZXkiOiI3U0RNc0pBNHM1RitXZWJ1L3pMOHZrMFFXV0lsa1c2Y1dOZlVsQ0tRIn0=",
            ICE_SQS),
        NETLIFY("netlify", "Netlify", "192.0.2.3:80", "192.0.2.4:80",
            "url=https://voluble-torrone-fc39bf.netlify.app/ fronts=vuejs.org", ICE),
        CDN77("cdn77", "CDN77", "192.0.2.3:80", "192.0.2.4:80",
            "url=https://1098762253.rsc.cdn77.org/ fronts=www.cdn77.com,www.phpmyadmin.net", ICE);

        fun lineA(): String = "snowflake $addressA $FP_A fingerprint=$FP_A $channel ice=$ice $TAIL"
        fun lineB(): String = "snowflake $addressB $FP_B fingerprint=$FP_B $channel ice=$ice $TAIL"
        fun lines(): List<String> = listOf(lineA(), lineB())

        companion object {
            fun of(key: String?): Method? = entries.firstOrNull { it.key == key }
        }
    }

    /** AMP first (Google-fronted, Tor's advice for Iran), then SQS, Netlify, and CDN77 (the only one before Build 181). */
    val LADDER = listOf(Method.AMP, Method.SQS, Method.NETLIFY, Method.CDN77)

    /** How long a winner or a failure steers the next start on a network. */
    const val MEMORY_MS = 3 * 24 * 60 * 60_000L

    /**
     * The two rendezvous a start races. [winner] (worked here before) goes first with the ladder's next one; otherwise the
     * ladder from [failedPairs] × 2 (each failed start moves on by one pair, wrapping). Pure.
     */
    fun pair(winner: Method?, failedPairs: Int): Pair<Method, Method> {
        if (winner != null) return winner to LADDER[(LADDER.indexOf(winner) + 1) % LADDER.size]
        val start = (failedPairs.coerceAtLeast(0) * 2) % LADDER.size
        return LADDER[start] to LADDER[(start + 1) % LADDER.size]
    }

    /** The two Bridge lines for [pair]: the first rendezvous's [FP_A] line, the second's [FP_B] line. Pure. */
    fun lines(pair: Pair<Method, Method>): List<String> = listOf(pair.first.lineA(), pair.second.lineB())

    /** The rendezvous that won, from a Tor notice naming a new bridge descriptor's fingerprint; null otherwise. Pure. */
    fun winnerOf(notice: String, pair: Pair<Method, Method>): Method? {
        if (!notice.contains("new bridge descriptor", ignoreCase = true)) return null
        return when {
            notice.contains(FP_A, ignoreCase = true) -> pair.first
            notice.contains(FP_B, ignoreCase = true) -> pair.second
            else -> null
        }
    }

    // ---- per-network memory (BuiltinMethodMemory) ----------------------------------------------------------------

    private fun winnerKey(network: String) = "snowflakeWinner#$network"
    private fun failedKey(network: String) = "snowflakeFailed#$network"

    /** "key@time". Pure. */
    internal fun decode(value: String?, now: Long): String? {
        val key = value?.substringBefore('@') ?: return null
        val at = value.substringAfter('@', "").toLongOrNull() ?: return null
        return key.takeIf { now - at in 0 until MEMORY_MS }
    }

    fun winner(network: String?, now: Long = System.currentTimeMillis()): Method? = network?.takeIf { it.isNotBlank() }?.let { n ->
        runCatching { Method.of(decode(com.example.vpn.connections.BuiltinMethodMemory.read(winnerKey(n)), now)) }.getOrNull()
    }

    fun failedPairs(network: String?, now: Long = System.currentTimeMillis()): Int = network?.takeIf { it.isNotBlank() }?.let { n ->
        runCatching { decode(com.example.vpn.connections.BuiltinMethodMemory.read(failedKey(n)), now)?.toIntOrNull() }.getOrNull()
    } ?: 0

    fun recordWinner(network: String?, method: Method, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank()) return
        runCatching {
            com.example.vpn.connections.BuiltinMethodMemory.write(winnerKey(network), "${method.key}@$now")
            com.example.vpn.connections.BuiltinMethodMemory.write(failedKey(network), null)
        }
    }

    /** A start where neither rendezvous brought Tor up: the winner is forgotten and the next start moves on a pair. */
    fun recordFailure(network: String?, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank()) return
        runCatching {
            val memory = com.example.vpn.connections.BuiltinMethodMemory
            memory.write(winnerKey(network), null)
            memory.write(failedKey(network), "${failedPairs(network, now) + 1}@$now")
        }
    }

    /** The pair the running built-in Snowflake start uses; null for a user's own bridges or a chained Tor. */
    @Volatile var active: Pair<Method, Method>? = null

    @Volatile private var settled = false

    /** Feeds one Tor notice of the running start; the first bridge descriptor names the winning rendezvous. */
    fun observe(notice: String) {
        val pair = active ?: return
        if (settled) return
        val won = winnerOf(notice, pair) ?: return
        settled = true
        val network = runCatching { com.example.vpn.connections.BuiltinMethodMemory.network() }.getOrNull()
        recordWinner(network, won)
        runCatching { com.example.xray.XrayLogManager.i("TOR", "Snowflake rendezvous won: ${won.label}") }
    }

    /** The running start ended: [ready] false with no winner moves the next start on to the next pair. */
    fun finished(ready: Boolean) {
        val pair = active ?: return
        if (!ready && !settled) {
            val network = runCatching { com.example.vpn.connections.BuiltinMethodMemory.network() }.getOrNull()
            recordFailure(network)
            runCatching { com.example.xray.XrayLogManager.i("TOR", "Snowflake rendezvous failed: ${pair.first.label} + ${pair.second.label}") }
        }
    }

    /** A new start begins: forget the previous start's verdict. */
    fun reset() { settled = false }

    /** The pair for the phone's current network. */
    fun current(now: Long = System.currentTimeMillis()): Pair<Method, Method> {
        val network = runCatching { com.example.vpn.connections.BuiltinMethodMemory.network() }.getOrNull()
        return pair(winner(network, now), failedPairs(network, now))
    }
}
