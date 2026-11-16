package com.example.vpn.engine

import com.example.core.AppResult
import com.example.data.model.AppSettings
import com.example.data.model.EngineType
import com.example.data.model.TrafficStats
import com.example.data.model.VlessProfile
import kotlinx.coroutines.flow.MutableStateFlow
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import org.json.JSONArray
import org.json.JSONObject

/** Native AmneziaWG session using the descriptor owned by Android's VpnService. */
object AmneziaNativeEngine : NativeTunVpnEngine {
    const val ID = "amneziawg"
    private const val SETTINGS = "nativeAmneziaWg"
    private val library by lazy {
        try {
            System.loadLibrary("amneziawg_android")
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    data class Address(val ip: String, val prefix: Int)
    data class Config(val addresses: List<Address>, val dns: List<String>, val uapi: String)

    override val engineType = EngineType.XRAY
    override val engineVersion = "AmneziaWG native"
    override val statsFlow = MutableStateFlow(TrafficStats())
    @Volatile private var running = false

    fun handles(profile: VlessProfile) = ProfileExtras.read(profile).has(SETTINGS)

    fun available() = library

    fun config(profile: VlessProfile): Config {
        val data = ProfileExtras.read(profile).getJSONObject(SETTINGS)
        val addresses = data.getJSONArray("addresses").strings().map(::address)
        require(addresses.isNotEmpty()) { "Missing native tunnel address." }
        val dns = data.getJSONArray("dns").strings().map(::literal)
        require(dns.isNotEmpty()) { "Missing native tunnel DNS." }
        val uapi = configuration(profile, data.getJSONArray("routes").strings())
        return Config(addresses, dns, uapi)
    }

    /** Only import configurations this direct TUN adapter can preserve without a lossy conversion. */
    fun preferNative(profile: VlessProfile, iface: Map<String, String>, peer: Map<String, String>, peers: Int): VlessProfile {
        if (peers != 1 || iface.keys.any { it in setOf("j1", "j2", "j3", "itime", "table", "preup", "postup", "predown", "postdown") }) return profile
        val options = iface.filterKeys { it in NATIVE_OPTIONS }.filterValues { it.isNotBlank() }
        if (options.isEmpty()) return profile
        val imported = runCatching {
            val addresses = iface["address"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            val local = addresses.map(::address)
            require(local.isNotEmpty())
            val routes = peer["allowedips"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty)
            val defaults = local.map { if (':' in it.ip) "::/0" else "0.0.0.0/0" }.toSet()
            require(routes.toSet() == defaults) // Android captures both families; this first adapter supports full tunnels.
            val dns = iface["dns"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty).map(::literal)
            require(dns.isNotEmpty()) // Keep the DNS policy explicitly supplied with the profile.
            configuration(profile, routes)
            JSONObject().put("addresses", JSONArray(addresses)).put("dns", JSONArray(dns)).put("routes", JSONArray(routes))
        }.getOrNull() ?: return profile
        return profile.copy(extraSettings = ProfileExtras.read(profile).put(SETTINGS, imported).toString())
    }

    private fun configuration(profile: VlessProfile, routes: List<String>): String {
        val proxy = requireNotNull(com.example.vpn.sidecar.MihomoSidecar.proxyOf(profile))
        val options = proxy.getJSONObject("amnezia-wg-option")
        val endpoint = literal(profile.address)
        require(profile.port in 1..65535 && routes.isNotEmpty() && routes.all { it == "0.0.0.0/0" || it == "::/0" })
        require(options.keys().asSequence().all { it in NATIVE_OPTIONS })
        return buildString {
            appendLine("private_key=${key(proxy.getString("private-key"))}")
            appendLine("replace_peers=true")
            options.keys().forEach { name ->
                val value = options.get(name).toString()
                require(value.none { it.isISOControl() })
                appendLine("$name=$value")
            }
            appendLine("public_key=${key(proxy.getString("public-key"))}")
            proxy.optString("pre-shared-key").takeIf(String::isNotBlank)?.let { appendLine("preshared_key=${key(it)}") }
            proxy.optInt("persistent-keepalive").takeIf { it in 1..65535 }
                ?.let { appendLine("persistent_keepalive_interval=$it") }
            appendLine("endpoint=${if (':' in endpoint) "[$endpoint]" else endpoint}:${profile.port}")
            appendLine("replace_allowed_ips=true")
            routes.forEach { appendLine("allowed_ip=$it") }
            appendLine()
        }
    }

    @Synchronized
    override fun startWithTun(profile: VlessProfile, settings: AppSettings, tunFd: Int, protectFd: (Int) -> Boolean): AppResult<Unit> {
        return try {
            stop()
            if (!available()) return failure("AmneziaWG native library is unavailable.")
            val code = startNative(tunFd, config(profile).uapi)
            if (code != 0) return failure("AmneziaWG native startup failed ($code).")
            running = true
            val sockets = listOf(socketNative(false), socketNative(true)).filter { it >= 0 }
            if (sockets.isEmpty() || sockets.any { !protectFd(it) }) {
                stop()
                return failure("Android rejected AmneziaWG socket protection.")
            }
            AppResult.Success(Unit)
        } catch (_: Exception) {
            stop()
            failure("Could not start the AmneziaWG native tunnel.")
        } catch (_: LinkageError) {
            stop()
            failure("AmneziaWG native integration is unavailable.")
        }
    }

    @Synchronized
    override fun stop(): AppResult<Unit> {
        if (running) {
            running = false
            stopNative()
        }
        return AppResult.Success(Unit)
    }

    override fun isRunning() = running
    override fun getStats() = statsFlow.value
    override fun start(profile: VlessProfile, settings: AppSettings, protectSocket: (Socket) -> Boolean,
        protectDatagram: (DatagramSocket) -> Boolean) = failure("AmneziaWG requires Android TUN.")
    override fun restart(profile: VlessProfile, settings: AppSettings, protectSocket: (Socket) -> Boolean,
        protectDatagram: (DatagramSocket) -> Boolean) = failure("Recreate the Android TUN before restarting AmneziaWG.")

    private fun failure(message: String): AppResult<Unit> = AppResult.Error(IllegalStateException(message), message)

    private external fun startNative(tunFd: Int, configuration: String): Int
    private external fun socketNative(ipv6: Boolean): Int
    private external fun stopNative()

    private fun JSONArray.strings() = List(length()) { getString(it) }
    private fun key(raw: String): String {
        val bytes = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
        require(bytes.size == 32)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun literal(raw: String): String {
        val ip = raw.trim().removePrefix("[").removeSuffix("]")
        require(ip.contains(':') && ip.matches(Regex("[0-9a-fA-F:.]+")) ||
            ip.split('.').let { pieces -> pieces.size == 4 && pieces.all { it.toIntOrNull() in 0..255 && it.all(Char::isDigit) } })
        return InetAddress.getByName(ip).hostAddress ?: error("Invalid IP address.")
    }

    private fun address(raw: String): Address {
        val ip = literal(raw.substringBefore('/'))
        val prefix = raw.substringAfter('/', "").toInt()
        require(prefix in 0..(if (':' in ip) 128 else 32))
        return Address(ip, prefix)
    }

    private val NATIVE_OPTIONS = setOf("jc", "jmin", "jmax", "s1", "s2", "s3", "s4",
        "h1", "h2", "h3", "h4", "i1", "i2", "i3", "i4", "i5")
}
