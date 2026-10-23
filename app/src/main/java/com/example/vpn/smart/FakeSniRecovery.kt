package com.example.vpn.smart

import com.example.data.model.VlessProfile
import com.example.vpn.sidecar.ByedpiSidecar
import com.example.vpn.sidecar.Sidecars

/** Bounded alternatives to existing TLS profiles; never replace credentials, SNI, Host or trust. */
object FakeSniRecovery {
    fun candidates(saved: List<VlessProfile>, resolve: (VlessProfile) -> VlessProfile): List<VlessProfile> =
        saved.asSequence().filter {
            it.security == "tls" && !it.allowInsecure && it.finalMask.isBlank() &&
                !Sidecars.isEngineProfile(it) && it.rawConfig.isBlank()
        }.distinctBy { it.address to it.sni }.take(2).flatMap { original ->
            val resolved = try { resolve(original) } catch (e: Exception) {
                if (e is InterruptedException || e is kotlinx.coroutines.CancellationException) throw e
                return@flatMap emptySequence()
            }
            listOf(4, 8).asSequence().mapNotNull { ttl ->
                runCatching { ByedpiSidecar.enable(resolved, "www.microsoft.com", ttl) }.getOrNull()?.let {
                    it.copy(id = "recovery-fake-${it.effectiveFingerprint}", name = "${original.name} · Fake SNI TTL $ttl")
                }
            }
        }.toList()
}
