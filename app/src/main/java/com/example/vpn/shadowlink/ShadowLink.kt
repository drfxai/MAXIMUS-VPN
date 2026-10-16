package com.example.vpn.shadowlink

import android.content.Context
import com.example.vpn.connectivity.TransportCapabilityEngine
import com.example.vpn.connectivity.TransportCapabilityEngine.Transport
import com.example.vpn.lab.NetworkState
import com.example.vpn.lab.NetworkStateClassifier
import com.example.vpn.lab.NetworkStateReading
import com.example.vpn.lab.ServiceCheck
import com.example.vpn.smart.NetworkCapabilityDetector
import com.example.vpn.smart.NetworkCapabilityProfile
import com.example.vpn.smart.NetworkKey
import com.example.vpn.connectivity.NetworkFirewalls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ShadowLink — the discovery coordinator. It runs a single pass that composes the engines the app
 * already has (capability measurement, network-state classification, service reachability, firewall
 * classification) plus a captive-portal probe, and fuses them into one [ShadowLinkReport]: a reachability
 * verdict, an adaptive transport order, and a recall of what worked on this network before.
 *
 * Nothing here re-implements measurement. The fusion ([synthesize]) is pure so it can be tested on its
 * own; [discover] only wires the Android pieces and the caller's HTTP fetch to it.
 */
object ShadowLink {

    /**
     * Fuses already-gathered signals into a report. Pure: no Android, no I/O.
     *
     * @param priorWorkingKinds transport kinds that succeeded here before (from NetworkMemory).
     */
    fun synthesize(
        networkKey: String,
        networkLabel: String,
        profile: NetworkCapabilityProfile?,
        reading: NetworkStateReading,
        services: ServiceCheck.Report?,
        firewall: NetworkFirewalls.Firewall,
        portal: CaptivePortalProbe.Portal,
        priorWorkingKinds: List<String>,
        now: Long
    ): ShadowLinkReport {
        val caps = TransportCapabilityEngine.capabilities(profile)
        val dnsHonest = profile?.dnsManipulated?.let { !it }
        val working = services?.results?.filter { it.verdict == ServiceCheck.Verdict.WORKS }?.map { it.service.title }.orEmpty()
        val blocked = services?.results
            ?.filter { it.verdict in BLOCKED_VERDICTS }?.map { it.service.title }.orEmpty()
        val egress = egressOf(reading.primary, portal, working.isNotEmpty(), blocked.isNotEmpty())
        val hints = recommendTransports(caps, reading.primary, firewall)
        val confidence = if (portal == CaptivePortalProbe.Portal.CAPTIVE) maxOf(reading.confidence, 0.9) else reading.confidence
        return ShadowLinkReport(
            networkKey = networkKey,
            networkLabel = networkLabel,
            egress = egress,
            state = reading.primary,
            firewall = firewall.title,
            captivePortal = portal.title,
            dnsHonest = dnsHonest,
            blockedServices = blocked,
            workingServices = working,
            exitCountry = services?.exit?.country,
            recommendedTransports = hints,
            priorWorkingKinds = priorWorkingKinds,
            confidence = confidence,
            summary = summarize(egress, reading.primary, portal, firewall, hints),
            at = now
        )
    }

    private val BLOCKED_VERDICTS = setOf(
        ServiceCheck.Verdict.FILTERED, ServiceCheck.Verdict.NO_CONNECTION,
        ServiceCheck.Verdict.REGION_BLOCKED, ServiceCheck.Verdict.REFUSED
    )

    /** Fuses the classifier's state and the captive-portal result into one reachability verdict. */
    internal fun egressOf(
        state: NetworkState,
        portal: CaptivePortalProbe.Portal,
        anyServiceWorks: Boolean,
        anyServiceBlocked: Boolean
    ): Egress {
        // A sign-in page gates everything before a tunnel can even be reached.
        if (portal == CaptivePortalProbe.Portal.CAPTIVE) return Egress.BLOCKED
        return when (state) {
            NetworkState.NORMAL -> if (anyServiceBlocked) Egress.FILTERED else Egress.OPEN
            NetworkState.FILTERED, NetworkState.SNI_INTERFERENCE_SUSPECTED, NetworkState.DNS_MANIPULATED,
            NetworkState.TLS_PATH_FAILURE, NetworkState.UDP_DEGRADED, NetworkState.UDP_BLOCKED_SUSPECTED,
            NetworkState.QUIC_DEGRADED, NetworkState.QUIC_BLOCKED_SUSPECTED, NetworkState.CDN_PATH_DEGRADED,
            NetworkState.INTERNATIONAL_DEGRADED, NetworkState.PARTIAL_INTERNATIONAL_CONNECTIVITY,
            NetworkState.IPV4_DEGRADED, NetworkState.IPV6_DEGRADED -> Egress.FILTERED
            NetworkState.NIN_WITH_DNS_EGRESS, NetworkState.SEVERE_FILTERING -> Egress.TUNNEL_ONLY
            NetworkState.DOMESTIC_ONLY, NetworkState.NO_VERIFIED_EGRESS,
            NetworkState.NO_VERIFIED_EGRESS_AFTER_RECOVERY ->
                if (anyServiceWorks) Egress.TUNNEL_ONLY else Egress.BLOCKED
            NetworkState.UNKNOWN -> Egress.UNKNOWN
        }
    }

    /**
     * Orders the transport families for this network from the measured capabilities, the classified
     * state and the known firewall. Reuses [TransportCapabilityEngine]'s capability flags; it does not
     * pick a concrete server, only the family order the selector should try.
     */
    internal fun recommendTransports(
        caps: TransportCapabilityEngine.Capabilities,
        state: NetworkState,
        firewall: NetworkFirewalls.Firewall
    ): List<TransportHint> {
        val hints = mutableListOf<TransportHint>()
        val udpOpen = caps.udp != false && caps.quic != false && !caps.udpImpaired &&
            state !in UDP_HOSTILE
        val tlsReason = when {
            firewall == NetworkFirewalls.Firewall.IRANCELL ->
                "TCP + TLS with fragmentation (this network filters by server name)"
            state == NetworkState.SNI_INTERFERENCE_SUSPECTED ->
                "TCP + TLS paired with ECH or fragmentation to hide the server name"
            firewall == NetworkFirewalls.Firewall.MCI || firewall == NetworkFirewalls.Firewall.RIGHTEL ->
                "TCP + TLS with ECH first on this network"
            else -> "TCP + TLS (REALITY) is a reliable path"
        }
        // On a clean-ish network, UDP/QUIC first for speed.
        if (udpOpen && state in UDP_PREFERRED) {
            hints += TransportHint(Transport.UDP, "UDP / QUIC is open here and is the fastest path")
        }
        if (caps.tcp != false) hints += TransportHint(Transport.TCP_TLS, tlsReason)
        if (udpOpen && hints.none { it.transport == Transport.UDP }) {
            hints += TransportHint(Transport.UDP, "UDP / QUIC as a secondary path")
        }
        if (state == NetworkState.TLS_PATH_FAILURE) {
            hints += TransportHint(Transport.TCP_PLAIN,
                "Plain TCP through a DNS or obfuscated tunnel, since TLS handshakes are being cut here")
        }
        if (hints.isEmpty()) {
            hints += TransportHint(Transport.TCP_TLS,
                "TCP + TLS through a tunnel; direct transports looked blocked when measured")
        }
        return hints
    }

    private val UDP_PREFERRED = setOf(
        NetworkState.NORMAL, NetworkState.FILTERED, NetworkState.INTERNATIONAL_DEGRADED,
        NetworkState.CDN_PATH_DEGRADED, NetworkState.PARTIAL_INTERNATIONAL_CONNECTIVITY
    )
    private val UDP_HOSTILE = setOf(
        NetworkState.UDP_DEGRADED, NetworkState.UDP_BLOCKED_SUSPECTED,
        NetworkState.QUIC_DEGRADED, NetworkState.QUIC_BLOCKED_SUSPECTED
    )

    private fun summarize(
        egress: Egress, state: NetworkState, portal: CaptivePortalProbe.Portal,
        firewall: NetworkFirewalls.Firewall, hints: List<TransportHint>
    ): String {
        if (portal == CaptivePortalProbe.Portal.CAPTIVE) {
            return "A sign-in page is blocking the internet. Sign in through the network's portal, then run discovery again."
        }
        val first = hints.firstOrNull()
        val lead = when (egress) {
            Egress.OPEN -> "The internet is open on this network."
            Egress.FILTERED -> "The internet works here but is being filtered (${state.title.lowercase()})."
            Egress.TUNNEL_ONLY -> "Only a tunnel is likely to reach abroad here (${state.title.lowercase()})."
            Egress.BLOCKED -> "No way out was verified on this network yet."
            Egress.UNKNOWN -> "Not enough was measured to judge this network yet."
        }
        val fw = if (firewall != NetworkFirewalls.Firewall.OTHER) " Firewall: ${firewall.title}." else ""
        val rec = first?.let { " Try ${it.transport.name.replace('_', '+')} first — ${it.reason.replaceFirstChar { c -> c.lowercase() }}." }.orEmpty()
        return lead + fw + rec
    }

    /**
     * Runs a full discovery pass. [fetch] is the caller's HTTP GET (bound to the physical network, or
     * through the VPN when it is on) — the same one the Network LAB uses for service checks. The
     * captive-portal probe runs only when [throughVpn] is false, since a portal no longer applies once a
     * tunnel is up.
     */
    suspend fun discover(
        context: Context,
        fetch: (String) -> ServiceCheck.Response,
        throughVpn: Boolean,
        priorWorkingKinds: List<String> = emptyList(),
        cancelled: () -> Boolean = { false },
        now: Long = System.currentTimeMillis()
    ): ShadowLinkReport = withContext(Dispatchers.IO) {
        val networkKey = runCatching { NetworkKey.current(context) }.getOrNull() ?: "other"
        val profile = runCatching { NetworkCapabilityDetector.detect(context) }.getOrNull()
        val reading = NetworkStateClassifier.classify(profile, now)
        val firewall = NetworkFirewalls.detect(networkKey)
        val services = runCatching {
            ServiceCheck.run(fetch = fetch, throughVpn = throughVpn, now = now, cancelled = cancelled)
        }.getOrNull()
        val portal = if (throughVpn) CaptivePortalProbe.Portal.UNKNOWN else probePortal(fetch, cancelled)
        synthesize(
            networkKey = networkKey,
            networkLabel = NetworkKey.describe(networkKey),
            profile = profile,
            reading = reading,
            services = services,
            firewall = firewall,
            portal = portal,
            priorWorkingKinds = priorWorkingKinds,
            now = now
        )
    }

    private fun probePortal(fetch: (String) -> ServiceCheck.Response, cancelled: () -> Boolean): CaptivePortalProbe.Portal {
        if (cancelled()) return CaptivePortalProbe.Portal.UNKNOWN
        return CaptivePortalProbe.check { url ->
            val r = runCatching { fetch(url) }.getOrNull()
            val redirected = r != null && !r.finalUrl.startsWith(url)
            CaptivePortalProbe.ProbeResponse(
                status = r?.status,
                location = if (redirected) r?.finalUrl else null,
                bodyLength = r?.body?.length
            )
        }
    }
}
