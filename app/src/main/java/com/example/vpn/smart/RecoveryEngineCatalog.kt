package com.example.vpn.smart

import com.example.data.model.VlessProfile
import com.example.vpn.engine.RuntimeCapabilities
import com.example.vpn.sidecar.ChainRunner
import com.example.vpn.sidecar.EngineChain
import com.example.vpn.sidecar.MasqueSidecar
import com.example.vpn.sidecar.PsiphonSidecar
import com.example.vpn.sidecar.Sidecars
import com.example.vpn.sidecar.TorSidecar

/** Shared recovery pool. Imported WebTunnel/obfs4 bridges stay distinct from the Snowflake fallback.
 * No bridge addresses, third-party account settings or relaxed TLS settings are supplied here.
 */
object RecoveryEngineCatalog {
    fun candidates(
        saved: List<VlessProfile>,
        seedBridges: List<String> = webTunnelSeeds(),
        available: (VlessProfile) -> Boolean = { RuntimeCapabilities.unsupportedReason(it) == null }
    ): List<VlessProfile> {
        val defaults = listOf(
            MasqueSidecar.profile().copy(id = "recovery-masque"),
            PsiphonSidecar.profile(tcpOnly = true).copy(id = "recovery-psiphon", name = "Psiphon direct (TCP)"),
            TorSidecar.profile().copy(id = "recovery-tor-snowflake"),
            ChainRunner.profile(EngineChain.Chain(listOf(EngineChain.Hop("psiphon"), EngineChain.Hop("masque")))) {
                if (it == "masque") "WARP MASQUE" else "Psiphon"
            }.copy(id = "recovery-psiphon-masque", name = "Psiphon over WARP MASQUE"),
            com.example.vpn.sidecar.MatrixTransportSidecar.profile("h2").copy(id = "recovery-matrix-h2"),
            com.example.vpn.sidecar.MatrixTransportSidecar.profile("h3").copy(id = "recovery-matrix-h3"),
            com.example.vpn.sidecar.MatrixTransportSidecar.profile("wireguard").copy(id = "recovery-matrix-wg"),
            com.example.vpn.sidecar.MatrixTransportSidecar.profile("gool").copy(id = "recovery-matrix-gool"),
            ChainRunner.profile(EngineChain.Chain(listOf(EngineChain.Hop("psiphon"), EngineChain.Hop("matrix-transport")))) {
                if (it == "matrix-transport") "Matrix Transport" else "Psiphon"
            }.copy(id = "recovery-psiphon-matrix", name = "Psiphon over Matrix Transport (HTTP/2)")
        )
        // Saved choices win a duplicate, including their region and bridge settings. A failed private
        // bridge must not suppress the no-bridge Snowflake fallback merely because both use Tor.
        val webTunnel = seedBridges.distinct().take(12).chunked(4).mapIndexedNotNull { i, lines ->
            val p = TorSidecar.profile(lines.joinToString("\n"), "Tor WebTunnel (seed ${i + 1})")
                .copy(id = "recovery-webtunnel-$i")
            p.takeIf { TorSidecar.problemOf(it) == null }
        }
        return (saved.filter { Sidecars.isEngineProfile(it) } + webTunnel + defaults)
            .filter { !it.allowInsecure && available(it) }
            .distinctBy { com.example.vpn.connectivity.PathRef.of(it) }
    }
    /** Public bootstrap data, not connectivity evidence. Bound reading and require HTTPS bridge URLs. */
    fun webTunnelSeeds(): List<String> = runCatching {
        Sidecars.openAsset?.invoke("tor/webtunnel-seeds.txt")?.bufferedReader()?.use { reader ->
            reader.lineSequence().take(128).map { it.trim().removePrefix("Bridge ") }
                .filter { line ->
                    line.startsWith("webtunnel ") && line.length <= 2_000 && line.none { it.isISOControl() } &&
                        line.split(' ').firstOrNull { it.startsWith("url=") }?.removePrefix("url=")?.let { url ->
                            runCatching { java.net.URI(url).let { it.scheme == "https" && !it.host.isNullOrBlank() && it.userInfo == null } }.getOrDefault(false)
                        } == true
                }.toList()
        }.orEmpty()
    }.getOrDefault(emptyList())

}
