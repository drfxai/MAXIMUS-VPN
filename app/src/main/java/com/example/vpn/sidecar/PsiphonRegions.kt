package com.example.vpn.sidecar

import org.json.JSONObject

/**
 * Psiphon's egress region chosen by measured quality when the profile leaves it on "any" (empty).
 *
 * Build 171 report (Iranian cellular, metered): two direct Psiphon connects each ran 2 × 120 s and ended "server
 * candidates found but no tunnel became ready (last notice: AvailableEgressRegions)". psiphon-tunnel-core (controller.go
 * at the pinned 4d6eb0e8) says a client whose configured EgressRegion is missing from the AvailableEgressRegions notice
 * should stop and change region, because establishment never completes; and a single start may sit silent for its whole
 * window. So:
 *
 *  - a region the user fixed is kept, but when Psiphon reports it unavailable the start stops at once and says which
 *    regions are available ([unavailable]); it is never switched silently;
 *  - on "any", the regions that brought a tunnel up fastest and carried the most verified data on this network
 *    ([PsiphonRegionBook]) go first, each with a bounded budget, then any region; a start that stays silent after
 *    AvailableEgressRegions is cut and up to [MAX_ROTATION] available regions are tried, the whole run inside
 *    [TOTAL_AUTO_MS] instead of 2 × 120 s.
 */
object PsiphonRegionPlan {
    /** The whole automatic-region run, well under the old single 120 s window (and its 120 s TCP retry). */
    const val TOTAL_AUTO_MS = 95_000L
    /** One concrete region's start. */
    const val REGION_STEP_MS = 25_000L
    /** Any region is always given at least this much. */
    const val ANY_MIN_MS = 45_000L
    /** Any region with the region list in and still no server dialed after this long is cut ([anyStalled]). */
    const val ANY_STALL_MS = 40_000L
    /** Below this much budget left no further region is started. */
    const val MIN_STEP_MS = 12_000L
    const val MAX_REMEMBERED = 2
    const val MAX_ROTATION = 2
    /**
     * The user's own direct-Psiphon pick on a network where direct Psiphon failed recently and Psiphon over Lantern is
     * the remembered working path ([com.example.vpn.connections.PsiphonChainMemory]): one bounded try, then a fast
     * failure that offers the chain with one tap (build 173 Irancell: direct 240 s → fail; the chain connected in 18 s).
     */
    const val KNOWN_BLOCKED_MS = 45_000L

    /**
     * Regions tried when nothing is remembered on this network, in this order, if Psiphon lists them: European exits
     * usually answer fastest from Iran. Only an order; any listed region may still be picked.
     */
    val PRIOR = listOf("DE", "NL", "FR", "GB", "FI", "SE", "AT", "CH", "CA", "US")

    /**
     * One Psiphon start. [tcpOnly]: TCP transports only (the old 120 s TCP-only retry, now one step inside the run's
     * budget instead of a second full window after it).
     */
    data class Step(val region: String, val budgetMs: Long, val why: String, val tcpOnly: Boolean = false) {
        val any: Boolean get() = region.isEmpty()
    }

    /**
     * The starts to try, in order. [remembered] is [PsiphonRegionBook.ranked] for this network. [avoid]: regions just
     * left because their tunnel stopped carrying traffic (a pinned restart, see
     * [com.example.vpn.smart.PinnedRecovery]); never led with, and when nothing else is remembered the first other
     * region from [knownAvailable] (Psiphon's last AvailableEgressRegions here) goes first. Pure.
     */
    fun initial(userRegion: String, remembered: List<String>, totalMs: Long = TOTAL_AUTO_MS,
                avoid: Set<String> = emptySet(), knownAvailable: List<String> = emptyList()): List<Step> {
        val fixed = userRegion.trim().uppercase()
        if (fixed.isNotEmpty()) return listOf(Step(fixed, totalMs, "region chosen in the profile"))
        val avoided = avoid.map { it.trim().uppercase() }.toSet()
        val room = maxOf(0L, totalMs - ANY_MIN_MS)
        val slots = minOf(MAX_REMEMBERED, (room / REGION_STEP_MS).toInt())
        val regions = remembered.map { it.trim().uppercase() }.filter { REGION.matches(it) && it !in avoided }.distinct()
            .take(slots)
        var steps = regions.map { Step(it, REGION_STEP_MS, "fastest region measured on this network") }
        if (steps.isEmpty() && avoided.isNotEmpty() && slots > 0) {
            val listed = knownAvailable.map { it.trim().uppercase() }.filter { REGION.matches(it) }.toSet()
            (PRIOR + listed.sorted()).firstOrNull { it in listed && it !in avoided }?.let {
                steps = listOf(Step(it, REGION_STEP_MS, "another region: ${avoided.sorted().joinToString(",")} stopped carrying traffic"))
            }
        }
        return steps + Step("", totalMs - steps.sumOf { it.budgetMs }, "any region")
    }

    /**
     * Whether the old TCP-only retry (a second full start window) may follow the region run. Never after an automatic
     * region run: it already used its whole [TOTAL_AUTO_MS] budget and carries its own TCP-only step ([rotation]), so
     * the total stays inside the cap (build 173: 120 s silence + 120 s TCP retry = 240 s). A fixed region that Psiphon
     * lists as unavailable will not appear over TCP either. Pure.
     */
    fun tcpRetryAllowed(regionRun: Boolean, userRegion: String, failure: String?): Boolean =
        !regionRun || (userRegion.isNotBlank() && failure == null)

    /**
     * The whole budget of one direct Psiphon start: [TOTAL_AUTO_MS] on any region, the engine's own window for a fixed
     * region. The user's own pick ([manual]) always gets that full window, also where direct Psiphon failed recently
     * ([knownBlocked]): build 176 cut such a pick at 40 s ("abandoned early by the connection plan") while its deadline
     * and stage text promised more. A manual pick tests only its engine, fully; the chain over Lantern stays a one-tap
     * offer after it fails. [KNOWN_BLOCKED_MS] is no longer applied here. Pure.
     */
    @Suppress("UNUSED_PARAMETER")
    fun totalBudget(userRegion: String, readyTimeoutMs: Long, manual: Boolean, knownBlocked: Boolean): Long =
        if (userRegion.isBlank()) TOTAL_AUTO_MS else readyTimeoutMs

    /**
     * Any region stayed silent: concrete regions from Psiphon's own [available] list, remembered ones first, then
     * [PRIOR], never one already [tried], inside [leftMs]. Pure.
     */
    fun rotation(available: List<String>, tried: Set<String>, remembered: List<String>, leftMs: Long): List<Step> {
        if (leftMs < MIN_STEP_MS) return emptyList()
        val listed = available.map { it.trim().uppercase() }.filter { REGION.matches(it) }.toSet()
        val order = (remembered.map { it.uppercase() } + PRIOR + listed.sorted())
            .filter { it in listed && it !in tried }.distinct()
        val count = minOf(MAX_ROTATION, order.size, (leftMs / MIN_STEP_MS).toInt())
        if (count <= 0) return emptyList()
        val each = minOf(REGION_STEP_MS, leftMs / count)
        // The second rotated region runs TCP transports only: the old TCP-only retry, inside the same budget.
        return order.take(count).mapIndexed { i, r ->
            if (i == 0) Step(r, each, "available region; any region stayed silent")
            else Step(r, each, "available region over TCP only; any region stayed silent", tcpOnly = true)
        }
    }

    /**
     * The stage text of one step: "up to 25s", and for any region (no region fixed in the profile, the first and
     * only step that [anyStalled] can cut) "up to 70s; next region after 40s if no server is dialed". Pure.
     */
    fun stepWindowText(step: Step, userRegion: String): String {
        val upTo = "up to ${step.budgetMs / 1000}s"
        return if (step.any && userRegion.isBlank() && step.budgetMs > ANY_STALL_MS)
            "$upTo; next region after ${ANY_STALL_MS / 1000}s if no server is dialed" else upTo
    }

    /** Psiphon listed its available regions and [region] is not one of them: that start can never complete. Pure. */
    fun unavailable(region: String, available: List<String>?): Boolean =
        region.isNotEmpty() && !available.isNullOrEmpty() && available.none { it.equals(region, ignoreCase = true) }

    /**
     * Any region listed its regions but dialed no server for [ANY_STALL_MS] ([stage] < 2: no ConnectingServer). Pure.
     * [availableSeen] means a NON-EMPTY region list: build 174 (Irancell) cut the user's own Psiphon pick at 40 s of its
     * 95 s budget on "AvailableEgressRegions regions=0" — Psiphon had no server entries yet and was still fetching its
     * server list, so there was nothing to rotate to and the cut only ended the start early.
     */
    fun anyStalled(elapsedMs: Long, stage: Int, availableSeen: Boolean): Boolean =
        availableSeen && stage < 2 && elapsedMs >= ANY_STALL_MS

    /** What the user's own region failure says: which regions are available here now. */
    fun unavailableMessage(region: String, available: List<String>): String =
        "Psiphon has no usable servers for region $region on this network right now (available: " +
            available.sorted().take(24).joinToString(", ") + "). Pick one of those, or Any region, for this profile; " +
            "traffic is unverified."

    private val REGION = Regex("^[A-Z]{2}$")
}

/**
 * Runs [PsiphonRegionPlan]: one Psiphon start per step, each cut short when Psiphon reports the step's region
 * unavailable or (any region) stays silent after listing its regions, then the available regions in rotation, until a
 * tunnel is ready or the plan's budget is used. [launch] starts Psiphon for a step; the caller stops nothing itself.
 */
class PsiphonRegionRun(private val clock: () -> Long = System::currentTimeMillis) {
    /** One Psiphon start as the run sees it (a [SidecarProcess] in the app, a fake in tests). */
    interface Start {
        fun await(timeoutMs: Long, giveUp: () -> Boolean): Boolean
        fun available(): List<String>?
        fun stage(): Int
        fun connectedRegion(): String?
        fun stop()
    }

    data class Attempt(val step: PsiphonRegionPlan.Step, val ready: Boolean, val elapsedMs: Long,
                       val available: List<String>?, val stage: Int, val connectedRegion: String?) {
        /** The region the tunnel runs in: the one Psiphon reported connecting to, else the step's own. */
        val region: String get() = connectedRegion ?: step.region
    }

    /** [ready] is the running start when one came up; [last] the last start (stopped) otherwise. */
    data class Outcome<S : Start>(val ready: S?, val last: S?, val attempts: List<Attempt>, val failure: String?) {
        val winner: Attempt? get() = attempts.lastOrNull()?.takeIf { it.ready }
    }

    fun <S : Start> run(userRegion: String, remembered: List<String>, totalMs: Long, launch: (PsiphonRegionPlan.Step) -> S,
                        cancelled: () -> Boolean = { false }, avoid: Set<String> = emptySet(),
                        knownAvailable: List<String> = emptyList()): Outcome<S> {
        val fixed = userRegion.trim().uppercase()
        val steps = ArrayDeque(PsiphonRegionPlan.initial(fixed, remembered, totalMs, avoid, knownAvailable))
        val startedAt = clock()
        // A region just left because it stopped carrying traffic is not rotated back to.
        val tried = avoid.map { it.trim().uppercase() }.toMutableSet()
        val attempts = mutableListOf<Attempt>()
        var rotated = false
        var last: S? = null
        while (steps.isNotEmpty()) {
            if (cancelled()) throw kotlinx.coroutines.CancellationException("Psiphon start cancelled")
            val step = steps.removeFirst()
            val left = totalMs - (clock() - startedAt)
            if (attempts.isNotEmpty() && left < PsiphonRegionPlan.MIN_STEP_MS) break
            val budget = if (attempts.isEmpty()) minOf(step.budgetMs, totalMs) else minOf(step.budgetMs, left)
            val start = launch(step)
            last = start
            val stepStart = clock()
            val ready = start.await(budget) {
                PsiphonRegionPlan.unavailable(step.region, start.available()) ||
                    (step.any && fixed.isEmpty() && !rotated &&
                        PsiphonRegionPlan.anyStalled(clock() - stepStart, start.stage(), !start.available().isNullOrEmpty()))
            }
            val attempt = Attempt(step, ready, clock() - stepStart, start.available(), start.stage(), start.connectedRegion())
            attempts += attempt
            if (ready) return Outcome(start, start, attempts, null)
            start.stop()
            if (cancelled()) throw kotlinx.coroutines.CancellationException("Psiphon start cancelled")
            tried += step.region
            if (fixed.isNotEmpty() && PsiphonRegionPlan.unavailable(fixed, attempt.available))
                return Outcome(null, start, attempts, PsiphonRegionPlan.unavailableMessage(fixed, attempt.available.orEmpty()))
            if (step.any && fixed.isEmpty() && !rotated && !attempt.available.isNullOrEmpty()) {
                rotated = true
                steps.addAll(PsiphonRegionPlan.rotation(attempt.available, tried, remembered, totalMs - (clock() - startedAt)))
            }
        }
        return Outcome(null, last, attempts, null)
    }
}

/**
 * How each Psiphon egress region did on each network ([com.example.vpn.smart.NetworkIdentity] key): how fast its tunnel
 * came up, the download rate verified through it, and when it last failed. Only two-letter region codes, times and
 * rates are kept. [ranked] orders the regions for [PsiphonRegionPlan.initial].
 */
class PsiphonRegionBook(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Sample(val readyMs: Long? = null, val bytesPerSecond: Long? = null, val at: Long = 0, val failedAt: Long? = null)

    private val entries: MutableMap<String, MutableMap<String, Sample>> by lazy { parse(load()) }

    @Synchronized
    fun recordReady(network: String, region: String, readyMs: Long) = update(network, region) {
        it.copy(readyMs = readyMs, at = clock(), failedAt = null)
    }

    @Synchronized
    fun recordRate(network: String, region: String, bytesPerSecond: Long) = update(network, region) {
        it.copy(bytesPerSecond = bytesPerSecond, at = clock())
    }

    @Synchronized
    fun recordFailure(network: String, region: String) = update(network, region) { it.copy(failedAt = clock()) }

    /**
     * Regions that brought a tunnel up on [network], best first: verified rate, then quicker start, then most recent.
     * A region that failed after its last success, within [FAILURE_HOLD_MS], is left out.
     */
    @Synchronized
    fun ranked(network: String): List<String> {
        val now = clock()
        return entries[network].orEmpty().filter { (_, s) ->
            s.readyMs != null && now - s.at in 0 until TTL_MS &&
                (s.failedAt == null || s.failedAt < s.at || now - s.failedAt !in 0 until FAILURE_HOLD_MS)
        }.entries.sortedWith(compareByDescending<Map.Entry<String, Sample>> { it.value.bytesPerSecond ?: -1L }
            .thenBy { it.value.readyMs ?: Long.MAX_VALUE }
            .thenByDescending { it.value.at })
            .map { it.key }
    }

    @Synchronized
    fun sample(network: String, region: String): Sample? = entries[network]?.get(region.uppercase())

    @Synchronized
    fun clear() {
        entries.clear()
        persist()
    }

    private fun update(network: String, region: String, change: (Sample) -> Sample) {
        val code = region.trim().uppercase()
        if (network.isBlank() || !Regex("^[A-Z]{2}$").matches(code)) return
        val n = entries.getOrPut(network) { linkedMapOf() }
        n[code] = change(n[code] ?: Sample(at = clock()))
        while (n.size > MAX_PER_NETWORK) n.remove(n.entries.minByOrNull { it.value.at }!!.key)
        val now = clock()
        entries.values.forEach { m -> m.entries.removeAll { now - maxOf(it.value.at, it.value.failedAt ?: 0) !in 0 until TTL_MS } }
        entries.entries.removeAll { it.value.isEmpty() }
        entries.entries.sortedByDescending { e -> e.value.values.maxOf { it.at } }.drop(MAX_NETWORKS).forEach { entries.remove(it.key) }
        persist()
    }

    private fun persist() {
        val n = JSONObject()
        entries.forEach { (network, regions) ->
            val o = JSONObject()
            regions.forEach { (r, s) ->
                o.put(r, JSONObject().put("at", s.at).apply {
                    s.readyMs?.let { put("ms", it) }
                    s.bytesPerSecond?.let { put("r", it) }
                    s.failedAt?.let { put("f", it) }
                })
            }
            n.put(network, o)
        }
        runCatching { save(JSONObject().put("schema", SCHEMA).put("networks", n).toString()) }
    }

    private fun parse(raw: String?): MutableMap<String, MutableMap<String, Sample>> {
        val out = linkedMapOf<String, MutableMap<String, Sample>>()
        val root = runCatching { JSONObject(raw ?: return out) }.getOrNull() ?: return out
        if (root.optInt("schema") != SCHEMA) return out
        val networks = root.optJSONObject("networks") ?: return out
        networks.keys().forEach { network ->
            val o = networks.optJSONObject(network) ?: return@forEach
            val m = linkedMapOf<String, Sample>()
            o.keys().forEach { r ->
                val s = o.optJSONObject(r) ?: return@forEach
                m[r] = Sample(if (s.has("ms")) s.optLong("ms") else null, if (s.has("r")) s.optLong("r") else null,
                    s.optLong("at"), if (s.has("f")) s.optLong("f") else null)
            }
            if (m.isNotEmpty()) out[network] = m
        }
        return out
    }

    companion object {
        const val TTL_MS = 3 * 24 * 60 * 60_000L
        /** A region that failed after it last worked is skipped for this long on that network. */
        const val FAILURE_HOLD_MS = 60 * 60_000L
        const val MAX_PER_NETWORK = 12
        const val MAX_NETWORKS = 8
        private const val SCHEMA = 1
    }
}
