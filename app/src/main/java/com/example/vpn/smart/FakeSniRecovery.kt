package com.example.vpn.smart

import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.NetworkFirewalls
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.ByedpiSidecar
import com.example.vpn.sidecar.Sidecars

/**
 * Bounded fake-SNI alternatives to existing TLS profiles, for Smart Connect's recovery only (a manual pick never
 * gets here). They never replace credentials, SNI, Host or trust: Xray still does verified TLS, ByeDPI only
 * sends a TTL-limited (or MD5-signed) decoy ClientHello before the real one on the same TCP connection.
 *
 * "Whitelist mode" (SNI-Spoofing review, 2026-11-26): under the SNI-whitelist regime only whitelisted names
 * pass, so the decoys come from patterniha/SNI-Spoofing's config and issues ([DECOYS]). The decoy TTL must
 * reach the DPI but die before the server, and that differs by network, so the recovery walks a TTL ladder
 * ([TTL_LADDER]) a few rungs per search, through the real tunnel, and [FakeSniMemory] keeps the lowest TTL
 * (and decoy) that carried traffic on each network for [FakeSniMemory.MEMORY_MS]. When the kernel supports
 * TCP_MD5SIG ([com.example.vpn.sidecar.Md5SigSupport]) an MD5-signed decoy is tried too: servers drop it,
 * so it needs no TTL calibration.
 *
 * Order by firewall ([NetworkFirewalls.detect]): earlier and more origins on Irancell (where the field reports
 * say fake-SNI works for some users); last and a single test on Hamrah-e-Aval (reported to defeat even the
 * stronger out-of-window fake). Total ByeDPI time per search is bounded by [Budget].
 */
object FakeSniRecovery {
    /** Whitelisted decoys first (SNI-Spoofing's default and the names its users report), the old default last. */
    val DECOYS = listOf("auth.vercel.com", "security.vercel.com", "www.hcaptcha.com", "www.microsoft.com")

    /** Lowest first: the first rung that carries traffic is the lowest working TTL among those tried. */
    val TTL_LADDER = listOf(2, 3, 4, 5, 6, 8, 10, 12)

    /** One pass over every (TTL, decoy) pair. */
    val LADDER_SIZE = TTL_LADDER.size * DECOYS.size

    /** An MD5-signed decoy is dropped by the server's TCP stack, so it may travel far: the carrier's largest TTL. */
    const val MD5SIG_TTL = 32

    const val ID_PREFIX = "recovery-fake-"

    /** One fake-SNI test: ByeDPI is a local native program, it starts in well under a second. */
    const val TEST_WINDOW_MS = 15_000L

    /** Every fake-SNI test of one search together (metered networks also keep RecoveryEngineCaps' 10 s ByeDPI cap). */
    const val TOTAL_BUDGET_MS = 30_000L

    data class Variant(val decoy: String, val ttl: Int, val md5sig: Boolean = false)

    /** How many saved TLS profiles and how many variants per profile one search may try. */
    data class Limits(val origins: Int, val variants: Int)

    fun limits(firewall: NetworkFirewalls.Firewall): Limits = when (firewall) {
        NetworkFirewalls.Firewall.IRANCELL -> Limits(origins = 3, variants = 4)
        NetworkFirewalls.Firewall.MCI -> Limits(origins = 1, variants = 1)
        else -> Limits(origins = 2, variants = 3)
    }

    /**
     * Multiplies the recovery planner's probability for a fake-SNI candidate on [firewall]: above the other engine
     * programs on Irancell, below everything else on Hamrah-e-Aval (still above the planner's floor for one test).
     */
    fun firewallFactor(firewall: NetworkFirewalls.Firewall): Double = when (firewall) {
        NetworkFirewalls.Firewall.IRANCELL -> 1.5
        NetworkFirewalls.Firewall.MCI -> 0.3
        else -> 1.0
    }

    /**
     * Step [index] of the ladder, diagonal over TTL × decoy: each pass of [TTL_LADDER.size] steps covers every TTL
     * once with the decoys rotating, and the next pass shifts the decoys, so [LADDER_SIZE] steps try every pair.
     */
    fun ladderStep(index: Int): Variant {
        val i = Math.floorMod(index, LADDER_SIZE)
        val rung = i % TTL_LADDER.size
        val pass = i / TTL_LADDER.size
        return Variant(DECOYS[(rung + pass) % DECOYS.size], TTL_LADDER[rung])
    }

    /** The first ladder step at or after [cursor] (within one full ladder) that is [variant]; null if none. */
    fun stepOf(variant: Variant, cursor: Int): Int? {
        if (variant.md5sig) return null
        return (0 until LADDER_SIZE).map { cursor + it }.firstOrNull { ladderStep(it) == variant }?.let { Math.floorMod(it, LADDER_SIZE) }
    }

    /**
     * The variants one search tries on this network, in order: the remembered MD5 winner, the remembered lowest TTL,
     * an untried MD5-signed decoy when the kernel supports it, then ladder rungs from where the last search stopped.
     * At most [count]. Pure.
     */
    fun plan(memory: FakeSniMemory.State, md5sigSupported: Boolean, count: Int): List<Variant> {
        if (count <= 0) return emptyList()
        val out = LinkedHashSet<Variant>()
        if (md5sigSupported) memory.md5Winner?.let { out += Variant(it, MD5SIG_TTL, true) }
        memory.ttlWinner?.let { out += Variant(it.decoy, it.ttl) }
        if (md5sigSupported && memory.md5Winner == null) {
            DECOYS.firstOrNull { it !in memory.md5Failed }?.let { out += Variant(it, MD5SIG_TTL, true) }
        }
        var step = memory.cursor
        while (out.size < count && step < memory.cursor + LADDER_SIZE) {
            out += ladderStep(step)
            step++
        }
        return out.take(count)
    }

    /** True for a profile made here (and only here). */
    fun isCandidate(profile: VlessProfile): Boolean = profile.id.startsWith(ID_PREFIX) && ByedpiSidecar.handles(profile)

    /** The decoy, TTL and MD5 flag a candidate carries; null for any other profile. */
    fun variantOf(profile: VlessProfile): Variant? {
        if (!isCandidate(profile)) return null
        val e = ProfileExtras.read(profile)
        val decoy = e.optString("fakeSni").takeIf { it.isNotBlank() } ?: return null
        return Variant(decoy, e.optInt("fakeTtl"), e.optBoolean(ByedpiSidecar.KEY_MD5SIG))
    }

    fun candidates(
        saved: List<VlessProfile>,
        resolve: (VlessProfile) -> VlessProfile,
        firewall: NetworkFirewalls.Firewall = NetworkFirewalls.Firewall.OTHER,
        memory: FakeSniMemory.State = FakeSniMemory.State(),
        md5sigSupported: Boolean = false
    ): List<VlessProfile> {
        val limits = limits(firewall)
        val variants = plan(memory, md5sigSupported, limits.variants)
        if (variants.isEmpty()) return emptyList()
        return saved.asSequence().filter {
            it.security == "tls" && !it.allowInsecure && it.finalMask.isBlank() &&
                !Sidecars.isEngineProfile(it) && it.rawConfig.isBlank()
        }.distinctBy { it.address to it.sni }.take(limits.origins).flatMap { original ->
            val resolved = try { resolve(original) } catch (e: Exception) {
                if (e is InterruptedException || e is kotlinx.coroutines.CancellationException) throw e
                return@flatMap emptySequence()
            }
            variants.asSequence().mapNotNull { v ->
                runCatching { ByedpiSidecar.enable(resolved, v.decoy, v.ttl, v.md5sig) }.getOrNull()?.let {
                    val label = if (v.md5sig) "MD5" else "TTL ${v.ttl}"
                    it.copy(id = "$ID_PREFIX${it.effectiveFingerprint}", name = "${original.name} · Fake SNI ${v.decoy} $label")
                }
            }
        }.toList()
    }

    /** Time left for fake-SNI tests in one search. Not thread-safe: one per search. */
    class Budget(private val totalMs: Long = TOTAL_BUDGET_MS, private val testMs: Long = TEST_WINDOW_MS) {
        var spent = 0L
            private set

        /** The window for the next fake-SNI test: [normalMs] cut to the test and search caps; null = used up. */
        fun window(normalMs: Long): Long? {
            val left = totalMs - spent
            if (left < com.example.vpn.smart.RecoveryEngineCaps.BYEDPI_MIN_WINDOW_MS) return null
            return minOf(normalMs, testMs, left)
        }

        fun charge(ms: Long) { spent += ms.coerceAtLeast(0L) }
    }
}
