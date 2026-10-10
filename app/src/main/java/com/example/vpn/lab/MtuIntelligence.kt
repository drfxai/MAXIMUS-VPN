package com.example.vpn.lab

/**
 * Bounded, evidence-driven MTU work. MTU 1500 is not assumed optimal, and a timeout alone never means "MTU".
 *
 * Three values are kept apart and never mixed up:
 *  - TUN_CONFIGURED_MTU: what the VPN interface was created with (settings);
 *  - PATH_MTU_ESTIMATE: what the network path seems to allow (not measured by this build; never invented);
 *  - TRANSPORT_WORKING_MTU: the highest MTU at which a transport (WireGuard on Xray) carried real traffic here.
 *
 * Every MTU change is a temporary derived copy in a transaction (PROPOSED → STAGED → VERIFIED → COMMITTED, or
 * ROLLED_BACK / EXPIRED). The saved config is never changed, and a value is remembered only for one network,
 * engine, transport and address family.
 */
object MtuIntelligence {

    /** Candidate values; the search tries a few of them, never all. */
    val LADDER = listOf(1500, 1480, 1460, 1420, 1400, 1360, 1320, 1280)

    /** IPv6 needs at least this on every link. */
    const val IPV6_MINIMUM = 1280

    /** At most this many MTU tests per candidate and run. */
    const val MAX_TESTS = 4

    enum class Kind { TUN_CONFIGURED_MTU, PATH_MTU_ESTIMATE, TRANSPORT_WORKING_MTU }

    enum class Suspicion(val title: String) {
        NONE("No MTU evidence"),
        MTU_SUSPECTED("MTU suspected"),
        MTU_RELATED_FAILURE_VERIFIED("MTU-related failure verified"),
        NOT_MTU("Lower MTU did not help")
    }

    /** Observations that can point at MTU. A plain timeout is deliberately not one of them. */
    data class Signals(
        val smallWorksLargeStalls: Boolean = false,
        val handshakeOkTrafficStalls: Boolean = false,
        /** A WireGuard path failed while UDP on this network demonstrably answers. */
        val udpAliveButWireGuardStalls: Boolean = false,
        val quicStartsTransferStalls: Boolean = false,
        /** Controlled A/B runs in which a lower MTU passed where the original failed. */
        val lowerMtuRestored: Int = 0,
        /** Controlled A/B runs in which a lower MTU failed too. */
        val lowerMtuNoImprovement: Int = 0
    )

    fun suspicion(s: Signals): Suspicion = when {
        s.lowerMtuRestored >= 2 -> Suspicion.MTU_RELATED_FAILURE_VERIFIED
        s.lowerMtuNoImprovement >= 1 && s.lowerMtuRestored == 0 -> Suspicion.NOT_MTU
        s.smallWorksLargeStalls || s.handshakeOkTrafficStalls || s.udpAliveButWireGuardStalls || s.quicStartsTransferStalls ||
            s.lowerMtuRestored == 1 -> Suspicion.MTU_SUSPECTED
        else -> Suspicion.NONE
    }

    /**
     * A bounded search below [current]: the floor first (one test proves or rules out the hypothesis), then a
     * bisection upwards over [LADDER] for the highest value that still carries traffic. [ipv6] keeps every value
     * at or above [IPV6_MINIMUM].
     */
    class Search(val current: Int, ipv6: Boolean, private val maxTests: Int = MAX_TESTS) {
        private val floor = if (ipv6) IPV6_MINIMUM else LADDER.last()
        private val values = LADDER.filter { it in floor until current }.sorted()
        private val results = linkedMapOf<Int, Boolean>()

        val tested: Map<Int, Boolean> get() = results

        /** The next value to test, or null when done. */
        fun next(): Int? {
            if (values.isEmpty() || results.size >= maxTests) return null
            val low = values.first()
            if (low !in results) return low
            if (results[low] == false) return null
            val working = results.filterValues { it }.keys.maxOrNull() ?: return null
            val failing = results.filterValues { !it }.keys.filter { it > working }.minOrNull() ?: current
            val between = values.filter { it in (working + 1) until failing }
            return between.getOrNull(between.size / 2)
        }

        fun record(mtu: Int, success: Boolean) { results[mtu] = success }

        /** The highest tested value that passed, or null. */
        fun best(): Int? = results.filterValues { it }.keys.maxOrNull()

        /** No lower value helped: the failure is not about MTU. */
        val ruledOut: Boolean get() = results.isNotEmpty() && results.values.none { it }
    }

    enum class TxState { PROPOSED, STAGED, VERIFIED, COMMITTED, ROLLED_BACK, EXPIRED }

    /** MTU evidence is specific to all four; one carrier's value never changes another network. */
    data class Key(val network: String, val engine: String, val transport: String, val addressFamily: String)

    data class Transaction(val key: Key, val mtu: Int, val state: TxState, val createdAt: Long, val expiresAt: Long) {
        private fun to(next: TxState, allowedFrom: Set<TxState>): Transaction =
            if (state in allowedFrom) copy(state = next) else this

        fun stage() = to(TxState.STAGED, setOf(TxState.PROPOSED))
        /** A real request passed with this MTU. */
        fun verify() = to(TxState.VERIFIED, setOf(TxState.STAGED))
        /** Only a verified value is committed (used for this key from now on). */
        fun commit() = to(TxState.COMMITTED, setOf(TxState.VERIFIED))
        /** The candidate failed: dropped at once. */
        fun rollBack() = to(TxState.ROLLED_BACK, setOf(TxState.PROPOSED, TxState.STAGED, TxState.VERIFIED, TxState.COMMITTED))
        fun expireIfDue(now: Long) = if (now >= expiresAt && state != TxState.ROLLED_BACK) copy(state = TxState.EXPIRED) else this
    }

    /** Committed working MTUs per [Key], with expiry; never a global setting. */
    class Cache(private val ttlMs: Long = TTL_MS) {
        private val map = java.util.concurrent.ConcurrentHashMap<Key, Transaction>()

        fun propose(key: Key, mtu: Int, now: Long): Transaction = Transaction(key, mtu, TxState.PROPOSED, now, now + ttlMs)

        fun store(tx: Transaction) {
            when (tx.state) {
                TxState.COMMITTED -> map[tx.key] = tx
                TxState.ROLLED_BACK, TxState.EXPIRED -> map.remove(tx.key, map[tx.key]?.takeIf { it.mtu == tx.mtu })
                else -> Unit
            }
        }

        /** The committed value for exactly this key, or null (expired values are dropped). */
        fun working(key: Key, now: Long): Int? {
            val tx = map[key] ?: return null
            if (tx.expireIfDue(now).state == TxState.EXPIRED) { map.remove(key); return null }
            return tx.mtu
        }

        /** The connection with this MTU failed: forget it so the saved value is used again. */
        fun failed(key: Key) { map.remove(key) }

        fun all(): Map<Key, Int> = map.mapValues { it.value.mtu }
    }

    /** Committed values are rechecked after a day. */
    const val TTL_MS = 24 * 60 * 60_000L

    val cache = Cache()

    /** The WireGuard MTU a config asks for (Xray's default is 1280). */
    fun wireGuardMtu(p: com.example.data.model.VlessProfile): Int =
        runCatching { com.example.vpn.engine.ProfileExtras.read(p).optInt(com.example.vpn.engine.ProfileExtras.WG_MTU, 1280) }.getOrDefault(1280)

    /** A derived copy of a WireGuard config with [mtu]; the saved config is untouched. */
    fun withWireGuardMtu(p: com.example.data.model.VlessProfile, mtu: Int): com.example.data.model.VlessProfile {
        val extras = runCatching { com.example.vpn.engine.ProfileExtras.read(p) }.getOrElse { org.json.JSONObject() }
        extras.put(com.example.vpn.engine.ProfileExtras.WG_MTU, mtu)
        return p.copy(id = "${p.id}#mtu$mtu", extraSettings = extras.toString())
    }
}
