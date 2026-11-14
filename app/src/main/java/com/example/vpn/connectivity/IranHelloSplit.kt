package com.example.vpn.connectivity

/**
 * The Iranian-carrier ClientHello split, adapted from UAC SNI Spoofer (MciConfig.IRAN_HELLO_FINALMASK,
 * MciConfig.IRANCELL_EDGE finalmaskMaxSplit = 100; Floxu1/UAC-SNI-Spoofer-Android, see THIRD_PARTY_NOTICES.md).
 *
 * [MASK] cuts the ClientHello into records so the first one ends right before the server name: with Go's TLS
 * stack ([FINGERPRINT] "unsafe") and the fixed [CIPHER_SUITES] list, an empty record is followed by a 104-byte
 * record that stops at the end of the cipher-suite list, and the rest (SNI included) leaves in 1-byte TCP
 * segments 1 ms apart (at most 11). A browser fingerprint moves the SNI, so the mask is only ever used with
 * the Go stack and this cipher list. Certificate verification is unchanged ([RecoverySecurityGate]).
 *
 * [DEEP_MASK] is UAC's Irancell "deep" fragment: 5-byte ClientHello pieces, no delay, up to 100 splits.
 *
 * Operator use ([NetworkFirewalls.bias]): Hamrah-e-Aval tries the split first (every other fragment recipe
 * stays skipped there); Irancell tries it after the ECH recipes, as UAC orders it. For Aether's MASQUE over
 * HTTP/2 the same idea is the core's own SNI-targeted first write ([AETHER_SNI_SPLIT_ENV]).
 */
object IranHelloSplit {
    const val PROFILE_ID = "iran-hello-split"
    const val DEEP_PROFILE_ID = "irancell-deep-split"

    const val FINGERPRINT = "unsafe"

    /** UAC's TLS_CIPHER_SUITES, identical to FIX BPB's list; the 104-byte cut depends on its exact length. */
    const val CIPHER_SUITES = com.example.panels.BpbFix.CIPHER_SUITES

    const val MASK =
        """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","lengths":["0","104","1"],"delays":["0"],"maxSplit":"0"}},""" +
            """{"type":"fragment","settings":{"packets":"1-1","lengths":["114","1"],"delays":["1"],"maxSplit":"11"}}]}"""

    const val DEEP_MASK =
        """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","length":"5","delay":"0","maxSplit":"100"}}]}"""

    /**
     * Aether (PattNG) splits the first TLS write of MASQUE over HTTP/2 inside the server name when this is set
     * together with --fragment (fragment.rs, FragmentConfig::from_env); MAXIMUS never set it before.
     */
    const val AETHER_SNI_SPLIT_ENV = "AETHER_MASQUE_H2_FRAGMENT_SNI"
}
