package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Shir o Khorshid (شیر و خورشید): the community Psiphon fork for Iran, run as its own program like [PsiphonSidecar].
 *
 * The program is the ConsoleClient of github.com/shirokhorshid/psiphon-tunnel-core (branch `shirokhorshid`, GPL-3.0,
 * built by scripts/engines/shirokhorshid.sh at a pinned commit). That fork is upstream psiphon-tunnel-core 4d6eb0e8
 * (the commit [PsiphonSidecar]'s program is built from) plus:
 * - CDN fronting: FRONTED-MEEK-CDN-OSSH / -HTTP-OSSH / -QUIC-OSSH dial fronted meek to CDN edge addresses taken from
 *   `FrontedMeekCDNScanSpec` (user edges, IPv4 or CIDR) and the fork's built-in edge corpus
 *   (`FrontedMeekCDNScanUseBuiltInSpec`). The core scans the candidates, remembers working ones per NetworkID in its
 *   data store and prints `cdn fronting scan found (ip: …)` when one carries a tunnel ([ShirokhorshidEdgeMemory]).
 * - In-proxy (Conduit) filtering: `InproxyRejectProxyCIDRs` rejects a matched conduit whose address is inside the
 *   listed ranges. MAXIMUS fills it from the bundled Iran GeoIP list ([GeoIpCidrs]) so a conduit inside Iran is
 *   never used as the way out.
 *
 * Nothing about the fork's private build is used: its conduit compartment ID, its build-time server entries and its
 * MaxMind database come from its own CI secrets. The network settings are the same ones [PsiphonSidecar] reads
 * (this build's Psiphon settings, an imported Psiphon.config, or the bundled public profile).
 */
class ShirokhorshidSidecar(
    private val configProvider: () -> String?,
    /** CIDRs of conduits to reject (the bundled Iran list); read once per start. */
    private val rejectCidrs: () -> List<String> = { emptyList() },
    /** Edges the core's scanner found on [network] before; prepended to the user's edges in auto mode. */
    private val rememberedEdges: (network: String?) -> List<String> = { ShirokhorshidEdgeMemory.edges(it) }
) : SidecarEngine {
    override val id: String = ID
    override val binary: String = BINARY

    override fun handles(profile: VlessProfile): Boolean =
        ProfileExtras.read(profile).optString(PsiphonSidecar.KEY_ENGINE) == ID

    override fun problem(profile: VlessProfile): String? {
        val raw = runCatching { configProvider() }.getOrNull()?.trim()
        if (raw.isNullOrEmpty()) return MISSING_SETTINGS
        PsiphonSidecar.validateNetworkSettings(raw)?.let { return it }
        return Options.read(profile).problem()
    }

    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        problem(profile)?.let { throw IllegalStateException(it) }
        ShirokhorshidEdgeMemory.listen()
        val options = Options.read(profile)
        val source = JSONObject(requireNotNull(configProvider()).trim())
        val dataDir = File(context.workDir, PsiphonSidecar.DATA_DIR).apply { mkdirs() }
        // An empty data store has nothing to dial (build 175): start from the Psiphon servers this phone already knows.
        when (val seeded = PsiphonServerSeed.seed(dataDir, PsiphonServerSeed.psiphonRoots(context.workDir))) {
            is PsiphonServerSeed.Result.Seeded -> runCatching {
                com.example.xray.XrayLogManager.i("VPN", "Shir o Khorshid: no Psiphon servers in its own data store; " +
                    "starting from the ${if (seeded.entries >= 64) "64+" else seeded.entries.toString()} known to Psiphon.")
            }
            is PsiphonServerSeed.Result.Failed -> runCatching {
                com.example.xray.XrayLogManager.w("VPN", "Shir o Khorshid: could not copy Psiphon's known servers (${seeded.reason}).")
            }
            else -> Unit
        }
        val chained = context.upstreamSocks != null
        val base = PsiphonSidecar.buildConfig(source, options.region, context.socksPort, dataDir,
            context.upstreamSocks, context.upstreamUser, context.upstreamPass, tcpOnly = false)
        val link = runCatching { Sidecars.psiphonNetworkIdentity?.invoke() }.getOrNull()
        val metered = runCatching { Sidecars.psiphonLinkMetered?.invoke() }.getOrNull() == true
        PsiphonSidecar.networkId(link, chained)?.let { base.put("NetworkID", it) }
        val network = runCatching { com.example.vpn.connections.BuiltinMethodMemory.network() }.getOrNull()
        // Build 181: Iranian conduits only when Smart Connect found a whitelist / near-shutdown network.
        val allowIran = takeIranConduitsAllowed()
        if (allowIran) runCatching { com.example.xray.XrayLogManager.i("VPN",
            "Shir o Khorshid: whitelist / near-shutdown network: conduits inside Iran are allowed for this start.") }
        val config = applyOptions(base, options,
            rejectCidrs = if (options.avoidIranConduits && !allowIran) runCatching { rejectCidrs() }.getOrDefault(emptyList()) else emptyList(),
            remembered = if (options.cdnFronting) runCatching { rememberedEdges(network) }.getOrDefault(emptyList()) else emptyList(),
            chained = chained, fronted = PsiphonSidecar.isFrontedOnly(profile))
        val configFile = File(context.workDir, PsiphonSidecar.CONFIG_FILE)
        PrivateEngineFile.write(configFile, config.toString())
        val linkDns = if (!chained && !source.has("DNSResolverAlternateServers"))
            runCatching { Sidecars.psiphonLinkDns?.invoke().orEmpty() }.getOrDefault(emptyList()) else emptyList()
        return SidecarLaunch(
            command = listOf(
                context.executable.absolutePath,
                "-config", configFile.absolutePath,
                "-dataRootDirectory", dataDir.absolutePath,
                "-formatNotices"
            ),
            environment = if (linkDns.isEmpty()) emptyMap() else mapOf(LINK_DNS_ENV to linkDns.joinToString(",")),
            readyTimeoutMs = readyTimeoutMs(options, link, metered, chained),
            // Like Psiphon's, its SOCKS server cannot check a login.
            socksAuth = false,
            readyLine = PsiphonSidecar.TUNNEL_UP
        )
    }

    /** How the fork connects. [AUTO] lets the core choose among every protocol it may use. */
    enum class Mode(val key: String) {
        AUTO("auto"),
        /** Only in-proxy (Conduit volunteer) protocols. */
        CONDUIT("conduit"),
        /** Only direct (non in-proxy) protocols, plus the CDN fronted ones when CDN fronting is on. */
        DIRECT("direct");

        companion object {
            fun of(key: String?): Mode = entries.firstOrNull { it.key == key?.trim()?.lowercase() } ?: AUTO
        }
    }

    /** What the user chose for this engine; stored in the profile's extras so a start needs nothing else. */
    data class Options(
        val mode: Mode = Mode.AUTO,
        /** Exit country (two letters), or empty: Psiphon picks the best server in any region. */
        val region: String = "",
        val cdnFronting: Boolean = true,
        /** User-supplied CDN edges (IPv4 or IPv4 CIDR), in the user's order. Empty: scanner only. */
        val edges: List<String> = emptyList(),
        /** Reject conduits inside Iran (Conduit and Auto modes). */
        val avoidIranConduits: Boolean = true,
        /**
         * Build 181: a personal pairing ID (InproxyClientPersonalCompartmentID) from a friend's Conduit, so this phone uses
         * only that friend's conduit(s). Empty: the shared pool. Psiphon then uses only in-proxy protocols.
         */
        val personalPairing: String = ""
    ) {
        fun problem(): String? {
            if (!PsiphonSidecar.validRegion(region)) return "The exit country must be a two-letter country code, or Auto."
            if (edges.size > MAX_EDGES) return "At most $MAX_EDGES CDN edge addresses can be used."
            edges.firstOrNull { !validEdge(it) }?.let { return "Not a valid CDN edge address: $it (use an IPv4 address or range such as 23.215.0.0/24)." }
            if (personalPairing.isNotEmpty() && !validPairingId(personalPairing))
                return "The personal pairing code is not valid. Paste the code or link your Conduit shares."
            return null
        }

        fun writeTo(extras: JSONObject): JSONObject = extras
            .put(KEY_MODE, mode.key)
            .put(PsiphonSidecar.KEY_REGION, region)
            .put(KEY_CDN, cdnFronting)
            .put(KEY_EDGES, JSONArray(edges))
            .put(KEY_AVOID_IR, avoidIranConduits)
            .put(KEY_PAIRING, personalPairing)

        companion object {
            fun read(profile: VlessProfile): Options = read(ProfileExtras.read(profile))

            fun read(extras: JSONObject): Options = Options(
                mode = Mode.of(extras.optString(KEY_MODE)),
                region = extras.optString(PsiphonSidecar.KEY_REGION).trim().uppercase(),
                cdnFronting = extras.optBoolean(KEY_CDN, true),
                edges = extras.optJSONArray(KEY_EDGES)?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    .map { it.trim() }.filter { it.isNotEmpty() },
                avoidIranConduits = extras.optBoolean(KEY_AVOID_IR, true),
                personalPairing = extras.optString(KEY_PAIRING).trim()
            )
        }
    }

    companion object {
        const val ID = "shirokhorshid"
        const val BINARY = "shirokhorshid"
        /** Upstream commit of the fork's tunnel core this build pins (scripts/engines/shirokhorshid.sh). */
        const val REVISION = "520f37c980df"
        const val KEY_MODE = "sokMode"
        const val KEY_CDN = "sokCdn"
        const val KEY_EDGES = "sokEdges"
        const val KEY_AVOID_IR = "sokAvoidIr"
        const val KEY_PAIRING = "sokPairing"

        /** Psiphon's personal compartment ID: 32 bytes as unpadded standard base64 (inproxy.ID, 43 characters). Pure. */
        fun validPairingId(value: String): Boolean = value.length == 43 &&
            Regex("^[A-Za-z0-9+/]{43}$").matches(value) &&
            // 43 characters carry 258 bits; 32 bytes use 256, so the last character's two low bits are zero.
            BASE64.indexOf(value.last()) % 4 == 0

        private const val BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        /**
         * The pairing ID from what the user pasted: the ID itself, or a share link or message that carries it (the first
         * 43-character base64 token that decodes to 32 bytes; URL-safe characters and %-escapes are normalised). Null if
         * none. Pure.
         */
        fun parsePairing(text: String): String? {
            val raw = text.trim()
            // URLDecoder turns '+' into a space, so the text as pasted is searched first.
            val decoded = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
            return sequenceOf(raw, decoded).flatMap { Regex("[A-Za-z0-9+/_-]{43}").findAll(it) }
                .map { it.value.replace('-', '+').replace('_', '/') }
                .firstOrNull { validPairingId(it) }
        }

        /**
         * Build 181 (3.3): Smart Connect allows conduits inside Iran for the next start, only on a whitelist or
         * near-shutdown network ([com.example.vpn.smart.SmartTiers.allowIranianConduits]). Read once by the next start.
         */
        @Volatile var iranConduitsAllowedOnce: Boolean = false

        /** Reads and clears [iranConduitsAllowedOnce]. */
        fun takeIranConduitsAllowed(): Boolean = iranConduitsAllowedOnce.also { iranConduitsAllowedOnce = false }
        const val MAX_EDGES = 64
        /** Read by the ConsoleClient DNS patch shared with Psiphon (scripts/engines/psiphon-link-dns.py). */
        const val LINK_DNS_ENV = "MAXIMUS_PSIPHON_LINK_DNS"

        const val MISSING_SETTINGS =
            "Shir o Khorshid needs Psiphon network settings, which are not included. Import a Psiphon.config file in Settings → Engine updates."

        /** The CDN scan and conduit matching take longer than a plain Psiphon dial (a scan found an edge after ~40 s). */
        const val SCAN_READY_TIMEOUT_MS = 120_000L

        /** In-proxy protocols, from the fork's common/protocol (and its Android app's Conduit list). */
        val CONDUIT_PROTOCOLS = listOf(
            "INPROXY-WEBRTC-SSH", "INPROXY-WEBRTC-OSSH", "INPROXY-WEBRTC-TLS-OSSH",
            "INPROXY-WEBRTC-UNFRONTED-MEEK-OSSH", "INPROXY-WEBRTC-UNFRONTED-MEEK-HTTPS-OSSH",
            "INPROXY-WEBRTC-UNFRONTED-MEEK-SESSION-TICKET-OSSH", "INPROXY-WEBRTC-FRONTED-MEEK-OSSH",
            "INPROXY-WEBRTC-FRONTED-MEEK-HTTP-OSSH", "INPROXY-WEBRTC-QUIC-OSSH",
            "INPROXY-WEBRTC-FRONTED-MEEK-QUIC-OSSH", "INPROXY-WEBRTC-SHADOWSOCKS-OSSH"
        )

        /** The fork's CDN fronted meek protocols. */
        val CDN_PROTOCOLS = listOf("FRONTED-MEEK-CDN-OSSH", "FRONTED-MEEK-CDN-HTTP-OSSH", "FRONTED-MEEK-CDN-QUIC-OSSH")

        /** Direct (not in-proxy) protocols. */
        val DIRECT_PROTOCOLS = listOf(
            "SSH", "OSSH", "TLS-OSSH", "UNFRONTED-MEEK-OSSH", "UNFRONTED-MEEK-HTTPS-OSSH",
            "UNFRONTED-MEEK-SESSION-TICKET-OSSH", "QUIC-OSSH", "SHADOWSOCKS-OSSH", "FRONTED-MEEK-OSSH",
            "FRONTED-MEEK-HTTP-OSSH", "FRONTED-MEEK-QUIC-OSSH"
        )

        /** Fields the app owns for this engine: whatever the network settings say about them is replaced. */
        private val OWNED_FIELDS = listOf(
            "FrontedMeekCDNScanUseBuiltInSpec", "FrontedMeekCDNScanSpec", "FrontedMeekDialOverrides",
            "FrontedMeekDialOverridesProbability", "InproxyRejectProxyCIDRs", "InproxyRejectProxyCountryCodes",
            "GeoIPDatabasePath", "LocalProxyUsername", "LocalProxyPassword", "InproxyClientPersonalCompartmentID"
        )

        private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)(\\.(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)){3}$")

        /** An IPv4 address, or an IPv4 CIDR from /8 to /32. Pure. */
        fun validEdge(value: String): Boolean {
            val parts = value.trim().split('/')
            if (parts.size > 2 || !IPV4.matches(parts[0])) return false
            if (parts.size == 1) return true
            val prefix = parts[1].toIntOrNull() ?: return false
            return prefix in 8..32 && parts[1] == prefix.toString()
        }

        /** Edges from free text (one per line, or separated by spaces, commas or semicolons), deduplicated. Pure. */
        fun parseEdges(text: String): List<String> =
            text.split(Regex("[\\s,;]+")).map { it.trim() }.filter { it.isNotEmpty() }.distinct()

        /** A Shir o Khorshid profile with [options]. */
        fun profile(options: Options = Options()): VlessProfile {
            val extras = options.writeTo(JSONObject().put(PsiphonSidecar.KEY_ENGINE, ID))
            return VlessProfile(
                name = if (options.region.isEmpty()) "Shir o Khorshid" else "Shir o Khorshid (${options.region})",
                address = ID,
                port = 0,
                uuid = "",
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.MIXED,
                extraSettings = extras.toString()
            )
        }

        internal fun readyTimeoutMs(options: Options, link: String?, metered: Boolean, chained: Boolean): Long =
            if (options.cdnFronting || options.mode == Mode.CONDUIT) SCAN_READY_TIMEOUT_MS
            else PsiphonSidecar.readyTimeoutMs(link, metered, chained)

        /**
         * The fork-specific part of the config: protocol limits for [Options.mode], CDN fronting and conduit
         * filtering. [config] is [PsiphonSidecar.buildConfig]'s output and is changed in place. Pure apart from that.
         */
        fun applyOptions(
            config: JSONObject,
            options: Options,
            rejectCidrs: List<String> = emptyList(),
            remembered: List<String> = emptyList(),
            chained: Boolean = false,
            /**
             * Build 178 field report (Irancell): Shir o Khorshid listed 26 regions and brought no tunnel up in 120 s. After
             * such a failure on a network the next start runs only the CDN-fronted protocols (the fork's CDN meek and
             * Psiphon's fronted meek), with tactics off as the fork's own CDN mode does. Same engine, so a manual pick may.
             */
            fronted: Boolean = false
        ): JSONObject {
            val owned = OWNED_FIELDS.map { it.lowercase() }.toSet()
            config.keys().asSequence().filter { it.lowercase() in owned }.toList().forEach { config.remove(it) }
            val cdn = options.cdnFronting && options.mode != Mode.CONDUIT
            if (cdn) {
                config.put("FrontedMeekCDNScanUseBuiltInSpec", true)
                val edges = (options.edges + remembered).map { it.trim() }.filter { validEdge(it) }.distinct().take(MAX_EDGES)
                if (edges.isNotEmpty()) config.put("FrontedMeekCDNScanSpec", JSONObject().put("IPCandidates", JSONArray(edges)))
            }
            val paired = options.personalPairing.isNotEmpty() && validPairingId(options.personalPairing)
            if (paired) config.put("InproxyClientPersonalCompartmentID", options.personalPairing)
            val limit: List<String>? = when {
                // A personal pairing uses only in-proxy protocols (the core enforces it too): never limit them away.
                paired -> CONDUIT_PROTOCOLS
                fronted && options.mode != Mode.CONDUIT -> (if (cdn) CDN_PROTOCOLS else emptyList()) + PsiphonSidecar.FRONTED_PROTOCOLS
                options.mode == Mode.AUTO -> null
                options.mode == Mode.CONDUIT -> CONDUIT_PROTOCOLS
                else -> DIRECT_PROTOCOLS + (if (cdn) CDN_PROTOCOLS else emptyList())
            }
            if (limit != null) {
                // Initial limits from the network settings would otherwise narrow the first round outside the mode.
                config.remove("InitialLimitTunnelProtocols")
                config.remove("InitialLimitTunnelProtocolsCandidateCount")
                config.remove("TunnelProtocol")
                config.put("LimitTunnelProtocols", JSONArray(limit))
            }
            // As the fork's own app does, CDN and direct modes skip tactics: tactics can re-enable protocols the
            // mode excludes, and its requests need a capable (non-CDN) server first.
            if (options.mode == Mode.DIRECT || fronted && options.mode != Mode.CONDUIT) config.put("DisableTactics", true)
            // A fronted start is not pinned to a region: few regions host fronted servers.
            if (fronted && options.mode != Mode.CONDUIT) config.remove("EgressRegion")
            if ((options.mode != Mode.DIRECT || paired) && rejectCidrs.isNotEmpty()) {
                config.put("InproxyRejectProxyCIDRs", JSONArray(rejectCidrs))
            }
            if (chained) {
                // A chain hop reaches its servers through the next hop's SOCKS port: TCP protocols only.
                val current = config.optJSONArray("LimitTunnelProtocols")
                    ?.let { a -> (0 until a.length()).map { a.optString(it) } }
                    ?: (DIRECT_PROTOCOLS + CDN_PROTOCOLS + CONDUIT_PROTOCOLS)
                val tcp = current.filter { it in PsiphonSidecar.TCP_PROTOCOLS || it in CDN_TCP_PROTOCOLS }
                require(tcp.isNotEmpty()) { "Shir o Khorshid in this mode has no TCP protocol for a chain hop." }
                config.put("LimitTunnelProtocols", JSONArray(tcp))
            }
            return config
        }

        private val CDN_TCP_PROTOCOLS = setOf("FRONTED-MEEK-CDN-OSSH", "FRONTED-MEEK-CDN-HTTP-OSSH")

        /** How long a failed start on a network steers the next one ([nextFronted]). */
        const val FRONTED_MEMORY_MS = 6 * 60 * 60_000L

        /**
         * Whether the next start on a network runs CDN-fronted only: after a failed start it switches to the other form
         * (all protocols ↔ CDN-fronted), after a working one it keeps the form that worked; with no outcome on this network
         * within [FRONTED_MEMORY_MS] (or a first start): all protocols. Pure.
         */
        fun nextFronted(lastFailed: Boolean?, lastFronted: Boolean): Boolean = when (lastFailed) {
            true -> !lastFronted
            false -> lastFronted
            null -> false
        }

        private fun frontedKey(network: String) = "shirokhorshidFronted#$network"

        fun lastFronted(network: String?): Boolean = network != null && runCatching {
            com.example.vpn.connections.BuiltinMethodMemory.read(frontedKey(network)) == "1"
        }.getOrDefault(false)

        fun recordFronted(network: String?, fronted: Boolean) {
            if (network.isNullOrBlank()) return
            runCatching { com.example.vpn.connections.BuiltinMethodMemory.write(frontedKey(network), if (fronted) "1" else "0") }
        }
    }
}

/**
 * CDN edges Shir o Khorshid's scanner found carrying a tunnel, per network (a [BuiltinMethodMemory] network key), so
 * the next start on that network tries them first even after the engine's data directory is cleared. Only addresses
 * that passed the core's own scan are kept, newest first, at most [MAX] per network, for [TTL_MS].
 */
object ShirokhorshidEdgeMemory {
    const val MAX = 8
    const val TTL_MS = 3 * 24 * 60 * 60_000L
    private val FOUND = Regex("cdn fronting scan found \\(ip: ([0-9.\\\\]+)")
    @Volatile private var subscription: AutoCloseable? = null

    private fun key(network: String) = "shirokhorshidEdges#$network"

    /** The edge in a `cdn fronting scan found (ip: 92\.123\.102\.43, …)` notice, or null. Pure. */
    fun foundEdge(line: String): String? {
        if (line.length > 4_096) return null
        val raw = FOUND.find(line)?.groupValues?.get(1) ?: return null
        val ip = raw.replace("\\", "")
        return ip.takeIf { ShirokhorshidSidecar.validEdge(it) && !it.contains('/') }
    }

    /** "ip@time;ip@time" newest first. Pure. */
    internal fun encode(entries: List<Pair<String, Long>>): String = entries.joinToString(";") { "${it.first}@${it.second}" }

    internal fun decode(value: String?): List<Pair<String, Long>> = value.orEmpty().split(';').mapNotNull { item ->
        val ip = item.substringBefore('@')
        val at = item.substringAfter('@', "").toLongOrNull() ?: return@mapNotNull null
        (ip to at).takeIf { ShirokhorshidSidecar.validEdge(ip) }
    }

    fun record(network: String?, ip: String, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank() || !ShirokhorshidSidecar.validEdge(ip)) return
        runCatching {
            val memory = com.example.vpn.connections.BuiltinMethodMemory
            val kept = decode(memory.read(key(network))).filter { it.first != ip && now - it.second in 0 until TTL_MS }
            memory.write(key(network), encode((listOf(ip to now) + kept).take(MAX)))
        }
    }

    fun edges(network: String?, now: Long = System.currentTimeMillis()): List<String> {
        if (network.isNullOrBlank()) return emptyList()
        return runCatching {
            decode(com.example.vpn.connections.BuiltinMethodMemory.read(key(network)))
                .filter { now - it.second in 0 until TTL_MS }.map { it.first }
        }.getOrDefault(emptyList())
    }

    /** Watches the engine's output (only while it runs, via [EngineSignals]); idempotent. */
    fun listen() {
        if (subscription != null) return
        synchronized(this) {
            if (subscription != null) return
            subscription = EngineSignals.subscribe { source, line ->
                if (source.removePrefix("lab-") != ShirokhorshidSidecar.ID) return@subscribe
                val ip = foundEdge(line) ?: return@subscribe
                record(runCatching { com.example.vpn.connections.BuiltinMethodMemory.network() }.getOrNull(), ip)
            }
        }
    }
}

/**
 * CIDRs of one country from a V2Ray `geoip.dat` (GeoIPList protobuf: repeated GeoIP{country_code=1, cidr=2:
 * CIDR{ip=1 bytes, prefix=2}}). IPv4 only. Used for the bundled Chocolate4U `geoip-lite.dat`, whose only country is IR.
 */
object GeoIpCidrs {
    fun ipv4(data: ByteArray, country: String, limit: Int = 20_000): List<String> {
        val out = ArrayList<String>()
        val reader = Proto(data, 0, data.size)
        while (reader.more()) {
            val (field, wire) = reader.tag() ?: break
            if (field == 1 && wire == 2) {
                val entry = reader.bytes() ?: break
                parseEntry(data, entry.first, entry.second, country, out, limit)
                if (out.size >= limit) break
            } else if (!reader.skip(wire)) break
        }
        return out
    }

    private fun parseEntry(data: ByteArray, start: Int, end: Int, country: String, out: MutableList<String>, limit: Int) {
        val r = Proto(data, start, end)
        var code: String? = null
        val cidrs = ArrayList<Pair<Int, Int>>()
        while (r.more()) {
            val (field, wire) = r.tag() ?: return
            when {
                field == 1 && wire == 2 -> code = r.bytes()?.let { String(data, it.first, it.second - it.first, Charsets.UTF_8) } ?: return
                field == 2 && wire == 2 -> cidrs += r.bytes() ?: return
                else -> if (!r.skip(wire)) return
            }
        }
        if (!code.equals(country, ignoreCase = true)) return
        for ((s, e) in cidrs) {
            val c = Proto(data, s, e)
            var ip: ByteArray? = null
            var prefix = -1
            while (c.more()) {
                val (field, wire) = c.tag() ?: break
                when {
                    field == 1 && wire == 2 -> ip = c.bytes()?.let { data.copyOfRange(it.first, it.second) }
                    field == 2 && wire == 0 -> prefix = c.varint()?.toInt() ?: -1
                    else -> if (!c.skip(wire)) break
                }
            }
            val bytes = ip ?: continue
            if (bytes.size != 4 || prefix !in 0..32) continue
            out += bytes.joinToString(".") { (it.toInt() and 0xff).toString() } + "/" + prefix
            if (out.size >= limit) return
        }
    }

    private class Proto(val data: ByteArray, var pos: Int, val end: Int) {
        fun more() = pos < end
        fun varint(): Long? {
            var shift = 0
            var result = 0L
            while (pos < end && shift < 64) {
                val b = data[pos++].toInt() and 0xff
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
            return null
        }
        fun tag(): Pair<Int, Int>? = varint()?.let { (it ushr 3).toInt() to (it and 7).toInt() }
        fun bytes(): Pair<Int, Int>? {
            val len = varint() ?: return null
            if (len < 0 || len > end - pos) return null
            val start = pos
            pos += len.toInt()
            return start to pos
        }
        fun skip(wire: Int): Boolean = when (wire) {
            0 -> varint() != null
            1 -> (pos + 8 <= end).also { if (it) pos += 8 }
            2 -> bytes() != null
            5 -> (pos + 4 <= end).also { if (it) pos += 4 }
            else -> false
        }
    }
}

/**
 * Build 179 device test (Irancell): Shir o Khorshid connected a server and then sat in "the tunnel did not become ready"
 * for the whole 120 s, and the CDN-fronted-only form (the fork's CDN meek and Psiphon's fronted meek) only ran on the
 * *next* start. Now the same pick switches to it once, inside its own readiness window, when a server connected and no
 * tunnel followed within [STAGE3_GRACE_MS] (or by [switchAtMs]). Same engine, so a manual pick may; never inside a chain
 * (the chain hop has its own TCP limits) or when the start is already CDN-fronted. Pure, so the rules are unit-tested.
 */
object ShirokhorshidFrontedRetry {
    /** A server connected this long ago and still no tunnel: the all-protocols start is given up. */
    const val STAGE3_GRACE_MS = 25_000L
    /**
     * Build 180 device test (A3): the switch fired at 53 s, because the grace counted from stage 3 (reached ~28 s in).
     * From [SWITCH_AFTER_START_MS] after the start, a server that has been connected for [STAGE3_MIN_MS] with no tunnel
     * is enough.
     */
    const val SWITCH_AFTER_START_MS = 25_000L
    const val STAGE3_MIN_MS = 8_000L
    /** What the CDN-fronted start gets at least; the first start is cut so this is left of the window. */
    const val FRONTED_MIN_MS = 45_000L

    fun applies(engineId: String, carrierStarted: Boolean, chained: Boolean, alreadyFronted: Boolean): Boolean =
        engineId == ShirokhorshidSidecar.ID && !carrierStarted && !chained && !alreadyFronted

    /** When, after the start, the first start is cut at the latest while a server is connected. */
    fun switchAtMs(readyTimeoutMs: Long): Long = (readyTimeoutMs - FRONTED_MIN_MS).coerceAtLeast(readyTimeoutMs / 2)

    /** The first start is given up for the CDN-fronted one: a server connected ([stage] 3) and no tunnel followed. */
    fun giveUp(stage: Int, stage3Since: Long?, startedAt: Long, now: Long, readyTimeoutMs: Long): Boolean =
        stage >= 3 && stage3Since != null && (now - stage3Since >= STAGE3_GRACE_MS || now - startedAt >= switchAtMs(readyTimeoutMs) ||
            now - startedAt >= SWITCH_AFTER_START_MS && now - stage3Since >= STAGE3_MIN_MS)

    /** The first (all-protocols) phase, kept for the final failure text once the fronted phase also fails. Pure. */
    fun firstPhaseText(elapsedMs: Long, stage3AfterMs: Long?, dials: PsiphonSidecar.StartupDials?): String =
        "first phase (all protocols, ${elapsedMs / 1000}s" + (stage3AfterMs?.let { "; server connected at ${it / 1000}s" } ?: "") +
            "): " + (dials?.summary() ?: "no dial counts")

    /** The CDN-fronted start's window: what is left of [readyTimeoutMs], never under a third of [FRONTED_MIN_MS]. */
    fun frontedWindowMs(readyTimeoutMs: Long, elapsedMs: Long): Long =
        (readyTimeoutMs - elapsedMs).coerceAtLeast(FRONTED_MIN_MS / 3)

    const val TEXT = "a server connected but no tunnel followed; switching this pick to Shir o Khorshid's CDN-fronted " +
        "protocols only (same engine) for the rest of its window"
}
