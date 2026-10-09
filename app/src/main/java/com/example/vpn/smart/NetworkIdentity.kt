package com.example.vpn.smart

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Network identity V2 for the LAB: tells two Wi-Fi (or Ethernet) networks apart without reading the SSID,
 * which needs the location permission and is personal. The inputs are what the phone's own link already
 * reports (default gateway, DNS servers, search domains, IPv4 prefix, IPv6 presence, MTU); they are hashed
 * with a random per-install salt and only a short prefix is kept. The salt never leaves the phone, so the
 * same network gives different fingerprints on different phones, and nothing here can be reversed.
 *
 * Mobile networks keep their carrier code ("cell:43235"): it is not personal, and the carrier stays
 * metadata only; it never decides a protocol. The fingerprint is never sent to the AI (see LabBrief).
 */
object NetworkIdentity {
    private const val PREFS = "lab_identity"
    private const val SALT = "salt"
    /** 12 hex characters (48 bits): enough to keep a person's networks apart, too short to be a tracker. */
    const val LENGTH = 12

    /** The inputs that tell one link apart from another. Empty values are dropped; order is fixed. */
    data class Inputs(
        val gateway: String?,
        val dnsServers: List<String>,
        val domains: String?,
        val ipv4Prefix: String?,
        val ipv6Present: Boolean,
        val mtu: Int?
    ) {
        fun canonical(): List<String> = listOf(
            "gw=" + (gateway ?: ""),
            "dns=" + dnsServers.sorted().joinToString(","),
            "dom=" + (domains ?: ""),
            "v4=" + (ipv4Prefix ?: ""),
            "v6=" + ipv6Present,
            "mtu=" + (mtu ?: 0)
        )

        /** True when nothing usable was read: then the fingerprint would merge every network. */
        val empty: Boolean get() = gateway.isNullOrBlank() && dnsServers.isEmpty() && ipv4Prefix.isNullOrBlank()
    }

    /** "wifi:<fp>" / "ethernet:<fp>"; [kind] alone when the link reported nothing to fingerprint. Pure. */
    fun key(kind: String, inputs: Inputs, salt: String): String =
        if (inputs.empty) kind else "$kind:" + fingerprint(inputs.canonical(), salt)

    fun fingerprint(parts: List<String>, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt.toByteArray())
        parts.forEach { digest.update(0); digest.update(it.toByteArray()) }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(LENGTH)
    }

    /** The IPv4 network of an address/prefix ("192.168.1.23/24" → "192.168.1.0/24"). Pure. */
    fun ipv4Network(address: ByteArray, prefix: Int): String? {
        if (address.size != 4 || prefix !in 0..32) return null
        val v = address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
        val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val n = v and mask
        return "${(n shr 24) and 0xff}.${(n shr 16) and 0xff}.${(n shr 8) and 0xff}.${n and 0xff}/$prefix"
    }

    /** A readable label that tells networks apart without naming them: "Wi-Fi · a1b2". */
    fun label(key: String, base: String): String {
        val fp = key.substringAfter(':', "")
        return if (fp.length >= 4 && (key.startsWith("wifi:") || key.startsWith("ethernet:"))) "$base · ${fp.take(4)}" else base
    }

    /** The salt for this install, created once. */
    @Synchronized
    fun salt(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(SALT, null)?.let { return it }
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val s = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(SALT, s).apply()
        return s
    }

    /** The LAB key of the network the phone uses outside the VPN. Mobile data keeps [NetworkKey.current]. */
    @Suppress("DEPRECATION")
    fun current(context: Context): String {
        val coarse = NetworkKey.current(context)
        if (coarse != "wifi" && coarse != "ethernet") return coarse
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return coarse
        val transport = if (coarse == "wifi") NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_ETHERNET
        val network = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let { it.hasTransport(transport) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
        } ?: return coarse
        val lp = runCatching { cm.getLinkProperties(network) }.getOrNull() ?: return coarse
        return key(coarse, inputsOf(lp), salt(context))
    }

    private fun inputsOf(lp: LinkProperties): Inputs {
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway != null }?.gateway?.hostAddress
        val v4 = lp.linkAddresses.firstOrNull { it.address.address.size == 4 }
        return Inputs(
            gateway = gateway,
            dnsServers = lp.dnsServers.mapNotNull { it.hostAddress },
            domains = lp.domains,
            ipv4Prefix = v4?.let { ipv4Network(it.address.address, it.prefixLength) },
            ipv6Present = lp.linkAddresses.any { it.address.address.size == 16 && !it.address.isLinkLocalAddress },
            mtu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) lp.mtu.takeIf { it > 0 } else null
        )
    }
}
