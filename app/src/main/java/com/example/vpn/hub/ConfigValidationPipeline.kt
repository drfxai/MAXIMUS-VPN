package com.example.vpn.hub

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.RuntimeCapabilities
import com.example.vpn.engine.UniversalImportEngine

/**
 * Public configs are hostile input. Fetched text goes through: size limit, format detection and
 * parsing, sanitizing, security validation, de-duplication and quarantine; what survives is
 * UNVERIFIED until a real request carries traffic through it ([ConfigSyncManager]). Nothing goes from a
 * source to the active list directly.
 */
object ConfigValidationPipeline {
    const val MAX_TEXT_BYTES = 5 * 1024 * 1024
    const val MAX_NODES_PER_SOURCE = 500

    data class Result(val accepted: List<HubNode>, val quarantined: List<HubNode>, val rejectedEntries: Int)

    fun process(text: String, provider: FreeConfigProvider, alreadyKnown: Set<String> = emptySet()): Result {
        require(text.length <= MAX_TEXT_BYTES) { "Source is larger than ${MAX_TEXT_BYTES / 1024 / 1024} MB" }
        val imported = UniversalImportEngine.importText(text)
        val seen = alreadyKnown.toMutableSet()
        val accepted = mutableListOf<HubNode>()
        val quarantined = mutableListOf<HubNode>()
        for (raw in imported.validProfiles.take(MAX_NODES_PER_SOURCE)) {
            val profile = sanitize(raw, provider)
            // Canonical fingerprint: protocol, endpoint, port, a hash of the credential, transport,
            // security, SNI, public key, path, service name. No plaintext credential is compared.
            if (!seen.add(profile.effectiveFingerprint)) continue
            val problem = securityProblem(profile)
            val unsupported = RuntimeCapabilities.unsupportedReason(profile)
            when {
                unsupported != null -> quarantined += HubNode(profile, provider.id, SecurityState.UNSUPPORTED, quarantineReason = unsupported)
                problem != null -> quarantined += HubNode(profile, provider.id, SecurityState.INSECURE, quarantineReason = problem)
                else -> accepted += HubNode(profile, provider.id, SecurityState.UNVERIFIED)
            }
        }
        return Result(accepted, quarantined, imported.invalidEntries.size)
    }

    /** Names and ids come from the source; they are shortened and the provider is recorded. */
    private fun sanitize(profile: VlessProfile, provider: FreeConfigProvider): VlessProfile = profile.copy(
        name = profile.name.filter { !it.isISOControl() }.take(64).ifBlank { "${provider.name} ${profile.address}" },
        id = "hub-${provider.id}-${profile.effectiveFingerprint.take(16)}"
    )

    /**
     * Why a config from a free source must not be used even though an engine could run it, or null.
     */
    fun securityProblem(profile: VlessProfile): String? {
        val security = profile.security.lowercase()
        val encrypted = security == "tls" || security == "reality" ||
            profile.protocolType in setOf(ProtocolType.HYSTERIA2, ProtocolType.WIREGUARD, ProtocolType.SHADOWSOCKS) ||
            (profile.protocolType == ProtocolType.VLESS && profile.encryption.isNotBlank() && !profile.encryption.equals("none", true)) ||
            profile.protocolType == ProtocolType.VMESS
        val ip = if (looksLikeIp(profile.address)) runCatching { java.net.InetAddress.getByName(profile.address) }.getOrNull() else null
        return when {
            !encrypted -> "Sends traffic without encryption"
            profile.allowInsecure -> "Does not check the server's certificate"
            ip != null && com.example.vpn.EndpointResolver.isBlockedAnswer(ip) -> "Points at a private or reserved address"
            // TLS settings the engine that would run it cannot honour, or that weaken authentication.
            else -> com.example.vpn.connectivity.TlsResilienceEngine.assess(profile, engineOf(profile)).reason
        }
    }

    private fun engineOf(profile: VlessProfile): com.example.vpn.connectivity.TlsResilienceEngine.Engine =
        if (com.example.vpn.engine.EngineSelectionPolicy.select(profile) == com.example.vpn.engine.EngineSelectionPolicy.Runtime.XRAY)
            com.example.vpn.connectivity.TlsResilienceEngine.Engine.XRAY
        else com.example.vpn.connectivity.TlsResilienceEngine.Engine.KOTLIN

    private fun looksLikeIp(host: String): Boolean =
        host.isNotEmpty() && (host.all { it.isDigit() || it == '.' } || host.contains(':'))
}
