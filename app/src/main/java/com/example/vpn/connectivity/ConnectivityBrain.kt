package com.example.vpn.connectivity

import com.example.vpn.lab.NetworkState

/**
 * One connectivity brain: the current-session evidence that LAB, SmartConnect, recovery and failover all read
 * and write, so they never reach contradictory conclusions from separate state machines.
 *
 * Rules:
 *  - Every observation names its source, measurement, network session, profile or family, address family,
 *    time, outcome, failing stage and (where it has one) a confidence.
 *  - Fresh evidence from the current network session is current truth. Anything older, or from another
 *    session, is prior information only: it may reorder tests, it never makes a path count as working.
 *  - A path that failed a real request in this session moments ago is RECENTLY_FAILED and is never started
 *    automatically. It becomes eligible again when the network changes, its evidence ages out, its config
 *    changes (another fingerprint) or a newer real test passes.
 *  - No credentials are stored: profiles appear only as their fingerprint and family.
 *
 * Pure apart from the clock, so every rule is unit-tested.
 */
enum class EvidenceSource { LAB, SMART_CONNECT, PREFLIGHT, FAILOVER, WATCHDOG, RECOVERY, NETWORK_PROBE, APP_TEST }

enum class MeasurementType(val pathLevel: Boolean) {
    /** One real request through a candidate (any target). */
    REAL_REQUEST(true),
    /** Real requests to several independent targets through a candidate. */
    MULTI_TARGET(true),
    /** A real request through a separate engine program (Psiphon, Tor, DNS tunnel, Mihomo). */
    ENGINE_REQUEST(true),
    /** A real request through the running tunnel. */
    TUNNEL_TRAFFIC(true),
    /** A second real request after the stable window. */
    STABILITY(true),
    /** Reachability of references outside any proxy (international, domestic). */
    NETWORK_REACHABILITY(false),
    /** Controlled comparison: the same address with a filtered and a neutral server name. */
    SNI_COMPARISON(false),
    UDP(false),
    QUIC(false),
    DNS(false),
    ECH(true),
    MTU(true)
}

enum class EvidenceAddressFamily { ANY, IPV4, IPV6 }

enum class EvidenceFreshness { FRESH, AGING, STALE }

data class CurrentNetworkSession(val id: String, val networkKey: String, val startedAt: Long)

data class NetworkEvidence(
    val source: EvidenceSource,
    val type: MeasurementType,
    val sessionId: String,
    val networkKey: String,
    /** The profile fingerprint (never a credential), or null for a network-level measurement. */
    val profileRef: String?,
    /** Family or kind name (REALITY, CDN, QUIC, Tor obfs4 ...), or the network property measured. */
    val family: String?,
    val addressFamily: EvidenceAddressFamily = EvidenceAddressFamily.ANY,
    val at: Long,
    val success: Boolean,
    /** Where it failed (a FailureStage or ConnectionStage name), null on success. */
    val failureStage: String? = null,
    val confidence: Double? = null,
    val latencyMs: Long? = null,
    val targetsPassed: Int = 0,
    val targetsTried: Int = 0,
    val attempts: Int = 1,
    val detail: String = ""
) {
    fun freshness(now: Long): EvidenceFreshness = when (now - at) {
        in Long.MIN_VALUE..EvidenceBook.FRESH_MS -> EvidenceFreshness.FRESH
        in EvidenceBook.FRESH_MS..EvidenceBook.AGING_MS -> EvidenceFreshness.AGING
        else -> EvidenceFreshness.STALE
    }
}

/** Where a path stands for automatic use, best first. RECENTLY_FAILED is never started automatically. */
enum class PathEligibility(val title: String, val automatic: Boolean) {
    FRESH_VERIFIED("verified on this network just now", true),
    FRESH_CANDIDATE("passed one real request on this network just now", true),
    UNTESTED("not tested on this network yet", true),
    STALE_VERIFIED("worked here before; needs a fresh test", true),
    RECENTLY_FAILED("failed a real request on this network moments ago", false)
}

/** The shared assessment of the current network session. [basis] lists the evidence behind it. */
data class NetworkAssessment(
    val sessionId: String?,
    val primary: NetworkState,
    val restrictions: Set<NetworkState>,
    val confidence: Double,
    val basis: List<String>,
    val at: Long
)

/** What the DNS model keeps apart; "DNS works" is never one answer. */
enum class DnsEvidenceKind(val title: String) {
    SYSTEM_DNS("System DNS"),
    DIRECT_FOREIGN_DNS("Direct DNS to a foreign resolver"),
    PRECONNECT_DOH_RESOLUTION("DNS over HTTPS before connecting"),
    PRECONNECT_DOT("DNS over TLS before connecting"),
    VPN_RUNTIME_DNS("DNS inside the VPN"),
    VPN_RUNTIME_DOH("DNS over HTTPS inside the VPN"),
    DNS_THROUGH_TUNNEL("DNS through the tunnel"),
    RECURSIVE_FOREIGN_DNS_EGRESS("Recursive DNS egress abroad"),
    EXTERNAL_DNS_LEAK("DNS leak outside the VPN")
}

enum class DnsEvidenceStatus { VERIFIED, WORKS, POISONED, FAILED, NOT_TESTED, INFRASTRUCTURE_REQUIRED }

/**
 * The recovery escalation of one connect or analysis. Only the user may move a failed run to
 * [USER_CONNECT_ANYWAY]; nothing automatic ever does.
 */
enum class RecoveryStage(val title: String) {
    INITIAL("Starting"),
    ORDINARY_PREFLIGHT("Testing the selected server"),
    SAVED_PROFILE_RACE("Racing the other saved servers"),
    SAFE_VARIANTS("Trying safe variants"),
    RECOVERY_ENGINES("Trying recovery engines"),
    FIRST_VERIFIED_PATH("First verified path found"),
    STABILITY_VERIFICATION("Rechecking the path"),
    DEEP_OPTIMIZATION("Looking for a better path"),
    CONNECTED_VERIFIED("Connected and verified"),
    NO_VERIFIED_EGRESS_AFTER_RECOVERY("No verified path with the methods available"),
    USER_CONNECT_ANYWAY("Connect anyway (your choice)");

    companion object {
        private val FORWARD = listOf(INITIAL, ORDINARY_PREFLIGHT, SAVED_PROFILE_RACE, SAFE_VARIANTS, RECOVERY_ENGINES,
            FIRST_VERIFIED_PATH, STABILITY_VERIFICATION, DEEP_OPTIMIZATION, CONNECTED_VERIFIED)

        /**
         * Legal moves: forward along the ladder (steps may be skipped), into the failure end state from any
         * search step, and from the failure end state to "connect anyway" only when [byUser].
         */
        fun allowed(from: RecoveryStage, to: RecoveryStage, byUser: Boolean): Boolean = when {
            to == USER_CONNECT_ANYWAY -> byUser && from == NO_VERIFIED_EGRESS_AFTER_RECOVERY
            from == NO_VERIFIED_EGRESS_AFTER_RECOVERY -> to == INITIAL
            to == NO_VERIFIED_EGRESS_AFTER_RECOVERY -> from in FORWARD && from.ordinal <= RECOVERY_ENGINES.ordinal
            to == INITIAL -> true
            from == USER_CONNECT_ANYWAY -> false
            else -> FORWARD.indexOf(to) > FORWARD.indexOf(from)
        }
    }
}

/** A tiny state holder over [RecoveryStage.allowed]; refuses illegal moves instead of performing them. */
class RecoveryMachine(private val onChange: (RecoveryStage) -> Unit = {}) {
    @Volatile var stage: RecoveryStage = RecoveryStage.INITIAL
        private set

    @Synchronized
    fun advance(to: RecoveryStage, byUser: Boolean = false): Boolean {
        if (to == stage) return true
        if (!RecoveryStage.allowed(stage, to, byUser)) return false
        stage = to
        onChange(to)
        return true
    }
}

/** What to do next, decided from evidence; [why] is shown to the user. */
data class RecoveryDecision(val stage: RecoveryStage, val action: Action, val profileRef: String?, val why: String) {
    enum class Action { CONNECT_VERIFIED, TRY_NEXT, ESCALATE_TO_RECOVERY, STOP_NO_VERIFIED_PATH }
}

class EvidenceBook(private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = Any()
    private var session: CurrentNetworkSession? = null
    private val log = ArrayDeque<NetworkEvidence>()
    private val dns = mutableMapOf<DnsEvidenceKind, DnsEvidenceStatus>()
    private var reading: Pair<String, com.example.vpn.lab.NetworkStateReading>? = null
    private var recoveryExhaustedIn: String? = null
    private var counter = 0

    /** The network the phone uses now; a different key starts a new session (old evidence becomes prior only). */
    fun observeNetwork(networkKey: String): CurrentNetworkSession = synchronized(lock) {
        val s = session
        if (s != null && s.networkKey == networkKey) return s
        val next = CurrentNetworkSession("${networkKey.take(16)}#${++counter}-${clock().toString(36)}", networkKey, clock())
        session = next
        dns.clear()
        reading = null
        recoveryExhaustedIn = null
        next
    }

    /** The network went away: the next observation always starts a new session. */
    fun networkLost() = synchronized(lock) { session = null; dns.clear(); reading = null; recoveryExhaustedIn = null }

    fun session(): CurrentNetworkSession? = synchronized(lock) { session }

    fun record(e: NetworkEvidence) = synchronized(lock) {
        log.addLast(e)
        while (log.size > MAX_RECORDS) log.removeFirst()
    }

    /** Records [success] for [profileRef] in the current session; a no-op without a session. */
    fun recordPath(
        source: EvidenceSource, type: MeasurementType, profileRef: String, family: String?, success: Boolean,
        latencyMs: Long? = null, failureStage: String? = null, targetsPassed: Int = if (success) 1 else 0,
        targetsTried: Int = 1, addressFamily: EvidenceAddressFamily = EvidenceAddressFamily.ANY, detail: String = ""
    ) {
        val s = session() ?: return
        record(NetworkEvidence(source, type, s.id, s.networkKey, profileRef, family, addressFamily, clock(), success,
            if (success) null else failureStage, null, latencyMs, targetsPassed, targetsTried, 1, detail))
    }

    /** A network-level fact (reachability, SNI comparison, UDP, QUIC, DNS) in the current session. */
    fun recordNetwork(source: EvidenceSource, type: MeasurementType, property: String, success: Boolean, confidence: Double? = null, detail: String = "") {
        val s = session() ?: return
        record(NetworkEvidence(source, type, s.id, s.networkKey, null, property, EvidenceAddressFamily.ANY, clock(), success,
            if (success) null else property, confidence, detail = detail))
    }

    /** The LAB's classification of the raw network, kept for this session only. */
    fun recordReading(r: com.example.vpn.lab.NetworkStateReading) = synchronized(lock) {
        session?.let { reading = it.id to r }
    }

    fun recordDns(kind: DnsEvidenceKind, status: DnsEvidenceStatus) = synchronized(lock) { if (session != null) dns[kind] = status }

    /** Every DNS property, the ones never measured as NOT_TESTED; recursive egress needs a controlled zone. */
    fun dnsEvidence(): Map<DnsEvidenceKind, DnsEvidenceStatus> = synchronized(lock) {
        DnsEvidenceKind.values().associateWith { k ->
            dns[k] ?: if (k == DnsEvidenceKind.RECURSIVE_FOREIGN_DNS_EGRESS) DnsEvidenceStatus.INFRASTRUCTURE_REQUIRED else DnsEvidenceStatus.NOT_TESTED
        }
    }

    /** Recovery ran in this session and every available family failed. */
    fun markRecoveryExhausted() = synchronized(lock) { recoveryExhaustedIn = session?.id }

    fun all(): List<NetworkEvidence> = synchronized(lock) { log.toList() }

    fun current(): List<NetworkEvidence> = synchronized(lock) { session?.let { s -> log.filter { it.sessionId == s.id } } ?: emptyList() }

    /** Path evidence for [profileRef]: this session's, newest last. */
    fun pathEvidence(profileRef: String): List<NetworkEvidence> = current().filter { it.profileRef == profileRef && it.type.pathLevel }

    fun eligibility(profileRef: String): PathEligibility {
        val now = clock()
        val mine = pathEvidence(profileRef).filter { now - it.at <= AGING_MS }
        val last = mine.lastOrNull()
        if (last != null && !last.success && now - last.at <= RECENT_FAIL_MS) return PathEligibility.RECENTLY_FAILED
        val passes = mine.filter { it.success && now - it.at <= FRESH_MS }
        if (passes.isNotEmpty() && (last?.success == true)) {
            val span = passes.maxOf { it.at } - passes.minOf { it.at }
            val stable = passes.any { it.type == MeasurementType.TUNNEL_TRAFFIC || it.type == MeasurementType.STABILITY ||
                it.type == MeasurementType.MULTI_TARGET && it.targetsPassed >= 2 } || (passes.size >= 2 && span >= STABLE_WINDOW_MS)
            return if (stable) PathEligibility.FRESH_VERIFIED else PathEligibility.FRESH_CANDIDATE
        }
        val key = session()?.networkKey
        val history = all().filter { it.profileRef == profileRef && it.networkKey == key && it.type.pathLevel }
        return if (history.lastOrNull()?.success == true) PathEligibility.STALE_VERIFIED else PathEligibility.UNTESTED
    }

    /** True when [profileRef] passed a real request so recently that repeating it now would only cost time. */
    fun reusablePass(profileRef: String): NetworkEvidence? {
        val now = clock()
        return pathEvidence(profileRef).lastOrNull()?.takeIf { it.success && now - it.at <= REUSE_PASS_MS }
    }

    /**
     * Failover order from fresh evidence: FRESH_VERIFIED, FRESH_CANDIDATE, UNTESTED, STALE_VERIFIED; RECENTLY_FAILED
     * is left out. [refOf] gives each item's profile fingerprint. Stable within a class.
     */
    fun <T> eligibleInOrder(items: List<T>, refOf: (T) -> String): List<T> =
        items.map { it to eligibility(refOf(it)) }
            .filter { it.second.automatic }
            .sortedBy { it.second.ordinal }
            .map { it.first }

    /**
     * One assessment for everyone. The LAB's measured reading of the raw network is the base; path evidence of
     * this session adds what the VPN side saw. Ordinary TLS, REALITY, Trojan, XHTTP or WebSocket failures say
     * TLS_PATH_FAILURE at most; SNI_INTERFERENCE_SUSPECTED only ever comes from a controlled comparison.
     */
    fun assess(): NetworkAssessment {
        val now = clock()
        val s = session()
        val cur = current().filter { now - it.at <= AGING_MS }
        val base = synchronized(lock) { reading?.takeIf { it.first == s?.id }?.second }
        val basis = mutableListOf<String>()
        val restrictions = linkedSetOf<NetworkState>()
        base?.let { basis += "network measurement: ${it.summary()}"; restrictions += it.restrictions }

        val sni = cur.lastOrNull { it.type == MeasurementType.SNI_COMPARISON }
        if (sni != null && !sni.success) { restrictions += NetworkState.SNI_INTERFERENCE_SUSPECTED; basis += "controlled SNI comparison: filtered name cut" }
        if (sni != null && sni.success) restrictions -= NetworkState.SNI_INTERFERENCE_SUSPECTED

        val paths = cur.filter { it.type.pathLevel && it.profileRef != null }
        val latestPerPath = paths.groupBy { it.profileRef }.mapValues { it.value.last() }.values
        val tlsKinds = setOf("REALITY", "TLS", "CDN")
        val udpKinds = setOf("QUIC", "WireGuard")
        val tlsFail = latestPerPath.filter { it.family in tlsKinds && !it.success }
        val tlsPass = latestPerPath.any { it.family in tlsKinds && it.success }
        if (tlsFail.isNotEmpty() && !tlsPass && sni?.success != false) {
            restrictions += NetworkState.TLS_PATH_FAILURE
            basis += "${tlsFail.size} TLS-based path(s) carried no traffic (a path failure, not proof of SNI filtering)"
        }
        val udpFail = latestPerPath.filter { it.family in udpKinds && !it.success }
        val udpPass = latestPerPath.any { it.family in udpKinds && it.success }
        if (udpFail.size >= 2 && !udpPass) {
            restrictions += NetworkState.UDP_DEGRADED
            basis += "${udpFail.size} independent UDP paths failed"
        }
        cur.lastOrNull { it.type == MeasurementType.DNS && it.family == "dns-poisoned" }?.let {
            if (!it.success) { restrictions += NetworkState.DNS_MANIPULATED; basis += "a server name got a blocked DNS answer" }
        }
        val anyVerified = latestPerPath.any { it.success }
        val exhausted = synchronized(lock) { recoveryExhaustedIn != null && recoveryExhaustedIn == s?.id }
        if (anyVerified) basis += "${latestPerPath.count { it.success }} path(s) carried real international traffic in this session"

        val primary = when {
            exhausted && !anyVerified -> NetworkState.NO_VERIFIED_EGRESS_AFTER_RECOVERY
            base != null && base.primary != NetworkState.UNKNOWN && base.primary !in setOf(NetworkState.NORMAL, NetworkState.FILTERED) -> base.primary
            restrictions.size >= 2 -> NetworkState.FILTERED
            restrictions.size == 1 -> restrictions.first()
            base != null -> base.primary
            anyVerified -> NetworkState.NORMAL
            else -> NetworkState.UNKNOWN
        }
        val confidence = when {
            primary == NetworkState.UNKNOWN -> 0.0
            base != null -> base.confidence
            else -> (0.4 + 0.1 * latestPerPath.size).coerceAtMost(0.7)
        }
        if (basis.isEmpty()) basis += "no measurement in this network session yet"
        return NetworkAssessment(s?.id, primary, restrictions, confidence, basis, now)
    }

    /**
     * The decision after a search step: connect a verified path, try the next strategy, escalate to recovery
     * engines, or stop with NO_VERIFIED_EGRESS_AFTER_RECOVERY. Never "connect anyway".
     */
    fun decide(stage: RecoveryStage, verifiedRef: String?, recoveryAvailable: Boolean): RecoveryDecision = when {
        verifiedRef != null -> RecoveryDecision(RecoveryStage.FIRST_VERIFIED_PATH, RecoveryDecision.Action.CONNECT_VERIFIED, verifiedRef,
            "a real request went through this path on the current network")
        stage.ordinal < RecoveryStage.RECOVERY_ENGINES.ordinal && recoveryAvailable -> RecoveryDecision(RecoveryStage.RECOVERY_ENGINES,
            RecoveryDecision.Action.ESCALATE_TO_RECOVERY, null, "no saved server carried traffic; trying recovery engines and other families")
        stage.ordinal < RecoveryStage.SAFE_VARIANTS.ordinal -> RecoveryDecision(RecoveryStage.SAFE_VARIANTS,
            RecoveryDecision.Action.TRY_NEXT, null, "trying safe variants")
        else -> RecoveryDecision(RecoveryStage.NO_VERIFIED_EGRESS_AFTER_RECOVERY, RecoveryDecision.Action.STOP_NO_VERIFIED_PATH, null,
            "no available method carried international traffic on this network")
    }

    companion object {
        /** A real result younger than this is current truth for its path. */
        const val FRESH_MS = 10 * 60_000L
        /** Older than fresh, younger than this: still this session's evidence, shown as aging. */
        const val AGING_MS = 30 * 60_000L
        /** A failure younger than this keeps the path from being started automatically. */
        const val RECENT_FAIL_MS = 10 * 60_000L
        /** A pass younger than this is not repeated before connecting; the tunnel's own check follows anyway. */
        const val REUSE_PASS_MS = 60_000L
        /** Two passes at least this far apart make a path stable (same as the LAB's window). */
        const val STABLE_WINDOW_MS = 3_000L
        const val MAX_RECORDS = 600
    }
}

/**
 * How the brain names a path: the saved config's identity plus its variant (fragment, fingerprint, ECH, MTU,
 * ALPN), so a failed variant never marks its saved config as failed and the reverse. Copies keep the saved
 * config's identity (endpoint resolution pins it), so a resolved address is the same path.
 */
object PathRef {
    fun of(p: com.example.data.model.VlessProfile): String {
        val variant = listOf(p.finalMask, p.fingerprint, p.echConfigList, p.extraSettings, p.alpn, p.cipherSuites, p.targetStrategy)
            .joinToString("|")
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(variant.toByteArray(Charsets.UTF_8))
        return p.effectiveFingerprint.take(32) + "~" + digest.take(4).joinToString("") { "%02x".format(it) }
    }
}

/**
 * The app-wide brain. RealDelayProbe reports every real test here (installed by RayApplication), the VPN
 * service and the LAB open sessions and record tunnel and network evidence, and failover reads eligibility.
 */
object ConnectivityBrain {
    val book = EvidenceBook()

    /** Gives the current network key (NetworkIdentity); installed by the app, null in unit tests. */
    @Volatile var networkKey: (() -> String?)? = null

    /** Opens or continues the session of the phone's current network; null when no key is available. */
    fun refreshSession(): CurrentNetworkSession? {
        val key = runCatching { networkKey?.invoke() }.getOrNull() ?: return book.session()
        return book.observeNetwork(key)
    }

    /** RealDelayProbe's observer: every finished real test becomes path evidence of the current session. */
    fun onProbe(profile: com.example.data.model.VlessProfile, outcome: com.example.xray.RealDelayProbe.Outcome) {
        refreshSession() ?: return
        val ref = PathRef.of(profile)
        val kind = com.example.vpn.stealth.ConnectionKind.of(profile)
        val v6 = profile.address.contains(':')
        val af = if (v6) EvidenceAddressFamily.IPV6 else EvidenceAddressFamily.ANY
        when (outcome) {
            is com.example.xray.RealDelayProbe.Outcome.Delay -> book.recordPath(EvidenceSource.APP_TEST,
                if (outcome.targetsTried > 1) MeasurementType.MULTI_TARGET else MeasurementType.REAL_REQUEST, ref, kind, true,
                latencyMs = outcome.latencyMs, targetsPassed = outcome.targetsPassed, targetsTried = outcome.targetsTried, addressFamily = af)
            is com.example.xray.RealDelayProbe.Outcome.Failed -> if (outcome.request) book.recordPath(EvidenceSource.APP_TEST,
                if (outcome.targetsTried > 1) MeasurementType.MULTI_TARGET else MeasurementType.REAL_REQUEST, ref, kind, false,
                failureStage = com.example.vpn.diagnostics.FailureStage.HTTP_REQUEST_FAILED.name, targetsTried = outcome.targetsTried, addressFamily = af)
            is com.example.xray.RealDelayProbe.Outcome.NotRun -> Unit
        }
    }
}
