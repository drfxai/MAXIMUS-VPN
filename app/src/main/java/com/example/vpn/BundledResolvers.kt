package com.example.vpn

import com.example.vpn.sidecar.Sidecars
import java.net.InetAddress

/** Raw asset entries are candidates. Only known provider addresses are used for live DNS. */
object BundledResolvers {
    private val ipv4 = Regex("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
    private val parts by lazy {
        (1..6).map { part ->
            val open = Sidecars.openAsset ?: return@lazy emptyList()
            try {
                open("resolvers/part_$part.txt").bufferedReader().useLines { lines ->
                    lines.map(String::trim).filter(::publicIpv4).distinct().toList()
                }
            } catch (_: java.io.IOException) {
                emptyList()
            }
        }
    }

    internal fun publicIpv4(ip: String): Boolean {
        if (!ipv4.matches(ip)) return false
        val bytes = ip.split('.').map { it.toInt() }
        if (bytes.any { it !in 0..255 } || bytes.first() == 0) return false
        val (a, b, c) = bytes
        if ((a == 192 && b == 0 && c in listOf(0, 2)) ||
            (a == 198 && b == 51 && c == 100) || (a == 203 && b == 0 && c == 113)) return false
        val address = InetAddress.getByAddress(bytes.map(Int::toByte).toByteArray())
        return !EndpointResolver.isBlockedAnswer(address)
    }

    /** Return matching provider addresses without changing the provider's preference order. */
    fun known(vararg addresses: String): List<String> {
        val selected = addresses.filter { ip -> parts.any { ip in it } }
        return selected.ifEmpty { addresses.toList() }
    }

    /** One candidate per part per day, only for the explicit LAB measurement. */
    fun labSample(networkKey: String, now: Long = System.currentTimeMillis()): List<String> {
        val day = now / 86_400_000L
        return parts.mapNotNull { part ->
            if (part.isEmpty()) null
            else part[Math.floorMod(networkKey.hashCode().toLong() + day, part.size.toLong()).toInt()]
        }
    }
}
