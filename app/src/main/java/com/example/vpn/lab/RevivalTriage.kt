package com.example.vpn.lab

import com.example.vpn.diagnostics.FailureStage
import com.example.vpn.connectivity.RecoveryProfile

/**
 * Why a dead config failed its plain request, read before any recipe is spent on it. Only causes the
 * error text states plainly are used; anything ambiguous (a timeout, a bare "no response") stays
 * [Cause.UNKNOWN] and every recipe is tried as before. Pure.
 *
 * - A name that does not resolve, or an address that refuses or has no route, cannot be fixed by
 *   changing how TLS looks: only another address can help, so only address recipes are tried.
 * - A connection cut during the handshake is what ClientHello filtering looks like: fragment, ECH and
 *   fingerprint recipes go first.
 * - A refused certificate is never worked around. The certificate stays checked on every recipe; another
 *   address goes first, since the network may be answering for this one.
 */
object RevivalTriage {
    enum class Cause(val title: String) {
        NAME_NOT_RESOLVED("The server name does not resolve here"),
        ADDRESS_UNREACHABLE("The address refuses connections or has no route"),
        CONNECTION_CUT("The connection was cut during the handshake"),
        CERTIFICATE_REFUSED("The certificate was refused"),
        UNKNOWN("Cause not clear from the error")
    }

    fun cause(reason: String?): Cause {
        val t = reason?.lowercase() ?: return Cause.UNKNOWN
        return when {
            "no such host" in t || "unknownhost" in t || "unable to resolve" in t || "cannot resolve" in t ||
                "lookup" in t && ("fail" in t || "misbehaving" in t) -> Cause.NAME_NOT_RESOLVED
            "x509" in t || "unknown authority" in t || "certificate verify failed" in t ||
                "certificate is not valid" in t -> Cause.CERTIFICATE_REFUSED
            "connection refused" in t || "no route to host" in t || "network is unreachable" in t ||
                "host is unreachable" in t -> Cause.ADDRESS_UNREACHABLE
            "reset by peer" in t || "connection reset" in t || "broken pipe" in t || "unexpected eof" in t ||
                "tls: handshake" in t || "handshake failure" in t -> Cause.CONNECTION_CUT
            else -> Cause.UNKNOWN
        }
    }

    /** The failure stage that limits which recipes are generated; null allows every recipe. */
    fun stage(cause: Cause): FailureStage? = when (cause) {
        Cause.NAME_NOT_RESOLVED -> FailureStage.DNS_RESOLUTION_FAILED
        Cause.ADDRESS_UNREACHABLE -> FailureStage.TCP_CONNECT_FAILED
        else -> null
    }

    /** How far [cause] moves [profile] up in the order; added to the network's own bias. */
    fun boost(cause: Cause, profile: RecoveryProfile): Double {
        val s = profile.networkConditions
        val address = RecoveryProfile.Strategy.ALT_ENDPOINT_V4 in s || RecoveryProfile.Strategy.ALT_ENDPOINT_V6 in s
        return when (cause) {
            Cause.CONNECTION_CUT -> if (RecoveryProfile.Strategy.FRAGMENT in s || RecoveryProfile.Strategy.ECH in s ||
                RecoveryProfile.Strategy.FINGERPRINT in s) 0.3 else 0.0
            Cause.CERTIFICATE_REFUSED -> if (address) 0.3 else 0.0
            else -> 0.0
        }
    }
}
