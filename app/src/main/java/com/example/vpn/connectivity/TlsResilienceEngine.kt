package com.example.vpn.connectivity

import com.example.data.model.VlessProfile

/**
 * Judges a config's TLS settings before it is used, and says what the bundled engine really supports.
 *
 * Facts checked in Xray-core v26.9.9 (the core in libXray v26.9.9, pinned in scripts/fetch-libxray-android.sh):
 * - `allowInsecure` was removed; Xray refuses it. Maximus refuses it too.
 * - fingerprint "unsafe" is not a uTLS preset: Xray dials with Go's own crypto/tls
 *   (transport/internet/tcp/dialer.go), so the custom cipher list applies and the certificate chain and
 *   hostname are still verified (RootCAs + verifyPeerCert in transport/internet/tls/config.go). It does
 *   not weaken authenticity; it makes the ClientHello easier to identify. REALITY refuses it.
 * - ECH (`echConfigList`, `echSockopt`) is supported by the Xray client. The Kotlin tunnel and the Mihomo
 *   adapter do not do ECH, so a config asking for ECH there is refused rather than silently sent without it.
 *
 * Connectivity never overrides a refusal here.
 */
object TlsResilienceEngine {
    enum class Engine { XRAY, KOTLIN, SIDECAR }

    /** Every fingerprint name Xray-core v26.9.9 accepts (PresetFingerprints, ModernFingerprints, OtherFingerprints). */
    val XRAY_FINGERPRINTS: Set<String> = setOf(
        "", "chrome", "firefox", "safari", "ios", "android", "edge", "360", "qq", "random", "randomized", "randomizednoalpn", "unsafe",
        "hellofirefox_120", "hellofirefox_148", "hellochrome_120", "hellochrome_131", "hellochrome_133", "helloios_13", "helloios_14",
        "helloedge_106", "hellosafari_26_3", "hello360_11_0", "helloqq_11_1", "hellogolang", "hellorandomized", "hellorandomizedalpn",
        "hellorandomizednoalpn", "hellofirefox_auto", "hellofirefox_55", "hellofirefox_56", "hellofirefox_63", "hellofirefox_65",
        "hellofirefox_99", "hellofirefox_102", "hellofirefox_105", "hellochrome_auto", "hellochrome_58", "hellochrome_62", "hellochrome_70",
        "hellochrome_72", "hellochrome_83", "hellochrome_87", "hellochrome_96", "hellochrome_100", "hellochrome_102",
        "hellochrome_106_shuffle", "helloios_auto", "helloios_11_1", "helloios_12_1", "helloandroid_11_okhttp", "helloedge_85",
        "helloedge_auto", "hellosafari_16_0", "hellosafari_auto", "hello360_auto", "hello360_7_5", "helloqq_auto", "hellochrome_100_psk",
        "hellochrome_112_psk_shuf", "hellochrome_114_padding_psk_shuf", "hellochrome_115_pq", "hellochrome_115_pq_psk", "hellochrome_120_pq"
    )

    private val WEAK_CIPHER = Regex("NULL|EXPORT|RC4|_DES_|3DES|_anon_|_MD5", RegexOption.IGNORE_CASE)

    data class Assessment(val accepted: Boolean, val refusals: List<String>, val notes: List<String>) {
        val reason: String? get() = refusals.firstOrNull()
    }

    fun echSupported(engine: Engine): Boolean = engine == Engine.XRAY

    fun assess(profile: VlessProfile, engine: Engine): Assessment {
        val refusals = mutableListOf<String>()
        val notes = mutableListOf<String>()
        val security = profile.security.lowercase()
        val fp = profile.fingerprint.lowercase()
        if (profile.allowInsecure) refusals += "Does not check the server's certificate"
        if (security == "tls" || security == "reality") {
            if (fp !in XRAY_FINGERPRINTS) refusals += "Unknown TLS fingerprint '${profile.fingerprint}'"
            if (security == "reality" && (fp == "unsafe" || fp == "hellogolang")) {
                notes += "REALITY cannot use the '$fp' fingerprint; a browser fingerprint is used instead"
            }
            if (security == "tls" && fp == "unsafe") {
                notes += "Go TLS stack: certificate still verified, ClientHello easier to identify"
            }
            if (profile.cipherSuites.isNotBlank() && WEAK_CIPHER.containsMatchIn(profile.cipherSuites)) refusals += "Weak TLS cipher suite"
            if (profile.echConfigList.isNotBlank()) {
                if (security != "tls") refusals += "ECH needs TLS"
                else if (!echSupported(engine)) refusals += "ECH was requested but this engine cannot do it"
                else notes += "Encrypted ClientHello"
            }
            if (profile.pinnedPeerCertSha256.isNotBlank()) notes += "Certificate pinned by SHA-256"
            if (profile.verifyPeerCertByName.isNotBlank()) notes += "Certificate verified against a named host"
        }
        RecoverySecurityGate.maskProblem(profile.finalMask)?.let { refusals += "Fragment mask refused: $it" }
        return Assessment(refusals.isEmpty(), refusals, notes)
    }
}
