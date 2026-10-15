package com.example.vpn.share

import com.example.data.model.AppSettings
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Helpers for sharing the tunnel over the phone's hotspot. The proxy itself is opened by
 * [com.example.xray.XrayConfigBuilder] (a password-protected SOCKS5 + HTTP inbound bound to the LAN);
 * this object only works out the address to show the user and generates the credentials.
 */
object HotspotShare {

    private val random = SecureRandom()
    private const val PASSWORD_ALPHABET = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"

    /** Interface-name hints Android uses for the local AP / tether link, most specific first. */
    private val AP_HINTS = listOf("ap", "swlan", "wlan", "softap", "rndis", "usb", "bridge", "tether")

    /** A short random username and a strong random password for the shared proxy. */
    fun newCredentials(): Pair<String, String> {
        val user = "maximus-" + randomToken(4)
        val pass = randomToken(16)
        return user to pass
    }

    private fun randomToken(length: Int): String {
        val sb = StringBuilder(length)
        repeat(length) { sb.append(PASSWORD_ALPHABET[random.nextInt(PASSWORD_ALPHABET.length)]) }
        return sb.toString()
    }

    /**
     * Best-effort local IPv4 address other devices on the hotspot would use to reach this phone.
     * Prefers an interface that looks like the access point; falls back to any site-local IPv4.
     * Returns null when no usable address is up (for example the hotspot is off).
     */
    fun localApAddress(): String? {
        val candidates = mutableListOf<Pair<Int, String>>()
        val interfaces = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: Exception) {
            return null
        }
        for (nif in interfaces) {
            val up = try {
                nif.isUp && !nif.isLoopback
            } catch (_: Exception) {
                false
            }
            if (!up) continue
            val name = nif.name.lowercase()
            val hintRank = AP_HINTS.indexOfFirst { name.startsWith(it) }
            for (addr in nif.inetAddresses) {
                if (addr is Inet4Address && addr.isSiteLocalAddress) {
                    // Lower score sorts first: an AP-looking interface beats a plain site-local one.
                    val score = if (hintRank >= 0) hintRank else AP_HINTS.size + 1
                    candidates.add(score to addr.hostAddress.orEmpty())
                }
            }
        }
        return candidates.filter { it.second.isNotBlank() }.minByOrNull { it.first }?.second
    }

    /**
     * The human-readable "set this on the other device" summary: SOCKS5 and HTTP endpoints plus the
     * shared credentials. Returns null when sharing is not active or no address is available.
     */
    fun clientInstructions(settings: AppSettings, address: String? = localApAddress()): Instructions? {
        if (!settings.hotspotShareEnabled) return null
        if (settings.hotspotShareUser.isBlank() || settings.hotspotSharePassword.isBlank()) return null
        val host = address ?: return null
        return Instructions(
            host = host,
            socksPort = settings.hotspotSharePort,
            httpPort = settings.hotspotSharePort + 1,
            user = settings.hotspotShareUser,
            password = settings.hotspotSharePassword
        )
    }

    data class Instructions(
        val host: String,
        val socksPort: Int,
        val httpPort: Int,
        val user: String,
        val password: String
    ) {
        val socksEndpoint: String get() = "$host:$socksPort"
        val httpEndpoint: String get() = "$host:$httpPort"
    }
}
