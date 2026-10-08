package com.example.vpn.connectivity

import com.example.data.model.VlessProfile
import com.example.vpn.stealth.ConnectionKind

/**
 * The decisions behind failover, kept apart from the watchdog so they can be tested:
 *
 * - **Backups**: Backup A and Backup B are the best candidates on failure domains (CDN, network, registered
 *   domain; see [DiversitySelector.traitsOf]) different from the primary's and from each other's, so one
 *   blocked CDN range or network cannot take all three down. Kind of connection is a tie-breaker.
 * - **Hysteresis**: several failed checks in a row before leaving a config (WatchPolicy), several passed
 *   checks over a minimum time before returning to the primary, and a minimum stay on any config.
 * - **Backoff**: each switch within [WINDOW_MS] doubles the wait before the next one, up to [MAX_COOLDOWN_MS].
 * - **No oscillation**: a config left because it failed is not switched back to until its penalty expires;
 *   leaving it again doubles the penalty.
 */
class SmartFailoverPolicy(private val clock: () -> Long = System::currentTimeMillis) {
    data class Backup(val profile: VlessProfile, val failureDomain: String, val kind: String)
    data class Plan(val primary: VlessProfile, val backupA: Backup?, val backupB: Backup?)

    private val switches = ArrayDeque<Long>()
    private var lastSwitchAt = 0L
    /** Config fingerprint → (penalty until, times left). */
    private val abandoned = HashMap<String, Pair<Long, Int>>()
    private var recoveryPasses = 0
    private var firstRecoveryPassAt = 0L

    /** The wait before another switch is allowed, given the switches in the last [WINDOW_MS]. */
    @Synchronized
    fun cooldownMs(): Long {
        prune()
        if (switches.isEmpty()) return 0L
        val exp = (switches.size - 1).coerceAtMost(16)
        return (BASE_COOLDOWN_MS shl exp).coerceAtMost(MAX_COOLDOWN_MS)
    }

    @Synchronized
    fun mayFailover(): Boolean = lastSwitchAt == 0L || clock() - lastSwitchAt >= cooldownMs()

    /** A switch away from [from] happened (or is about to): starts its penalty and the next cooldown. */
    @Synchronized
    fun recordSwitch(from: VlessProfile?, failed: Boolean = true) {
        val now = clock()
        prune()
        switches.addLast(now)
        lastSwitchAt = now
        recoveryPasses = 0
        if (from != null && failed) {
            val times = (abandoned[from.effectiveFingerprint]?.second ?: 0) + 1
            val penalty = (BASE_PENALTY_MS shl (times - 1).coerceAtMost(16)).coerceAtMost(MAX_PENALTY_MS)
            abandoned[from.effectiveFingerprint] = (now + penalty) to times
        }
    }

    /** Whether [p] was left after failing and its penalty still runs. */
    @Synchronized
    fun isPenalized(p: VlessProfile): Boolean = abandoned[p.effectiveFingerprint]?.let { clock() < it.first } ?: false

    /** Candidates without a running penalty; all of them when every one has one. */
    fun withoutPenalized(candidates: List<VlessProfile>): List<VlessProfile> = candidates.filter { !isPenalized(it) }.ifEmpty { candidates }

    /** A check of the primary while running on a backup; true when it is time to return to the primary. */
    @Synchronized
    fun recordPrimaryCheck(primaryOk: Boolean): Boolean {
        val now = clock()
        if (!primaryOk) { recoveryPasses = 0; return false }
        if (recoveryPasses == 0) firstRecoveryPassAt = now
        recoveryPasses++
        return recoveryPasses >= RECOVERY_PASSES && now - firstRecoveryPassAt >= RECOVERY_SPAN_MS &&
            now - lastSwitchAt >= MIN_DWELL_MS
    }

    private fun prune() {
        val now = clock()
        while (switches.isNotEmpty() && now - switches.first() > WINDOW_MS) switches.removeFirst()
        abandoned.entries.removeAll { now - it.value.first > WINDOW_MS * 6 }
    }

    companion object {
        const val BASE_COOLDOWN_MS = 20_000L
        const val MAX_COOLDOWN_MS = 5 * 60_000L
        const val WINDOW_MS = 10 * 60_000L
        const val BASE_PENALTY_MS = 2 * 60_000L
        const val MAX_PENALTY_MS = 30 * 60_000L
        /** Passed checks of the primary, over at least [RECOVERY_SPAN_MS], before returning to it. */
        const val RECOVERY_PASSES = 3
        const val RECOVERY_SPAN_MS = 60_000L
        /** Least time on a config after a switch before a voluntary switch back. */
        const val MIN_DWELL_MS = 2 * 60_000L

        /**
         * Backup A and B for [primary] from [candidates] (already eligible), best [score] first: A on a
         * failure domain other than the primary's, B on one other than both. When no such candidate
         * exists the slot stays empty rather than holding a backup that fails together with the primary.
         */
        fun plan(
            primary: VlessProfile,
            candidates: List<VlessProfile>,
            score: (VlessProfile) -> Double,
            traits: (VlessProfile) -> DiversitySelector.Traits = DiversitySelector::traitsOf
        ): Plan {
            val pDomain = traits(primary).failureDomain
            val pKind = ConnectionKind.of(primary)
            val ranked = candidates.filter { it.effectiveFingerprint != primary.effectiveFingerprint }
                .sortedWith(compareBy<VlessProfile>({ -score(it) }, { it.id }))
            fun pick(excludedDomains: Set<String>, avoidKind: Set<String>): VlessProfile? {
                val pool = ranked.filter { traits(it).failureDomain !in excludedDomains }
                return pool.firstOrNull { ConnectionKind.of(it) !in avoidKind } ?: pool.firstOrNull()
            }
            val a = pick(setOf(pDomain), setOf(pKind))
            val b = a?.let { pick(setOf(pDomain, traits(it).failureDomain), setOf(pKind, ConnectionKind.of(it))) }
            fun backup(p: VlessProfile?) = p?.let { Backup(it, traits(it).failureDomain, ConnectionKind.of(it)) }
            return Plan(primary, backup(a), backup(b))
        }

        /** Candidates off [failed]'s failure domain first; all of them when none is. */
        fun preferOtherDomains(candidates: List<VlessProfile>, failed: VlessProfile,
                               traits: (VlessProfile) -> DiversitySelector.Traits = DiversitySelector::traitsOf): List<VlessProfile> {
            val domain = traits(failed).failureDomain
            return candidates.filter { traits(it).failureDomain != domain }.ifEmpty { candidates }
        }
    }
}
