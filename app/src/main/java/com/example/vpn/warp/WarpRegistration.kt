package com.example.vpn.warp

import com.example.core.SecretRedactor
import com.example.data.model.VlessProfile
import com.example.vpn.engine.WireGuardConf
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/**
 * One device registered with Cloudflare's free WARP service. [token] and [privateKey] are secrets;
 * [toString] leaves them out so the account can be logged.
 */
data class WarpAccount(
    val deviceId: String,
    val token: String,
    val privateKey: String,
    val peerPublicKey: String,
    val endpointHost: String,
    val endpointPort: Int,
    val addressV4: String,
    val addressV6: String,
    val reserved: List<Int>
) {
    fun toJson(): String = JSONObject()
        .put("deviceId", deviceId)
        .put("token", token)
        .put("privateKey", privateKey)
        .put("peerPublicKey", peerPublicKey)
        .put("endpointHost", endpointHost)
        .put("endpointPort", endpointPort)
        .put("addressV4", addressV4)
        .put("addressV6", addressV6)
        .put("reserved", JSONArray(reserved))
        .toString()

    override fun toString(): String =
        "WarpAccount(device=${SecretRedactor.maskUuid(deviceId)}, endpoint=$endpointHost:$endpointPort, reserved=$reserved)"

    companion object {
        /** Reads what [toJson] wrote; null when [json] is empty or incomplete. */
        fun fromJson(json: String): WarpAccount? = runCatching {
            val o = JSONObject(json)
            val reserved = o.optJSONArray("reserved")
            WarpAccount(
                deviceId = o.getString("deviceId"),
                token = o.getString("token"),
                privateKey = o.getString("privateKey"),
                peerPublicKey = o.getString("peerPublicKey"),
                endpointHost = o.getString("endpointHost"),
                endpointPort = o.getInt("endpointPort"),
                addressV4 = o.optString("addressV4"),
                addressV6 = o.optString("addressV6"),
                reserved = (0 until (reserved?.length() ?: 0)).map { reserved!!.getInt(it) }
            ).takeIf {
                it.deviceId.isNotBlank() && it.privateKey.isNotBlank() && it.peerPublicKey.isNotBlank() &&
                    it.endpointHost.isNotBlank() && it.endpointPort in 1..65535 &&
                    (it.addressV4.isNotBlank() || it.addressV6.isNotBlank())
            }
        }.getOrNull()
    }
}

/**
 * Registers a WARP device with Cloudflare's consumer API, the way wgcf does: a WireGuard key pair is
 * made on the device, only the public key is sent, and the answer names the peer, its endpoint, the
 * interface addresses and the `client_id` that becomes WireGuard's 3 reserved bytes.
 */
object WarpRegistration {
    const val API_URL = "https://api.cloudflareclient.com"

    /** API version and client headers of the Android 1.1.1.1 app 6.38.9 (build 5641), as wgcf sends them. */
    const val API_VERSION = "v0a5641"
    const val CLIENT_VERSION = "a-6.38.9-5641"
    const val USER_AGENT = "1.1.1.1/6.38.9-5641 (Android 16.0.0)"

    const val PROFILE_NAME = "Cloudflare WARP"
    const val DEFAULT_PORT = 2408

    /** Cloudflare's anycast WARP address, used when the answer names only a hostname. */
    const val FALLBACK_ENDPOINT_V4 = "162.159.192.1"

    private const val TIMEOUT_MS = 15_000
    private const val MTU = 1280

    /** Opens [url] directly, ignoring any system HTTP proxy. */
    val directOpener: (URL) -> HttpURLConnection = { url -> url.openConnection(Proxy.NO_PROXY) as HttpURLConnection }

    /** Registers a new device. Blocking; call it off the main thread. */
    fun register(
        open: (URL) -> HttpURLConnection = directOpener,
        privateKey: ByteArray = Curve25519.newPrivateKey(),
        now: Date = Date()
    ): WarpAccount {
        val publicKey = Curve25519.publicKey(privateKey)
        val body = requestBody(base64(publicKey), timestamp(now)).toString().toByteArray(Charsets.UTF_8)
        val connection = open(URL("$API_URL/$API_VERSION/reg"))
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.doOutput = true
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("CF-Client-Version", CLIENT_VERSION)
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Connection", "Keep-Alive")
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }

            val code = connection.responseCode
            if (code !in 200..299) {
                val error = runCatching { connection.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
                throw IOException("WARP registration failed: HTTP $code ${SecretRedactor.redact(error.take(200))}".trim())
            }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parse(text, base64(privateKey))
        } finally {
            connection.disconnect()
        }
    }

    /** The JSON the 1.1.1.1 app posts to `/reg`. */
    internal fun requestBody(publicKey: String, tos: String): JSONObject = JSONObject()
        .put("fcm_token", "")
        .put("install_id", "")
        .put("key", publicKey)
        .put("locale", "en_US")
        .put("model", "PC")
        .put("tos", tos)
        .put("serial_number", "")
        .put("os_version", "16.0.0")
        .put("key_type", "curve25519")
        .put("tunnel_type", "wireguard")

    /** Reads a `/reg` answer. [privateKey] is the base64 key whose public half was registered. */
    internal fun parse(text: String, privateKey: String): WarpAccount {
        val root = runCatching { JSONObject(text) }.getOrElse { throw IOException("WARP registration answer is not JSON") }
        val config = root.optJSONObject("config") ?: throw IOException("WARP registration answer has no config")
        val peer = config.optJSONArray("peers")?.optJSONObject(0) ?: throw IOException("WARP registration answer has no peer")
        val addresses = config.optJSONObject("interface")?.optJSONObject("addresses")
        val (host, port) = endpoint(peer.optJSONObject("endpoint") ?: JSONObject())
        val account = WarpAccount(
            deviceId = root.optString("id"),
            token = root.optString("token"),
            privateKey = privateKey,
            peerPublicKey = peer.optString("public_key"),
            endpointHost = host,
            endpointPort = port,
            addressV4 = addresses?.optString("v4").orEmpty(),
            addressV6 = addresses?.optString("v6").orEmpty(),
            reserved = reservedBytes(config.optString("client_id"))
        )
        if (account.deviceId.isBlank() || account.token.isBlank()) throw IOException("WARP registration answer has no device id or token")
        if (account.peerPublicKey.isBlank()) throw IOException("WARP registration answer has no peer public key")
        if (account.addressV4.isBlank() && account.addressV6.isBlank()) throw IOException("WARP registration answer has no interface address")
        return account
    }

    /**
     * The peer endpoint as an IP literal and port. The IPv4 literal comes first (the app does not
     * connect WireGuard to hostnames, and IPv6 is often missing on mobile networks); the answer's
     * literals carry port 0, so the port is the first of `ports`, then the hostname's port, then 2408.
     */
    internal fun endpoint(endpoint: JSONObject): Pair<String, Int> {
        val v4 = endpoint.optString("v4").trim().substringBefore(':')
        val v6 = endpoint.optString("v6").trim().let { raw ->
            if (raw.startsWith("[")) raw.substringAfter('[').substringBefore(']') else raw
        }
        val hostPort = endpoint.optString("host").substringAfterLast(':', "").toIntOrNull()
        val port = endpoint.optJSONArray("ports")?.optInt(0, 0)?.takeIf { it in 1..65535 }
            ?: hostPort?.takeIf { it in 1..65535 }
            ?: DEFAULT_PORT
        val host = when {
            isIpv4(v4) -> v4
            v6.contains(':') && v6.all { it.isLetterOrDigit() || it == ':' || it == '.' } -> v6
            else -> FALLBACK_ENDPOINT_V4
        }
        return host to port
    }

    /** `client_id` is base64 of the 3 reserved bytes Cloudflare expects in every WireGuard packet. */
    internal fun reservedBytes(clientId: String): List<Int> {
        if (clientId.isBlank()) return emptyList()
        val bytes = runCatching { Base64.getDecoder().decode(clientId.trim()) }.getOrNull() ?: return emptyList()
        return if (bytes.size == 3) bytes.map { it.toInt() and 0xff } else emptyList()
    }

    /** The account as a WireGuard profile run on the bundled Xray WireGuard client. */
    fun toProfile(account: WarpAccount): VlessProfile {
        val proxy = mutableMapOf<String, Any>(
            "name" to PROFILE_NAME,
            "server" to account.endpointHost,
            "port" to account.endpointPort,
            "private-key" to account.privateKey,
            "public-key" to account.peerPublicKey,
            "mtu" to MTU
        )
        if (account.addressV4.isNotBlank()) proxy["ip"] = account.addressV4
        if (account.addressV6.isNotBlank()) proxy["ipv6"] = account.addressV6
        if (account.reserved.size == 3) proxy["reserved"] = account.reserved
        // A stable id, so the same registration always maps to the same saved profile.
        val id = UUID.nameUUIDFromBytes("warp:${account.deviceId}".toByteArray(Charsets.UTF_8)).toString()
        return WireGuardConf.fromMihomo(proxy).copy(id = id)
    }

    /**
     * Returns the account in [stored] (as [WarpAccount.toJson] wrote it), or registers a new one with
     * [register] and hands it to [save] first.
     */
    fun loadOrRegister(stored: String, save: (String) -> Unit, register: () -> WarpAccount): WarpAccount {
        WarpAccount.fromJson(stored)?.let { return it }
        val account = register()
        save(account.toJson())
        return account
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255 }
    }

    private fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** RFC 3339 in UTC with milliseconds, the form the app sends as its terms-of-service time. */
    private fun timestamp(now: Date): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(now)
}
