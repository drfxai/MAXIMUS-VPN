package com.example.xray

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Which uTLS fingerprints still reach an Xray REALITY server.
 *
 * Since Xray-core v26.9.8 (xtls/reality 47cfe99) a REALITY server treats a ClientHello without an
 * X25519MLKEM768 key share as a foreign client and forwards it to its target, so the client sees
 * "REALITY: received real certificate". This is intentional upstream (XTLS/Xray-core#7259: a modern
 * Chrome hello with the post-quantum share stripped is itself a fingerprint). Reported there: edge, ios,
 * qq, random, android and 360 fail; chrome, firefox, safari and randomized work.
 *
 * Checked against the uTLS revision Xray-core v26.11.9 pins (refraction-networking/utls a13c59ffe98b,
 * u_parrots.go / u_common.go): X25519MLKEM768 is sent by HelloChrome_131/133/155 (chrome = Chrome_155),
 * HelloFirefox_148 (firefox), HelloSafari_26_3 (safari). Edge_Auto = Edge_85, IOS_Auto = IOS_14,
 * 360_Auto = 360_7_5, QQ_11_1 and Android_11_OkHttp have no post-quantum share; "random" picks any
 * ModernFingerprints entry, so it fails part of the time; the *_pq presets carry the old Kyber draft group.
 */
object RealityFingerprint {
    /** Order the Smart/STEALTH rotation tries on a network; every entry sends X25519MLKEM768. */
    val PQ_ROTATION: List<String> = listOf("chrome", "firefox", "safari")

    /** Names that send an X25519MLKEM768 key share. "" is Xray's default (HelloChrome_Auto). */
    val PQ_CAPABLE: Set<String> = setOf(
        "", "chrome", "firefox", "safari", "randomized",
        "hellochrome_auto", "hellochrome_131", "hellochrome_133", "hellochrome_155",
        "hellofirefox_auto", "hellofirefox_148", "hellosafari_auto", "hellosafari_26_3"
    )

    /** Memory key (in the per-network variant map) for the fingerprint that last reached a REALITY server here. */
    const val NETWORK_WINNER_KEY = "*reality-fingerprint"

    fun isPqCapable(fingerprint: String): Boolean = normalize(fingerprint) in PQ_CAPABLE

    /**
     * The fingerprint a REALITY outbound really sends for [requested]: unchanged when it is PQ-capable,
     * otherwise the closest browser that is ("unsafe"/"hellogolang" are refused by REALITY outright).
     */
    fun forReality(requested: String): String {
        val fp = normalize(requested)
        if (fp in PQ_CAPABLE) return fp
        return when {
            fp == "ios" || fp.startsWith("helloios") || fp.startsWith("hellosafari") -> "safari"
            fp.startsWith("hellofirefox") -> "firefox"
            // edge, 360, qq, android (OkHttp), random, older or Kyber-draft Chrome, golang, unsafe, unknown.
            else -> "chrome"
        }
    }

    /** The fingerprint the rotation treats as "as saved" for [requested] ("" counts as chrome). */
    fun effectiveForRotation(requested: String): String = forReality(requested).ifBlank { "chrome" }.let {
        when {
            it.startsWith("hellochrome") -> "chrome"
            it.startsWith("hellofirefox") -> "firefox"
            it.startsWith("hellosafari") -> "safari"
            else -> it
        }
    }

    private val logged: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * [forReality] plus one log line per profile and fingerprint, so the user's explicit choice stays
     * visible in the logs when it is replaced.
     */
    fun forRealityLogged(requested: String, profileName: String, log: (String) -> Unit = { XrayLogManager.w(TAG, it) }): String {
        val sent = forReality(requested)
        if (sent != normalize(requested) && logged.add("$profileName|${normalize(requested)}")) {
            val why = if (normalize(requested) in setOf("unsafe", "hellogolang")) "is not a browser fingerprint, which REALITY requires"
                else "sends no X25519MLKEM768 key share, which Xray REALITY servers v26.9.8+ reject"
            log("REALITY: fingerprint '${requested.trim()}' of '$profileName' $why; sending '$sent' instead (saved choice unchanged).")
        }
        return sent
    }

    internal fun resetLogForTests() = logged.clear()

    private fun normalize(fp: String): String = fp.trim().lowercase(Locale.US)

    const val TAG = "REALITY"
}
