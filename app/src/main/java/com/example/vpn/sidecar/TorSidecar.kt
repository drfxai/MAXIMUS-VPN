package com.example.vpn.sidecar

import com.example.data.model.AppSettings
import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Tor through bridges, for networks where nothing else gets out. Tor itself runs inside the app from
 * the tor-android library (BSD-3-Clause); its bridges (Snowflake, obfs4, WebTunnel, meek) run in
 * lyrebird, a separate program Tor starts, shipped as `liblyrebird.so`.
 *
 * Snowflake needs no bridge of the user's: it reaches volunteers' browsers through a CDN-fronted
 * broker, which is why it is the default. Users with their own bridge lines (obfs4, WebTunnel, from
 * bridges.torproject.org or Telegram's @GetBridgesBot) put them in the profile.
 *
 * Tor has no UDP; QUIC falls back to TCP, and DNS goes through Xray's own DNS over the tunnel.
 */
class TorSidecar(
    /** Starts the Tor library with a torrc; set by the VPN service, null where Android is absent. */
    private val starter: ((torrc: String, socksPort: Int) -> RunningEngine)?
) : SidecarEngine {
    override val id: String = ID
    override val binary: String = BINARY

    override fun handles(profile: VlessProfile): Boolean =
        profile.protocolType == ProtocolType.MIXED && extras(profile).optString(KEY_ENGINE) == ID

    override fun problem(profile: VlessProfile): String? = problemOf(profile)

    override fun prepare(profile: VlessProfile, settings: AppSettings, context: SidecarContext): SidecarLaunch {
        val torrc = torrc(bridges(profile), context.socksPort, context.executable)
        File(context.workDir, TORRC).writeText(torrc)
        // Tor reads the name and password of a SOCKS request as circuit isolation, not as a login.
        return SidecarLaunch(command = emptyList(), readyTimeoutMs = READY_TIMEOUT_MS, socksAuth = false)
    }

    override fun startInApp(launch: SidecarLaunch, context: SidecarContext): RunningEngine? {
        val start = starter ?: error("Tor needs Android to run")
        return start(File(context.workDir, TORRC).readText(), context.socksPort)
    }

    companion object {
        const val ID = "tor"
        const val BINARY = "lyrebird"
        const val KEY_ENGINE = "engine"
        const val KEY_BRIDGES = "bridges"
        const val TORRC = "torrc"
        /** Snowflake can need a minute or more to find a volunteer and build a circuit. */
        const val READY_TIMEOUT_MS = 120_000L

        /**
         * Tor's built-in Snowflake bridges, as shipped with Snowflake v2.11.0 (client/torrc), the
         * version lyrebird is built with.
         */
        private const val ICE = "stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478," +
            "stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478," +
            "stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443"
        val SNOWFLAKE = listOf(
            "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 " +
                "url=https://1098762253.rsc.cdn77.org/ fronts=www.cdn77.com,www.phpmyadmin.net ice=$ICE utls-imitate=hellorandomizedalpn",
            "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA " +
                "url=https://1098762253.rsc.cdn77.org/ fronts=www.cdn77.com,www.phpmyadmin.net ice=$ICE utls-imitate=hellorandomizedalpn"
        )

        private val TRANSPORTS = setOf("snowflake", "obfs4", "webtunnel", "meek_lite")

        /** A Tor profile; [bridges] are bridge lines, one per line, or empty for Snowflake. */
        fun profile(bridges: String = "", name: String = ""): VlessProfile {
            val extras = JSONObject().put(KEY_ENGINE, ID)
            if (bridges.isNotBlank()) extras.put(KEY_BRIDGES, bridges.trim())
            return VlessProfile(
                name = name.trim().ifEmpty { if (bridges.isBlank()) "Tor (Snowflake)" else "Tor (my bridges)" },
                address = "tor",
                port = 0,
                uuid = "",
                profileType = ProfileType.VLESS,
                protocolType = ProtocolType.MIXED,
                extraSettings = extras.toString()
            )
        }

        fun problemOf(profile: VlessProfile): String? = runCatching { bridges(profile) }.exceptionOrNull()?.message

        /** The bridge lines to use; throws [IllegalArgumentException] for one Tor could not run. */
        fun bridges(profile: VlessProfile): List<String> {
            val lines = extras(profile).optString(KEY_BRIDGES).lines()
                .map { it.trim().removePrefix("Bridge ").trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            if (lines.isEmpty()) return SNOWFLAKE
            for (line in lines) {
                val transport = line.substringBefore(' ')
                require(transport in TRANSPORTS) {
                    "Bridge type '${transport.take(20)}' is not supported. Use obfs4, webtunnel, snowflake or meek_lite bridges."
                }
                // Each line becomes one torrc line: no line breaks or control characters may hide a second option.
                require(line.none { it.isISOControl() } && line.length <= 2_000) { "A bridge line is malformed." }
            }
            return lines
        }

        fun torrc(bridges: List<String>, socksPort: Int, lyrebird: File): String = buildString {
            appendLine("SocksPort 127.0.0.1:$socksPort")
            appendLine("ClientOnly 1")
            appendLine("AvoidDiskWrites 1")
            appendLine("UseBridges 1")
            appendLine("ClientTransportPlugin ${TRANSPORTS.joinToString(",")} exec ${lyrebird.absolutePath}")
            bridges.forEach { appendLine("Bridge $it") }
        }

        /** True once a request through Tor's SOCKS port reaches the internet, which needs a circuit. */
        fun carriesTraffic(socksPort: Int, timeoutMs: Int = 10_000): Boolean = try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", socksPort), 1_000)
                s.soTimeout = timeoutMs
                val out = s.getOutputStream()
                val input = s.getInputStream()
                out.write(byteArrayOf(5, 1, 0))
                if (input.read() != 5 || input.read() != 0) return false
                // CONNECT 1.1.1.1:443: Tor answers success only once it has a circuit.
                out.write(byteArrayOf(5, 1, 0, 1, 1, 1, 1, 1, 0x01, 0xBB.toByte()))
                input.read() == 5 && input.read() == 0
            }
        } catch (_: Exception) {
            false
        }

        private fun extras(profile: VlessProfile): JSONObject =
            profile.extraSettings.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    }
}
