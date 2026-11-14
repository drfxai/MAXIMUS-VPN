package com.example.vpn.connections

import com.example.data.model.AppSettings
import com.example.data.model.VlessProfile
import com.example.vpn.sidecar.*
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.verify.Socks5
import kotlinx.coroutines.CancellationException

/** Serial alternatives, each proven by HTTPS data; never crosses direct/tunnel mode boundaries. */
object ConnectionEngineRunner {
    data class Started(val running: RunningEngine, val launch: SidecarLaunch, val profile: VlessProfile,
        val check: AccessProbe.Result)
    class NoVerifiedPath(attempts: List<String>, val lastCheck: AccessProbe.Result?) :
        IllegalStateException(attempts.joinToString(" · "))

    /**
     * A built-in transport can fail after the Smart Connect path search has finished:
     * unlike a saved proxy, its success is only measurable after process startup.
     * Escalate once to a saved server search, never across exact/Trade/forced boundaries.
     */
    fun mayRecoverAether(
        aether: Boolean, smart: Boolean, autoFailover: Boolean,
        exact: Boolean, exitLocked: Boolean, force: Boolean, alreadyEscalated: Boolean,
        /** "Power button fallback" (default on): a non-exact session may hand over even with failover off. */
        fallback: Boolean = false
    ): Boolean = aether && (smart || autoFailover || fallback) && !exact && !exitLocked &&
        !force && !alreadyEscalated

    /**
     * Every automatic method failed on this same network in the last half hour and none was ever verified
     * here: the Irancell pattern where WARP handshakes pass and the data is dropped for every carrier. Run one
     * quick recheck of the first method (has the block lifted?) without follow-ups, instead of repeating the
     * whole 5-6 minute chain, so Smart Connect / the power-button fallback reaches Psiphon, Tor or saved
     * servers in about a minute.
     */
    fun quickRecheckOnly(methods: List<String>, preferred: String?, recentlyFailed: Set<String>,
                         warpDataBlocked: Boolean = false): Boolean =
        preferred == null && methods.isNotEmpty() && (warpDataBlocked || methods.all { it in recentlyFailed })

    /**
     * Readiness windows for automatic Aether carriers. Every attempt on the same link gets the same window:
     * the windows used to shrink from retry to retry (180 s, then 65 s, then 40 s), so a carrier that needed
     * a full gateway scan could never finish on a second try, and the first try on a Cloudflare-filtered link
     * (measured by the LAB, cf=0 with UDP answering) still paid the full 180 s per MASQUE carrier.
     *
     * - Cloudflare TLS filtered with UDP answering: fixed per-carrier windows ([FILTERED_BUDGET_MS]).
     *   H2 MASQUE is TLS over TCP to Cloudflare, exactly what the probe saw fail, so it gets the shortest.
     * - A carrier that failed here in the last half hour on a link that is not (or no longer) measured as
     *   filtered: [RETRY_FLOOR_MS], never less, so a retry can still complete a scan.
     * - Manual/pinned carriers and other engines keep the engine's own window.
     * A ready SOCKS port alone still never counts as verified traffic.
     */
    fun readinessBudgetMs(
        engineId: String, automatic: Boolean, filteredTlsWithUdp: Boolean,
        carrier: String, defaultMs: Long, failedHere: Boolean = false
    ): Long = when {
        engineId != AetherSidecar.ID || !automatic -> defaultMs
        // WARP inside WARP is only ever an automatic follow-up to a WireGuard handshake whose data was blocked.
        carrier == "wiw" -> minOf(defaultMs, WIW_BUDGET_MS)
        filteredTlsWithUdp -> minOf(defaultMs, FILTERED_BUDGET_MS[carrier] ?: FILTERED_DEFAULT_BUDGET_MS)
        failedHere -> minOf(defaultMs, RETRY_FLOOR_MS)
        else -> defaultMs
    }

    /** Fixed windows on a measured Cloudflare-filtered link (same on the first try and on every retry). */
    val FILTERED_BUDGET_MS = mapOf("wireguard" to 75_000L, "h3" to 75_000L, "h2" to 60_000L)
    const val FILTERED_DEFAULT_BUDGET_MS = 75_000L
    const val RETRY_FLOOR_MS = 90_000L

    const val WIW_BUDGET_MS = 90_000L

    const val H2_BLOCKED_TLS_BUDGET_MS = 60_000L

    /**
     * What the LAB measured on the *current* physical link that matters to Aether, shared by Connect, the
     * Connections page test and the LAB engine probe (the latter two used to pass nothing, so a test on a
     * cf=0 link spent two full 180 s MASQUE scans before WireGuard was even tried).
     */
    data class LinkHints(val ipv4Only: Boolean = false, val cloudflareFiltered: Boolean = false, val metered: Boolean = false)

    fun linkHints(measured: com.example.vpn.smart.NetworkCapabilityProfile?): LinkHints {
        val m = measured ?: return LinkHints()
        return LinkHints(
            ipv4Only = m.ipv4Available == true && m.ipv6Available == false,
            // Cloudflare's TLS (or Cloudflare itself) failed while UDP abroad answered: MASQUE is the carrier
            // the probe saw fail, WireGuard is still worth a try. Only a measured failure counts, never a guess.
            cloudflareFiltered = m.udpAvailable == true &&
                (m.cloudflareIpv4Tls == false || m.cloudflareReachable == false),
            metered = m.meteredNetwork == true
        )
    }

    /** Method name of the obfuscated WireGuard retry, recorded so a verified result is reused next time. */
    const val WIREGUARD_NOIZE = "wireguard-noize"

    /** The obfuscation profile used for the retry: Aether's strongest pre-handshake junk-packet profile. */
    const val WIREGUARD_NOIZE_PROFILE = "gfw"

    /**
     * WireGuard's handshake passed but no HTTPS came back, with the default obfuscation: try WireGuard once
     * more with Aether's GFW junk-packet profile and a fresh endpoint/port scan (no quick reconnect to the
     * endpoint that just carried nothing) before WARP-in-WARP. Aether's own profile fallback only changes
     * obfuscation when the *handshake* fails, so without this it never tries a stronger profile on a link
     * that lets handshakes through and drops the data afterwards (the Irancell pattern). Never for a user's
     * own obfuscation choice, a reverse relay, or twice.
     */
    fun obfuscatedWireGuardFollowUp(engineId: String, automatic: Boolean, carrier: String, noize: String,
                                    alreadyQueued: Boolean, reverseRelay: Boolean): Boolean =
        engineId == AetherSidecar.ID && automatic && carrier == "wireguard" && noize == "auto" &&
            !alreadyQueued && !reverseRelay

    fun obfuscatedWireGuard(candidate: VlessProfile): VlessProfile = candidate.copy(extraSettings =
        org.json.JSONObject(candidate.extraSettings).put("carrier", "wireguard").put("noize", WIREGUARD_NOIZE_PROFILE)
            .put("rescan", true).put(NOIZE_FOLLOW_UP, true).toString())

    internal const val NOIZE_FOLLOW_UP = "noizeFollowUp"

    /** Method names of WARP carried through a saved server verified on this network (see [relayed]). */
    const val WIREGUARD_RELAY = "wireguard-relay"
    const val H2_RELAY = "h2-relay"
    internal const val RELAY_FOLLOW_UP = "relayFollowUp"

    /**
     * WARP through a saved server that carried verified traffic on this same network (Aether's exit-node
     * upstream: WireGuard's UDP and MASQUE's TLS leave the phone inside that server's own protocol, e.g. VLESS
     * REALITY). On a carrier that drops WARP data after the handshake (Irancell) the censor never sees WARP,
     * and the exit is still Cloudflare's. Only for automatic carriers, when the user picked no exit node of
     * their own and no Tor/Psiphon relay mode.
     */
    fun relayEligible(engineId: String, automatic: Boolean, options: AetherSettings, relayExitNode: String?): Boolean =
        engineId == AetherSidecar.ID && automatic && !relayExitNode.isNullOrBlank() && options.exitNode.isBlank() &&
            options.tor == "off" && options.psiphon == "off"

    fun relayed(candidate: VlessProfile, carrier: String, exitNode: String,
                route: BuiltinExitNode.Route = BuiltinExitNode.Route()): VlessProfile = candidate.copy(extraSettings =
        route.write(org.json.JSONObject(candidate.extraSettings).put("carrier", carrier).put("exitNode", exitNode)
            .put(RELAY_FOLLOW_UP, true).apply { remove(NOIZE_FOLLOW_UP); remove(MTU_FOLLOW_UP); remove("rescan") }).toString())

    /**
     * Whole-ladder budget for automatic Aether on a metered link that is Irancell or measured Cloudflare-filtered:
     * once it is spent no further direct carrier starts (WARP through a verified saved server still may, it is
     * one quick attempt) and the session falls back to saved servers / Psiphon / Tor instead of spending
     * minutes of mobile data on gateway scans that the carrier filters anyway.
     */
    const val HEAVY_LADDER_BUDGET_MS = 120_000L
    /** Below this much budget left a direct carrier is not started at all. */
    const val LADDER_MIN_ATTEMPT_MS = 20_000L

    fun ladderBudgetMs(engineId: String, automatic: Boolean, metered: Boolean, irancell: Boolean,
                       cloudflareFiltered: Boolean): Long? =
        if (engineId == AetherSidecar.ID && automatic && metered && (irancell || cloudflareFiltered)) HEAVY_LADDER_BUDGET_MS else null

    /**
     * WireGuard's handshake passed and no data came back on Irancell or a measured Cloudflare-filtered link: the
     * carrier is dropping WARP data (seen on every WARP carrier there). Record that at once and go straight to
     * WARP through a verified saved server, with at most one cheap follow-up (the MTU retry on the same endpoint),
     * instead of the obfuscated rescan, H2, H2-split and WARP-in-WARP full scans that all fail the same way.
     */
    fun warpDataBlockedFastPath(engineId: String, automatic: Boolean, carrier: String, irancell: Boolean,
                                cloudflareFiltered: Boolean, reverseRelay: Boolean): Boolean =
        engineId == AetherSidecar.ID && automatic && carrier == "wireguard" && (irancell || cloudflareFiltered) && !reverseRelay

    /** The reason recorded for an exception while starting a carrier: fixed, non-secret strings only. */
    internal fun startFailureReason(engineId: String, e: Throwable, stage: String): String {
        val message = e.message
        return when {
            e is BuiltinExitNode.ExitNodeFailure && message in BuiltinExitNode.REASONS -> message!!
            engineId == AetherSidecar.ID && message in setOf("WARP API TLS unreachable via forced Direct OOB route",
                AetherApiBridge.INCOMPATIBLE_OOB, AetherApiBridge.UNREACHABLE_WITHOUT_IDENTITY) -> message!!
            else -> stage
        }
    }

    /** Method name of the reduced-MTU WireGuard retry, recorded so a verified result is reused next time. */
    const val WIREGUARD_MTU = "wireguard-mtu"
    internal const val MTU_FOLLOW_UP = "mtuFollowUp"

    /**
     * WireGuard's handshake passed (small packets) but no HTTPS came back: the signature of a path that drops
     * full-size packets, common on mobile carriers that add their own encapsulation. Retry WireGuard once with
     * a smaller data-plane MTU ([AetherSettings.MTU_RETRY], inside the 1180–1220 band) on the endpoint that just
     * handshook (quick reconnect, no rescan: the endpoint was fine, the packet size was not). Only when the
     * user left the MTU on automatic; never for a reverse relay, a manual carrier, or twice.
     */
    fun reducedMtuWireGuardFollowUp(engineId: String, automatic: Boolean, carrier: String, mtu: String,
                                    alreadyQueued: Boolean, reverseRelay: Boolean): Boolean =
        engineId == AetherSidecar.ID && automatic && carrier == "wireguard" && mtu.isBlank() &&
            !alreadyQueued && !reverseRelay

    fun reducedMtuWireGuard(candidate: VlessProfile): VlessProfile = candidate.copy(extraSettings =
        org.json.JSONObject(candidate.extraSettings).put("carrier", "wireguard")
            .put("mtu", AetherSettings.MTU_RETRY.toString()).put(MTU_FOLLOW_UP, true)
            .apply { remove(NOIZE_FOLLOW_UP); remove("rescan") }.toString())

    /**
     * Aether's carrier order on Irancell (MCC-MNC 43235): MASQUE H3, then WireGuard, then MASQUE H2, then H2 with
     * the ClientHello split at the server name (UAC SNI Spoofer tries the split hello on Irancell last).
     */
    val IRANCELL_LADDER = listOf("h3", "wireguard", "h2", "h2-split")

    /**
     * Aether's carrier order on Hamrah-e-Aval (MCC-MNC 43211), from UAC SNI Spoofer's MCI outer plan: MASQUE over
     * HTTP/2 with the full, unfragmented ClientHello on both address families first ("the same dial that connected
     * on an MCI modem"), then HTTP/2 with the ClientHello split at the server name over IPv4, then H3, then WireGuard.
     */
    val MCI_LADDER = listOf("h2", "h2-split", "h3", "wireguard")

    /** Method name of MASQUE over HTTP/2 to a gateway remembered as clean on this network ([CleanH2PeerMemory]). */
    const val CLEAN_H2 = "h2-clean"
    /** Method name of MASQUE over HTTP/2 with the first TLS write split inside the server name. */
    const val H2_SPLIT = "h2-split"
    /** A remembered gateway either answers quickly or not at all: no scan runs behind it. */
    const val CLEAN_H2_BUDGET_MS = 35_000L
    /** Remembered gateways tried per connect before the normal carriers (UAC keeps its speed pick small too). */
    const val CLEAN_H2_TRIES = 2

    /** Whether [networkKey] is Hamrah-e-Aval (MCI). */
    fun mci(networkKey: String?): Boolean =
        com.example.vpn.connectivity.NetworkFirewalls.detect(networkKey) == com.example.vpn.connectivity.NetworkFirewalls.Firewall.MCI

    /**
     * Operator variants of the automatic Aether carriers. Hamrah-e-Aval: H2 sends the full ClientHello (no
     * fragment) and an [H2_SPLIT] variant follows it. Irancell: an [H2_SPLIT] variant follows H2. The split
     * variant fragments the first TLS write inside the server name (Aether's SNI-targeted split, the HTTP/2 form
     * of UAC's Iran ClientHello split) and dials IPv4 unless the user chose a family. Never for reverse relays.
     */
    fun operatorVariants(choices: List<VlessProfile>, irancell: Boolean, mci: Boolean): List<VlessProfile> {
        if (!irancell && !mci) return choices
        return choices.flatMap { candidate ->
            val e = ProfileExtras.read(candidate)
            val options = AetherSettings.read(e)
            if (e.optString("carrier") != "h2" || options.tor == "reverse" || options.psiphon == "reverse") return@flatMap listOf(candidate)
            val split = candidate.copy(extraSettings = org.json.JSONObject(e.toString()).put("fragment", true)
                .put(AetherSidecar.SNI_SPLIT, true).apply { if (optString("family", "auto") == "auto") put("family", "ipv4") }.toString())
            val plain = if (mci) candidate.copy(extraSettings = org.json.JSONObject(e.toString()).put("fragment", false).toString()) else candidate
            listOf(plain, split)
        }
    }

    /**
     * MASQUE over HTTP/2 to the gateways remembered as clean on this network (most recent / fastest first), at
     * most [CLEAN_H2_TRIES]. Each uses the hello it worked with (split or not). Only for automatic H2 without a
     * user endpoint, exit node or reverse/standalone relay; IPv6 gateways are left out on an IPv4-only link.
     */
    fun cleanH2Candidates(choices: List<VlessProfile>, peers: List<CleanH2PeerMemory.Peer>, ipv4Only: Boolean): List<VlessProfile> {
        val plain = choices.firstOrNull { ProfileExtras.read(it).let { e -> e.optString("carrier") == "h2" && !e.optBoolean(AetherSidecar.SNI_SPLIT) } }
            ?: return emptyList()
        val split = choices.firstOrNull { ProfileExtras.read(it).let { e -> e.optString("carrier") == "h2" && e.optBoolean(AetherSidecar.SNI_SPLIT) } }
        val options = AetherSettings.read(ProfileExtras.read(plain))
        if (options.endpoint.isNotBlank() || options.exitNode.isNotBlank() ||
            options.tor in listOf("reverse", "only") || options.psiphon in listOf("reverse", "only")) return emptyList()
        return peers.filter { !(ipv4Only && it.address.startsWith("[")) }.take(CLEAN_H2_TRIES).map { peer ->
            val base = if (peer.split && split != null) split else plain
            base.copy(extraSettings = org.json.JSONObject(base.extraSettings).put(AetherSidecar.CLEAN_H2_PEER, peer.address)
                .put("family", if (peer.address.startsWith("[")) "ipv6" else "ipv4").toString())
        }
    }

    /** Whether [networkKey] (a [com.example.vpn.smart.NetworkIdentity] key such as "cell:43235") is Irancell. */
    fun irancell(networkKey: String?): Boolean =
        com.example.vpn.connectivity.NetworkFirewalls.detect(networkKey) == com.example.vpn.connectivity.NetworkFirewalls.Firewall.IRANCELL

    /** On Irancell MASQUE H3 dials IPv4 only (UAC's Irancell plan); a family the user chose stays theirs. */
    fun irancellH3(candidate: VlessProfile): VlessProfile {
        val e = ProfileExtras.read(candidate)
        if (e.optString("carrier") != "h3" || e.optString("family", "auto") != "auto") return candidate
        return candidate.copy(extraSettings = org.json.JSONObject(e.toString()).put("family", "ipv4").toString())
    }

    /** One line telling the user what to do when Aether found nothing on a link whose Cloudflare TLS is filtered. */
    const val CLOUDFLARE_FILTERED_HINT = "Cloudflare TLS is filtered on this network: Smart Connect can try saved servers, Psiphon or Tor"

    /**
     * The order the methods are tried in: a method verified here first, then WireGuard when Cloudflare TLS
     * is filtered but UDP answers, then the rest, with methods that failed here in the last half hour last.
     */
    fun order(methods: List<String>, preferred: String?, preferWireGuard: Boolean, recentlyFailed: Set<String>,
              irancell: Boolean = false, mci: Boolean = false): List<String> =
        methods.withIndex().sortedWith(compareBy<IndexedValue<String>>({
            when {
                it.value == preferred -> 0
                it.value in recentlyFailed -> 4
                // A gateway that carried HTTP/2 here recently, before any scan (UAC's saved clean HTTP/2 list).
                it.value == CLEAN_H2 -> 1
                // Irancell ladder: MASQUE over HTTP/3 (QUIC, IPv4) first; Irancell lets WireGuard handshakes
                // through and then drops the data, so WireGuard goes after it (pattern from UAC SNI Spoofer).
                irancell && it.value == "h3" -> 2
                // Hamrah-e-Aval ladder: MASQUE over HTTP/2 with the full ClientHello first (UAC's MCI plan).
                mci && it.value == "h2" -> 2
                !irancell && !mci && preferWireGuard && it.value == "wireguard" -> 2
                else -> 3
            }
        }, {
            if (irancell) return@compareBy IRANCELL_LADDER.indexOf(it.value).let { i -> if (i < 0) methods.size + it.index else i }
            if (mci) return@compareBy MCI_LADDER.indexOf(it.value).let { i -> if (i < 0) methods.size + it.index else i }
            // Among the rest keep the declared order, but on a filtered link (measured, or H2 just failed
            // here) put WireGuard first and H2 (TCP TLS) after H3: WireGuard answers or fails fastest.
            val filtered = preferWireGuard || "h2" in recentlyFailed
            when {
                filtered && it.value == "wireguard" -> -1
                filtered && it.value == "h2" -> methods.size
                else -> it.index
            }
        })).map { it.value }

    /**
     * WARP inside WARP (gool-classic), tried once, automatically, when the WireGuard handshake passed but
     * no HTTPS came back through it: the pattern of a censor that lets WireGuard handshakes through and
     * then blocks the data (the case Oblivion's and PattNG's "gool" exist for). Never for a reverse relay,
     * which needs MASQUE over HTTP/2, and never twice.
     */
    fun warpInWarpFollowUp(engineId: String, automatic: Boolean, carrier: String, handshakePassed: Boolean,
                           alreadyQueued: Boolean, reverseRelay: Boolean): Boolean =
        engineId == AetherSidecar.ID && automatic && carrier == "wireguard" && handshakePassed &&
            !alreadyQueued && !reverseRelay

    fun candidates(profile: VlessProfile, ipv4Only: Boolean = false): List<VlessProfile> {
        val e = ProfileExtras.read(profile)
        val variants = when {
            AetherSidecar.handles(profile) && e.optString("carrier", "auto") == "auto" -> (if (AetherSettings.read(e).let { it.tor in listOf("reverse", "only") || it.psiphon in listOf("reverse", "only") }) listOf("h2") else listOf("h2", "h3", "wireguard")).map {
                profile.copy(extraSettings = org.json.JSONObject(e.toString()).put("carrier", it).toString())
            }
            ServerlessSidecar.handles(profile) && e.optString("variant", "auto") == "auto" -> listOf("fragA", "fragB", "oob").map {
                profile.copy(extraSettings = org.json.JSONObject(e.toString()).put("variant", it).toString())
            }
            else -> listOf(profile)
        }
        // "auto" may probe IPv6 indefinitely on an IPv4-only mobile network. Restrict only
        // when this session measured the *same physical link* with IPv4 and no IPv6.
        // User-selected ipv6/ipv4 settings remain authoritative.
        return if (ipv4Only && AetherSidecar.handles(profile) && e.optString("family", "auto") == "auto") {
            variants.map { candidate ->
                val options = org.json.JSONObject(candidate.extraSettings).put("family", "ipv4")
                candidate.copy(extraSettings = options.toString())
            }
        } else variants
    }
    /**
     * After verified HTTPS over MASQUE HTTP/2 (plain, split or a remembered gateway), keep the gateway in this
     * network's clean list. A scanned gateway is read from Aether's own last-connection file; relays are skipped
     * (their gateway was reached through a saved server, not over this network).
     */
    internal fun rememberCleanH2(name: String, candidate: VlessProfile, networkKey: String?, since: Long,
                                 record: (String, String, String, String) -> Unit) {
        if (name !in setOf("h2", H2_SPLIT, CLEAN_H2) || networkKey.isNullOrBlank()) return
        val e = ProfileExtras.read(candidate)
        val split = e.optBoolean(AetherSidecar.SNI_SPLIT)
        val peer = e.optString(AetherSidecar.CLEAN_H2_PEER).ifBlank { null } ?: AetherSidecar.lastMasqueH2Peer(since) ?: return
        CleanH2PeerMemory.remember(networkKey, peer, split = split)
        record(name, "clean-h2", "SAVED", "${CleanH2PeerMemory.masked(peer)} remembered for this network" + if (split) " (split ClientHello)" else "")
    }

    fun start(
        engine: SidecarEngine,
        profile: VlessProfile,
        settings: AppSettings,
        context: SidecarContext,
        checkDns: Boolean = false,
        ipv4Only: Boolean = false,
        preferWireGuard: Boolean = false,
        /** Profile id of a saved server verified on this network, for WARP-through-relay (see [relayed]). */
        relayExitNode: String? = null,
        /** How that saved server carried the verified traffic here (pinned address, stealth alternate). */
        relayExitRoute: BuiltinExitNode.Route = BuiltinExitNode.Route(),
        /** The physical link is metered (mobile data): the whole automatic ladder gets [HEAVY_LADDER_BUDGET_MS]. */
        metered: Boolean = false,
        onProgress: (String) -> Unit = {},
        cancelled: () -> Boolean = { false }
    ): Started {
        val attempts = mutableListOf<String>()
        var lastCheck: AccessProbe.Result? = null
        fun checkCancellation() {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            if (cancelled()) throw CancellationException("Connection cancelled")
        }
        fun record(method: String, stage: String, outcome: String, reason: String = "") {
            onProgress("${engine.id}/$method · $stage · $outcome" +
                reason.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
            com.example.vpn.diagnostics.events.EventLog.event("builtin.connection",
                attributes = mapOf("engine.name" to engine.id, "method" to method, "stage" to stage,
                    "reason" to reason), result = outcome)
            com.example.xray.XrayLogManager.i("BUILTIN", "${engine.id}/$method · $stage · $outcome" +
                reason.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
        }
        val automatic = ProfileExtras.read(profile).let {
            if (engine.id == AetherSidecar.ID) it.optString("carrier", "auto") == "auto"
            else it.optString("variant", "auto") == "auto"
        }
        fun scope(): String? {
            if (!automatic) return null
            val network = BuiltinMethodMemory.network() ?: return null
            val options = org.json.JSONObject(profile.extraSettings).apply { remove("rescan") }.toString()
            val input = network + "|" + engine.id + "|" + options + "|" + settings.dnsServer
            return java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
                .joinToString("") { "%02x".format(it) }
        }
        fun method(candidate: VlessProfile) = ProfileExtras.read(candidate).let {
            if (engine.id == AetherSidecar.ID) {
                if (it.optBoolean(RELAY_FOLLOW_UP)) it.optString("carrier") + "-relay"
                else if (it.optString(AetherSidecar.CLEAN_H2_PEER).isNotBlank()) CLEAN_H2
                else if (it.optBoolean(AetherSidecar.SNI_SPLIT) && it.optString("carrier") == "h2") H2_SPLIT
                else if (it.optBoolean(MTU_FOLLOW_UP)) WIREGUARD_MTU
                else if (it.optBoolean(NOIZE_FOLLOW_UP)) WIREGUARD_NOIZE else it.optString("carrier")
            } else it.optString("variant")
        }
        val cacheScope = scope()
        val networkKey = BuiltinMethodMemory.network()
        val onIrancell = engine.id == AetherSidecar.ID && automatic && irancell(networkKey)
        val onMci = engine.id == AetherSidecar.ID && automatic && mci(networkKey)
        val carrierChoices = candidates(profile, ipv4Only = ipv4Only).let { list -> if (onIrancell) list.map(::irancellH3) else list }
            .let { operatorVariants(it, irancell = onIrancell, mci = onMci) }
        val cleanPeers = if (engine.id == AetherSidecar.ID && automatic) CleanH2PeerMemory.peers(networkKey) else emptyList()
        val cleanChoices = if (engine.id == AetherSidecar.ID && automatic) cleanH2Candidates(carrierChoices, cleanPeers, ipv4Only) else emptyList()
        val baseChoices = cleanChoices + carrierChoices
        // The obfuscated WireGuard retry is not one of the declared carriers, but once it carried HTTPS on
        // this network it is remembered and tried first next time instead of the plain carrier.
        val noizeChoice = if (engine.id == AetherSidecar.ID && automatic &&
            baseChoices.any { method(it) == "wireguard" } &&
            AetherSettings.read(ProfileExtras.read(profile)).noize == "auto")
            baseChoices.first { method(it) == "wireguard" }.let(::obfuscatedWireGuard) else null
        val mtuChoice = if (engine.id == AetherSidecar.ID && automatic &&
            baseChoices.any { method(it) == "wireguard" } &&
            AetherSettings.read(ProfileExtras.read(profile)).mtu.isBlank())
            baseChoices.first { method(it) == "wireguard" }.let(::reducedMtuWireGuard) else null
        val relays = if (relayEligible(engine.id, automatic, AetherSettings.read(ProfileExtras.read(profile)), relayExitNode))
            listOf("wireguard", "h2").filter { c -> baseChoices.any { method(it) == c } }
                .map { c -> relayed(baseChoices.first { method(it) == c }, c, relayExitNode!!, relayExitRoute) } else emptyList()
        val followUps = listOfNotNull(noizeChoice, mtuChoice) + relays
        val preferred = BuiltinMethodMemory.preferred(cacheScope, baseChoices.map(::method) + followUps.map(::method))
        val choices = followUps.firstOrNull { method(it) == preferred }?.let { listOf(it) + baseChoices } ?: baseChoices
        // Keep a previously HTTPS-verified method first. Without such evidence, try WG
        // before long MASQUE gateway scans when fresh physical-link probes show that
        // ordinary Cloudflare TLS is unreachable but UDP is answering.
        val recentlyFailed = BuiltinMethodMemory.recentlyFailed(cacheScope)
        val rank = order(choices.map(::method), preferred, preferWireGuard, recentlyFailed, irancell = onIrancell, mci = onMci)
        if (onIrancell) record("ladder", "queue", "START", "Irancell: MASQUE H3 over IPv4 first, then WireGuard, then H2, then H2 with the ClientHello split at the SNI")
        if (onMci) record("ladder", "queue", "START", "Hamrah-e-Aval: MASQUE H2 with the full ClientHello first, then H2 with the ClientHello split at the SNI over IPv4, then H3, then WireGuard")
        if (cleanChoices.isNotEmpty()) record(CLEAN_H2, "queue", "START", "${cleanPeers.size} clean H2 gateway(s) remembered on this network; trying " +
            cleanChoices.joinToString(", ") { CleanH2PeerMemory.masked(ProfileExtras.read(it).optString(AetherSidecar.CLEAN_H2_PEER)) } +
            " before any scan" + (cleanPeers.first().rttMs?.let { " (prewarm: $it ms)" }.orEmpty()))
        val ordered = choices.sortedBy { rank.indexOf(method(it)) }
        val failedRegistrationKinds = mutableSetOf<String>()
        val recheckOnly = engine.id == AetherSidecar.ID && automatic &&
            quickRecheckOnly(ordered.map(::method).filter { it != CLEAN_H2 }, preferred, recentlyFailed,
                warpDataBlocked = BuiltinMethodMemory.warpDataBlocked(cacheScope))
        var handshakeWithoutData = false
        // A remembered gateway that fails is dropped from this network's clean list instead of marking the method
        // failed, so the next remembered gateway is not pushed to the back.
        fun failed(name: String, candidate: VlessProfile) {
            if (name == CLEAN_H2) {
                val peer = ProfileExtras.read(candidate).optString(AetherSidecar.CLEAN_H2_PEER)
                CleanH2PeerMemory.forget(networkKey, peer)
                record(name, "clean-h2", "FORGET", "${CleanH2PeerMemory.masked(peer)} no longer carries HTTP/2 here")
            } else BuiltinMethodMemory.recordFailure(cacheScope, name)
        }
        // WARP data blocked here recently: go straight through the verified saved server when there is one,
        // otherwise one quick recheck of the first method.
        val queue = ArrayDeque(when {
            recheckOnly && relays.isNotEmpty() -> relays
            recheckOnly -> ordered.take(1)
            else -> ordered
        })
        var relayQueued = recheckOnly && relays.isNotEmpty() || preferred in setOf(WIREGUARD_RELAY, H2_RELAY)
        if (recheckOnly) ordered.filter { it !in queue }.forEach {
            record(method(it), "queue", "SKIP", if (relayQueued) "WARP data was blocked on this network recently; trying WARP through a verified saved server"
                else "WARP data was blocked on this network recently; one quick recheck of ${method(ordered.first())} only")
        }
        val reverseRelay = engine.id == AetherSidecar.ID &&
            AetherSettings.read(ProfileExtras.read(profile)).let { it.tor == "reverse" || it.psiphon == "reverse" }
        var warpInWarpQueued = recheckOnly
        var noizeQueued = recheckOnly || preferred == WIREGUARD_NOIZE
        var mtuQueued = recheckOnly || preferred == WIREGUARD_MTU
        val ladderBudget = ladderBudgetMs(engine.id, automatic, metered, onIrancell, preferWireGuard)
        val ladderStartedAt = System.currentTimeMillis()
        var ladderStopped = false
        var warpBlockFastPath = false
        fun queueRelays(reason: String) {
            if (relayQueued || relays.isEmpty()) return
            relayQueued = true
            relays.forEach { record(method(it), "queue", "START", reason) }
            queue.addAll(relays)
        }
        while (true) {
            if (ladderBudget != null && !ladderStopped && queue.isNotEmpty() &&
                !ProfileExtras.read(queue.first()).optBoolean(RELAY_FOLLOW_UP) &&
                ladderBudget - (System.currentTimeMillis() - ladderStartedAt) < LADDER_MIN_ATTEMPT_MS) {
                ladderStopped = true
                val dropped = queue.filterNot { ProfileExtras.read(it).optBoolean(RELAY_FOLLOW_UP) }
                queue.removeAll(dropped)
                record("ladder", "budget", "STOP", "${ladderBudget / 1000}s Aether budget on this metered filtered link used; skipping " +
                    dropped.joinToString(", ") { method(it) } + "; falling back to saved servers")
                queueRelays("Aether budget used; trying WARP through a verified saved server once")
                warpInWarpQueued = true; noizeQueued = true; mtuQueued = true
            }
            if (queue.isEmpty()) {
                // Every direct carrier failed: WARP through the saved server that works on this network.
                if (relayQueued || relays.isEmpty()) break
                relayQueued = true
                relays.forEach { record(method(it), "queue", "START", "direct WARP carried no data here; trying WARP through a verified saved server") }
                queue.addAll(relays)
            }
            val candidate = queue.removeFirst()
            checkCancellation()
            val extras = ProfileExtras.read(candidate)
            val name = method(candidate)
            // Native carrier name: budgets and follow-ups are about the transport, not the label.
            val carrier = if (engine.id == AetherSidecar.ID) extras.optString("carrier") else name
            if (engine.id == AetherSidecar.ID && AetherApiBridge.skipAfterBootstrapFailure(candidate, failedRegistrationKinds)) {
                record(name, "registration", "SKIP", "account API unavailable; no independent stored identity")
                continue
            }
            var running: RunningEngine? = null
            var stage = "configuration preparation failed"
            val attemptStartedAt = System.currentTimeMillis()
            try {
                record(name, "prepare", "START")
                val launch = engine.prepare(candidate, settings, context)
                checkCancellation()
                stage = "engine process could not start"
                val process = EngineReaper.track(engine.startPrepared(candidate, settings, launch, context) ?: SidecarProcess.start(engine.id, launch, context.workDir, context.socksPort))
                running = process
                checkCancellation()
                stage = "local proxy did not become ready"
                val window = if (name == CLEAN_H2) minOf(launch.readyTimeoutMs, CLEAN_H2_BUDGET_MS)
                    else readinessBudgetMs(
                    engine.id, automatic, preferWireGuard, carrier, launch.readyTimeoutMs,
                    failedHere = name in recentlyFailed
                )
                // On a metered filtered link a direct carrier never runs past what is left of the ladder budget.
                val left = ladderBudget?.takeIf { !extras.optBoolean(RELAY_FOLLOW_UP) }
                    ?.let { maxOf(LADDER_MIN_ATTEMPT_MS, it - (System.currentTimeMillis() - ladderStartedAt)) }
                val ready = process.awaitReady(if (left != null) minOf(window, left) else window)
                checkCancellation()
                if (!ready) {
                    val reason = process.startupFailure ?: stage
                    record(name, "startup", "FAIL", reason)
                    attempts += "$name: $reason"
                    failed(name, candidate)
                    if (name == preferred) BuiltinMethodMemory.forget(cacheScope)
                    // Registration is shared by H2/H3 MASQUE. Do not repeat a failed signup
                    // for the same identity, but allow an independent stored or new WG identity.
                    // A proven API transport failure/rate limit blocks all *new* registrations.
                    if (engine.id == AetherSidecar.ID && AetherApiBridge.registrationFailed(reason)) {
                        failedRegistrationKinds += if (AetherApiBridge.bootstrapBlocked(reason))
                            AetherApiBridge.API_UNAVAILABLE else AetherApiBridge.keyKind(candidate)
                    }
                    continue
                }
                record(name, "startup", "PASS")
                stage = "access check failed"
                val endpoint = Socks5.Endpoint("127.0.0.1", context.socksPort,
                    context.socksUser.takeIf { launch.socksAuth }, context.socksPass.takeIf { launch.socksAuth })
                val generation = process.transportGeneration
                val first = AccessProbe.check(endpoint, checkDns, diagnoseAddress = engine.id == AetherSidecar.ID)
                checkCancellation()
                val result = if (engine.id == AetherSidecar.ID && first.traffic) {
                    record(name, "https", "RECHECK", "checking that the same transport keeps carrying traffic")
                    Thread.sleep(6_000)
                    checkCancellation()
                    if (process.isAlive && process.transportGeneration == generation) {
                        val second = AccessProbe.check(endpoint, checkDns = false)
                        if (process.transportGeneration == generation) second.copy(dns = first.dns)
                        else first.copy(traffic = false, latencyMs = null, failure = "Aether transport restarted during traffic verification")
                    } else first.copy(traffic = false, latencyMs = null, failure = "Aether transport stopped or restarted before traffic recheck")
                } else first
                checkCancellation()
                if (result.traffic && process.isAlive) {
                    record(name, "https", "PASS")
                    if (engine.id == AetherSidecar.ID && automatic) rememberCleanH2(name, candidate, networkKey, attemptStartedAt, ::record)
                    if (scope() == cacheScope) {
                        BuiltinMethodMemory.record(cacheScope, name)
                        BuiltinMethodMemory.clearFailure(cacheScope, name)
                        BuiltinMethodMemory.clearWarpDataBlocked(cacheScope)
                    }
                    running = null
                    return Started(process, launch, candidate, result)
                }
                lastCheck = result.takeUnless { it.traffic }
                if (carrier == "wireguard" || carrier == "wiw") handshakeWithoutData = true
                val reason = result.failure ?: "engine stopped during check"
                record(name, "https", "FAIL", reason)
                attempts += "$name: $reason"
                failed(name, candidate)
                if (name == preferred) BuiltinMethodMemory.forget(cacheScope)
                // A relayed carrier has no direct follow-ups of its own.
                if (extras.optBoolean(RELAY_FOLLOW_UP)) continue
                // Irancell / Cloudflare-filtered: handshake without data is the WARP data block. Remember it now (the
                // next connect goes straight to the relay) and skip the full scans that fail the same way.
                if (!warpBlockFastPath && warpDataBlockedFastPath(engine.id, automatic, carrier, onIrancell, preferWireGuard, reverseRelay)) {
                    warpBlockFastPath = true
                    BuiltinMethodMemory.recordWarpDataBlocked(cacheScope)
                    val dropped = queue.filterNot { ProfileExtras.read(it).optBoolean(RELAY_FOLLOW_UP) }
                    queue.removeAll(dropped)
                    val mtu = if (reducedMtuWireGuardFollowUp(engine.id, automatic, carrier, AetherSettings.read(extras).mtu,
                            alreadyQueued = mtuQueued, reverseRelay = reverseRelay)) reducedMtuWireGuard(candidate) else null
                    noizeQueued = true; warpInWarpQueued = true; mtuQueued = true
                    record(name, "queue", "FAST", "WireGuard handshake passed but no data came back on this " +
                        (if (onIrancell) "Irancell" else "Cloudflare-filtered") + " link: WARP data marked blocked here; " +
                        (if (relays.isNotEmpty()) "trying WARP through a verified saved server" else "no verified saved server for a relay") +
                        (if (mtu != null) ", then one MTU ${AetherSettings.MTU_RETRY} retry" else "") +
                        (if (dropped.isNotEmpty()) "; skipping " + dropped.joinToString(", ") { method(it) } else ""))
                    queueRelays("direct WARP carried no data here; trying WARP through a verified saved server")
                    mtu?.let { queue.addLast(it) }
                    continue
                }
                // Before WARP-in-WARP: the same WireGuard identity with the strongest junk-packet profile and a
                // fresh endpoint/port scan. It costs one more WireGuard window and is the cheaper fix when the
                // censor classifies the flow from its first packets.
                // Queue order after a plain WireGuard failure: reduced MTU first (same endpoint, quick), then
                // junk-packet obfuscation with a fresh scan, then the other carriers, WARP-in-WARP last.
                val obfuscate = obfuscatedWireGuardFollowUp(engine.id, automatic, carrier,
                        AetherSettings.read(extras).noize, alreadyQueued = noizeQueued, reverseRelay = reverseRelay)
                if (obfuscate) {
                    noizeQueued = true
                    record(WIREGUARD_NOIZE, "queue", "START",
                        "WireGuard handshake passed but no data came back; retrying with junk-packet obfuscation and a fresh endpoint scan")
                    queue.addFirst(obfuscatedWireGuard(candidate))
                }
                if (reducedMtuWireGuardFollowUp(engine.id, automatic, carrier, AetherSettings.read(extras).mtu,
                        alreadyQueued = mtuQueued, reverseRelay = reverseRelay)) {
                    mtuQueued = true
                    record(WIREGUARD_MTU, "queue", "START",
                        "WireGuard handshake passed but no data came back; retrying with MTU ${AetherSettings.MTU_RETRY}")
                    queue.addFirst(reducedMtuWireGuard(candidate))
                }
                if (warpInWarpFollowUp(engine.id, automatic, carrier, handshakePassed = true,
                        alreadyQueued = warpInWarpQueued || choices.any { method(it) == "wiw" }, reverseRelay = reverseRelay)) {
                    warpInWarpQueued = true
                    record("wiw", "queue", "START", "WireGuard handshake passed but no data came back; trying WARP inside WARP")
                    queue.addLast(candidate.copy(extraSettings =
                        org.json.JSONObject(candidate.extraSettings).put("carrier", "wiw").apply { remove(NOIZE_FOLLOW_UP); remove(MTU_FOLLOW_UP) }.toString()))
                }
            } catch (e: Exception) {
                if (e is InterruptedException || e is CancellationException) throw e
                checkCancellation()
                // Never print arbitrary process exceptions: they may contain profile secrets.
                // Only fixed, non-sensitive reasons (preflight and exit-node failures) are shown as they are.
                val reason = startFailureReason(engine.id, e, stage)
                record(name, "startup", "FAIL", reason)
                attempts += "$name: $reason"
                failed(name, candidate)
                // No saved identity and the account API cannot be reached: every other carrier that also
                // lacks an identity would wait out its whole window for the same registration.
                if (engine.id == AetherSidecar.ID && reason == AetherApiBridge.UNREACHABLE_WITHOUT_IDENTITY)
                    failedRegistrationKinds += AetherApiBridge.API_UNAVAILABLE
            } finally {
                running?.stop()
            }
        }
        checkCancellation()
        if (engine.id == AetherSidecar.ID && automatic && handshakeWithoutData)
            BuiltinMethodMemory.recordWarpDataBlocked(cacheScope)
        if (engine.id == AetherSidecar.ID && preferWireGuard) attempts += CLOUDFLARE_FILTERED_HINT
        throw NoVerifiedPath(attempts, lastCheck)
    }
}
