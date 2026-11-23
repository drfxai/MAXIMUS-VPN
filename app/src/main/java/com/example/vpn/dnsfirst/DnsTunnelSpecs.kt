package com.example.vpn.dnsfirst

import com.example.data.model.VlessProfile
import com.example.vpn.dns.VerifiedResolverPool
import com.example.vpn.sidecar.CottenDnsSidecar
import com.example.vpn.sidecar.DnsttSidecar
import com.example.vpn.sidecar.SlipstreamSidecar
import com.example.vpn.sidecar.Sidecars
import java.security.MessageDigest

/**
 * Between saved DNS tunnel profiles and the pure ladder: [spec] reads what the ladder needs, [apply] builds the
 * derived copy a rung describes. The saved profile is never changed; copies only live for a test or a connect.
 */
object DnsTunnelSpecs {
    val ENGINE_IDS = mapOf(
        DnsttSidecar.ID to EngineKind.DNSTT,
        SlipstreamSidecar.ID to EngineKind.SLIPSTREAM,
        CottenDnsSidecar.ID to EngineKind.COTTEN
    )

    fun kindOf(profile: VlessProfile): EngineKind? =
        runCatching { Sidecars.forProfile(profile)?.id }.getOrNull()?.let { ENGINE_IDS[it] }

    fun settingsHash(profile: VlessProfile): String =
        MessageDigest.getInstance("SHA-256").digest((profile.extraSettings + "|" + profile.uuid + "|" + profile.address).toByteArray())
            .take(12).joinToString("") { "%02x".format(it) }

    fun spec(profile: VlessProfile): DnsTunnelLadder.Spec? {
        val kind = kindOf(profile) ?: return null
        return runCatching {
            when (kind) {
                EngineKind.DNSTT -> DnsttSidecar.settings(profile).let { s ->
                    DnsTunnelLadder.Spec(profile.id, kind, s.domain, s.resolvers.map {
                        if (it.kind == DnsttSidecar.ResolverKind.DOH) it.address else VerifiedResolverPool.hostOf(it.address)
                    }, settingsHash(profile))
                }
                EngineKind.SLIPSTREAM -> SlipstreamSidecar.settings(profile).let { s ->
                    DnsTunnelLadder.Spec(profile.id, kind, s.domain, s.resolvers.map(VerifiedResolverPool::hostOf), settingsHash(profile))
                }
                EngineKind.COTTEN -> CottenDnsSidecar.settings(profile).let { s ->
                    DnsTunnelLadder.Spec(profile.id, kind, s.domain, s.resolvers.map(VerifiedResolverPool::hostOf), settingsHash(profile))
                }
            }
        }.getOrNull()
    }

    /** [profile] as [rung] describes it; [profile] itself for an as-configured rung; null when it cannot be built. */
    fun apply(profile: VlessProfile, rung: DnsTunnelLadder.Rung): VlessProfile? {
        if (rung.via == DnsTunnelLadder.Via.AS_CONFIGURED) return profile
        if (rung.resolvers.isEmpty()) return null
        return runCatching {
            when (rung.kind) {
                EngineKind.DNSTT -> {
                    val s = DnsttSidecar.settings(profile)
                    val resolvers = when (rung.via) {
                        DnsTunnelLadder.Via.DOH -> rung.resolvers.map { DnsttSidecar.Resolver(DnsttSidecar.ResolverKind.DOH, it) }
                        DnsTunnelLadder.Via.UDP -> rung.resolvers.map { DnsttSidecar.Resolver(DnsttSidecar.ResolverKind.UDP, "$it:53") }
                        else -> return null
                    }.take(DnsttSidecar.MAX_RESOLVERS)
                    val qps = (rung.qpsPerResolver ?: s.qps).coerceIn(1, DnsttSidecar.MAX_QPS)
                    DnsttSidecar.withSettings(profile, s.copy(resolvers = resolvers, qps = qps, perResolver = resolvers.size > 1 && rung.qpsPerResolver != null))
                }
                EngineKind.SLIPSTREAM -> {
                    if (rung.via != DnsTunnelLadder.Via.UDP) return null
                    val s = SlipstreamSidecar.settings(profile)
                    SlipstreamSidecar.withSettings(profile, s.copy(resolvers = rung.resolvers.take(SlipstreamSidecar.MAX_RESOLVERS)))
                }
                EngineKind.COTTEN -> {
                    val s = CottenDnsSidecar.settings(profile)
                    val transport = when (rung.via) {
                        DnsTunnelLadder.Via.UDP -> "udp"
                        DnsTunnelLadder.Via.TCP -> "tcp"
                        else -> return null
                    }
                    // Cotten's copy changes its fingerprint; keep the saved profile's identity for memory and UI.
                    CottenDnsSidecar.withSettings(profile, s.copy(resolvers = rung.resolvers.map { "$it:53" }, transport = transport))
                        .copy(id = profile.id, name = profile.name)
                }
            }
        }.getOrNull()
    }
}

/**
 * Connect-time hook: DnsTunnelResolverOrder asks it for the variant the ladder verified for this profile on the
 * current network before it reorders resolvers. Installed by the automation start-up; absent in tests and in
 * processes that never ran the ladder, where nothing changes.
 */
object DnsLadderHook {
    @Volatile
    var lookup: ((VlessProfile) -> VlessProfile?)? = null

    fun preferred(profile: VlessProfile): VlessProfile? = runCatching { lookup?.invoke(profile) }.getOrNull()

    /** The lookup for one memory and network source; pure apart from [DnsTunnelSpecs]. */
    fun lookupFor(memory: DnsLadderMemory, network: () -> String): (VlessProfile) -> VlessProfile? = { profile ->
        DnsTunnelSpecs.spec(profile)?.let { spec ->
            memory.winner(network(), spec.profileId, spec.settingsHash)?.let { w ->
                DnsTunnelSpecs.apply(profile, DnsTunnelLadder.Rung(spec.profileId, spec.kind, "remembered", w.via, w.resolvers, w.qpsPerResolver))
            }
        }
    }
}
