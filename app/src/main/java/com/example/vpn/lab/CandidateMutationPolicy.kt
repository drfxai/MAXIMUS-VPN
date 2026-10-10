package com.example.vpn.lab

import com.example.data.model.VlessProfile
import com.example.panels.BpbFix
import com.example.vpn.connectivity.RecoverySecurityGate
import com.example.vpn.stealth.StealthVariants

/**
 * The experiment allowlist (spec section 24). A derived LAB candidate may differ from its original only in
 * the fields listed in [Field], each only to an approved value, and it must then pass [RecoverySecurityGate].
 * Neither an AI suggestion nor a generator can vary anything else.
 *
 * Never mutable: credentials (UUID / password), private and public keys, short ids, authentication secrets,
 * certificate pins and name checks, SNI and Host (identity), path, service name, transport, security mode,
 * allowInsecure, flow, encryption, protocol, port, raw configs and extras. Kill switch, routing and DNS-leak
 * policy are app settings, not config fields, so no candidate can reach them at all.
 *
 * DNS strategy is measured per network by DnsResilienceEngine and is not a per-config mutation.
 */
object CandidateMutationPolicy {
    enum class Field(val profileField: String, val title: String) {
        ADDRESS("address", "Validated endpoint"),
        ADDRESS_FAMILY("targetStrategy", "Address family"),
        ALPN("alpn", "ALPN"),
        TLS_FINGERPRINT("fingerprint", "TLS fingerprint"),
        TLS_CIPHERS("cipherSuites", "TLS cipher list (with the Go TLS fingerprint only)"),
        FRAGMENT("finalMask", "Fragment profile"),
        ECH("echConfigList", "ECH profile");

        companion object {
            fun byProfileField(name: String): Field? = entries.firstOrNull { it.profileField == name }
        }
    }

    /** Field names that are refused outright, with a reason, when a mutation names them. */
    val PROTECTED: Map<String, String> = mapOf(
        "uuid" to "credential", "password" to "credential", "encryption" to "authentication", "flow" to "authentication",
        "publicKey" to "key", "privateKey" to "key", "shortId" to "authentication secret", "spiderX" to "REALITY setting",
        "pinnedPeerCertSha256" to "certificate pin", "verifyPeerCertByName" to "certificate check", "allowInsecure" to "certificate check",
        "sni" to "server identity (SNI)", "host" to "server identity (Host)", "security" to "security mode", "port" to "server identity",
        "path" to "server identity", "serviceName" to "server identity", "transport" to "transport", "protocolType" to "protocol",
        "rawConfig" to "raw configuration", "extraSettings" to "protocol extras", "echSockopt" to "socket options",
        "killSwitch" to "kill switch", "routing" to "protected routing policy", "dns" to "DNS leak protection", "command" to "external command"
    )

    val APPROVED_FINGERPRINTS: Set<String> = RecoverySecurityGate.TLS_FINGERPRINTS - ""
    val APPROVED_ALPN = setOf("h2", "http/1.1", "h2,http/1.1")
    val APPROVED_TARGET_STRATEGIES = setOf("UseIPv4", "UseIPv6", "UseIPv4v6", "UseIPv6v4")
    val APPROVED_MASKS: Set<String> = BpbFix.MASKS.toSet() + BpbFix.FINAL_MASK_V1
    val APPROVED_CIPHERS = setOf(BpbFix.CIPHER_SUITES)

    data class Verdict(val allowed: Boolean, val reason: String? = null, val changed: Set<Field> = emptySet()) {
        companion object { fun no(why: String) = Verdict(false, why) }
    }

    /**
     * Checks a mutation given as field name → value (what an AI suggestion or research handoff can express).
     * Unknown and protected fields are refused before anything is built.
     */
    fun checkRequest(parent: VlessProfile, request: Map<String, String>): Pair<Verdict, VlessProfile?> {
        if (request.isEmpty()) return Verdict.no("no change requested") to null
        for (name in request.keys) {
            PROTECTED[name]?.let { return Verdict.no("'$name' is protected ($it)") to null }
            if (Field.byProfileField(name) == null) return Verdict.no("'$name' is not an approved experiment field") to null
        }
        var p = parent
        for ((name, value) in request) {
            p = when (Field.byProfileField(name)!!) {
                Field.ADDRESS -> p.copy(address = value)
                Field.ADDRESS_FAMILY -> p.copy(targetStrategy = value)
                Field.ALPN -> p.copy(alpn = value)
                Field.TLS_FINGERPRINT -> p.copy(fingerprint = value)
                Field.TLS_CIPHERS -> p.copy(cipherSuites = value)
                Field.FRAGMENT -> p.copy(finalMask = value)
                Field.ECH -> p.copy(echConfigList = value)
            }
        }
        val verdict = check(parent, p)
        return verdict to p.takeIf { verdict.allowed }
    }

    /** The deterministic validation every candidate passes, however it was produced. */
    fun check(parent: VlessProfile, derived: VlessProfile): Verdict {
        // Rebuild the derived copy from the parent plus only the allowlisted fields: any other difference is a refusal.
        val rebuilt = parent.copy(
            address = derived.address, targetStrategy = derived.targetStrategy, alpn = derived.alpn, fingerprint = derived.fingerprint,
            cipherSuites = derived.cipherSuites, finalMask = derived.finalMask, echConfigList = derived.echConfigList
        )
        if (rebuilt != derived) return Verdict.no("a field outside the experiment allowlist changed")
        val changed = Field.entries.filter { f -> value(parent, f) != value(derived, f) }.toSet()
        if (changed.isEmpty()) return Verdict.no("the candidate is the original")
        for (f in changed) {
            val v = value(derived, f)
            val ok = when (f) {
                Field.ADDRESS -> true // the gate requires a public IP literal and an unchanged SNI
                Field.ADDRESS_FAMILY -> v in APPROVED_TARGET_STRATEGIES
                Field.ALPN -> v in APPROVED_ALPN
                Field.TLS_FINGERPRINT -> v.lowercase() in APPROVED_FINGERPRINTS
                Field.TLS_CIPHERS -> v.isEmpty() || (v in APPROVED_CIPHERS && derived.fingerprint == BpbFix.FINGERPRINT)
                Field.FRAGMENT -> v.isEmpty() || v in APPROVED_MASKS
                Field.ECH -> v.isEmpty() || v.endsWith("+" + StealthVariants.ECH_DNS) ||
                    v in com.example.vpn.connectivity.RecoveryProfiles.CLOUDFLARE_ECH_VALUES
            }
            if (!ok) return Verdict.no("${f.title} value is not an approved one")
        }
        val gate = RecoverySecurityGate.check(parent, derived)
        if (!gate.passed) return Verdict.no("security gate: ${gate.reason}")
        return Verdict(true, changed = changed)
    }

    private fun value(p: VlessProfile, f: Field): String = when (f) {
        Field.ADDRESS -> p.address
        Field.ADDRESS_FAMILY -> p.targetStrategy
        Field.ALPN -> p.alpn
        Field.TLS_FINGERPRINT -> p.fingerprint
        Field.TLS_CIPHERS -> p.cipherSuites
        Field.FRAGMENT -> p.finalMask
        Field.ECH -> p.echConfigList
    }
}
