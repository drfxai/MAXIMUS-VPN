package com.example.vpn.lab

import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.stealth.StealthVariants
import com.example.xray.RealDelayProbe

/**
 * Evidence-based ECH. A candidate is only ever called ECH-capable after a real request went through it with ECH
 * configured, and verified after a second one.
 *
 * Why a pass proves ECH and cannot be a silent fallback: when tlsSettings.echConfigList is set, Xray-core
 * (transport/internet/tls/ech.go) either obtains an ECHConfigList or installs an invalid one that makes the
 * handshake fail, and Go's crypto/tls never retries without ECH when the server rejects it (it returns an ECH
 * rejection error). So with ECH configured, ordinary TLS cannot carry the request in its place. ECH changes
 * nothing about certificate or name verification: the copy keeps every security field of the saved config.
 *
 * The ECHConfig is looked up only over DNS over HTTPS ([StealthVariants.ECH_DNS]); a plaintext DNS source
 * (udp://, tcp://) would reveal the protected name, so such configs are refused for experiments.
 */
object EchMeasurement {

    enum class State(val title: String) {
        ECH_NOT_TESTED("Not tested"),
        ECH_UNSUPPORTED("Not supported for this config"),
        ECH_CONFIG_UNAVAILABLE("No ECH configuration for this name"),
        ECH_CONFIG_AVAILABLE("ECH configuration found"),
        ECH_HANDSHAKE_STARTED("ECH handshake started"),
        ECH_HANDSHAKE_SUCCESS("ECH handshake succeeded"),
        ECH_HANDSHAKE_FAILED("ECH handshake failed"),
        ECH_FALLBACK_USED("ECH failed; the ordinary path is used"),
        ECH_APPLICATION_TRAFFIC_VERIFIED("Real traffic passed with ECH"),
        ECH_DEGRADED("ECH passed once, then failed"),
        ECH_VERIFIED("ECH verified (two real requests)")
    }

    private val PLAINTEXT_DNS = Regex("(?i)^(udp|tcp|quic)://|\\+(udp|tcp|quic)://")
    private val CONFIG_ERROR = Regex("(?i)ech dns record|echconfig|ech config|failed to query ech|no ech")
    private val REJECTED = Regex("(?i)ech.*reject|reject.*ech|ech.*retry")

    /** Why [p] cannot run an ECH experiment, or null when it can. */
    fun unsupportedReason(p: VlessProfile): String? = when {
        p.profileType == ProfileType.XRAY_JSON -> "a raw Xray config is never rewritten"
        com.example.vpn.sidecar.Sidecars.forProfile(p) != null -> "ECH is measured only on the Xray core"
        p.protocolType == ProtocolType.HYSTERIA2 || p.protocolType == ProtocolType.WIREGUARD -> "not a TLS-over-TCP config"
        p.security.equals("reality", true) -> "REALITY does not use ECH"
        !p.security.equals("tls", true) -> "the config does not use TLS"
        p.allowInsecure -> "certificate checks are off in this config"
        serverName(p) == null -> "the server name is an address, and ECH needs a name"
        p.echConfigList.isNotBlank() && !safeSource(p.echConfigList) -> "its ECH source uses plaintext DNS"
        else -> null
    }

    /** True when the ECH config source never sends the protected name over plaintext DNS. */
    fun safeSource(echConfigList: String): Boolean = echConfigList.isBlank() || !PLAINTEXT_DNS.containsMatchIn(echConfigList.trim())

    private fun serverName(p: VlessProfile): String? = p.sni.ifBlank { p.host }.ifBlank { p.address }
        .takeIf { n -> n.any { it.isLetter() } && !n.contains(':') }

    /**
     * The ECH copy of [p] (never saved): the same config with its ECHConfig fetched over DoH for its server name.
     * A config that already names an ECH source is measured as it is.
     */
    fun variant(p: VlessProfile): VlessProfile? {
        if (unsupportedReason(p) != null) return null
        if (p.echConfigList.isNotBlank()) return p
        val name = serverName(p) ?: return null
        return p.copy(id = "${p.id}#ech", echConfigList = "$name+${StealthVariants.ECH_DNS}")
    }

    data class Result(
        val state: State,
        /** The ECH attempt's own state when the run ended in a fallback. */
        val echAttempt: State,
        val baselinePassed: Boolean?,
        val latencyMs: Long?,
        val why: String
    )

    /**
     * Judges one ECH experiment from real outcomes: [baseline] is the same config without ECH (null when not
     * measured), [first] the ECH copy, [recheck] a second ECH request after the stable window (null when not run).
     */
    fun judge(baseline: RealDelayProbe.Outcome?, first: RealDelayProbe.Outcome, recheck: RealDelayProbe.Outcome?): Result {
        val basePassed = when (baseline) { is RealDelayProbe.Outcome.Delay -> true; is RealDelayProbe.Outcome.Failed -> false; else -> null }
        return when (first) {
            is RealDelayProbe.Outcome.NotRun -> Result(State.ECH_NOT_TESTED, State.ECH_NOT_TESTED, basePassed, null, "not measured: ${first.reason}")
            is RealDelayProbe.Outcome.Delay -> when (recheck) {
                is RealDelayProbe.Outcome.Delay -> Result(State.ECH_VERIFIED, State.ECH_VERIFIED, basePassed, minOf(first.latencyMs, recheck.latencyMs),
                    "two real requests passed with ECH configured")
                is RealDelayProbe.Outcome.Failed -> Result(State.ECH_DEGRADED, State.ECH_DEGRADED, basePassed, first.latencyMs,
                    "passed with ECH, then failed on the recheck: ${recheck.reason}")
                else -> Result(State.ECH_APPLICATION_TRAFFIC_VERIFIED, State.ECH_APPLICATION_TRAFFIC_VERIFIED, basePassed, first.latencyMs,
                    "a real request passed with ECH configured (not yet rechecked)")
            }
            is RealDelayProbe.Outcome.Failed -> {
                val attempt = when {
                    CONFIG_ERROR.containsMatchIn(first.reason) -> State.ECH_CONFIG_UNAVAILABLE
                    REJECTED.containsMatchIn(first.reason) -> State.ECH_HANDSHAKE_FAILED
                    else -> State.ECH_HANDSHAKE_FAILED
                }
                if (basePassed == true) Result(State.ECH_FALLBACK_USED, attempt, true, null,
                    "ECH failed (${attempt.title.lowercase()}); the ordinary config carries traffic and is used instead. This is not ECH.")
                else Result(attempt, attempt, basePassed, null, first.reason)
            }
        }
    }

    /** True for states that may be shown as "ECH works". */
    fun proven(s: State): Boolean = s == State.ECH_APPLICATION_TRAFFIC_VERIFIED || s == State.ECH_VERIFIED

    /**
     * How much an ECH experiment is worth now (0..1). High when a controlled comparison suspects name-based
     * interference; some value when TLS fails while an international path exists; low when ordinary TLS already
     * works; none when there is no international IP-level path or the network gave no ECH config.
     */
    fun priority(restrictions: Set<NetworkState>, primary: NetworkState, baselineReliable: Boolean, configUnavailableHere: Boolean): Double = when {
        configUnavailableHere -> 0.0
        primary in setOf(NetworkState.NO_VERIFIED_EGRESS, NetworkState.NO_VERIFIED_EGRESS_AFTER_RECOVERY, NetworkState.DOMESTIC_ONLY,
            NetworkState.NIN_WITH_DNS_EGRESS) -> 0.0
        NetworkState.SNI_INTERFERENCE_SUSPECTED in restrictions || primary == NetworkState.SNI_INTERFERENCE_SUSPECTED -> 1.0
        baselineReliable -> 0.15
        NetworkState.TLS_PATH_FAILURE in restrictions || primary == NetworkState.TLS_PATH_FAILURE -> 0.5
        else -> 0.3
    }
}
