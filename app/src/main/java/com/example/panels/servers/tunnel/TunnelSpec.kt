package com.example.panels.servers.tunnel

import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The ways the Iran server reaches the server abroad. Every one is an existing, audited protocol;
 * Maximus Tunnel only installs, rotates and switches between them.
 */
enum class TunnelTransport(val id: String, val title: String, val detail: String, val udp: Boolean) {
    REALITY("reality", "REALITY", "TCP · VLESS Vision, looks like a visit to a big website", false),
    XHTTP("xhttp", "XHTTP", "TCP · HTTP requests over REALITY, survives stricter filters", false),
    HYSTERIA2("hy2", "Hysteria2", "UDP · QUIC with Salamander, fastest on lossy links", true),
    /** The server abroad connects to the Iran server's phone port and the Iran server sends traffic back through it. */
    REVERSE("rv", "Reverse", "TCP · the server abroad connects to Iran, no open port abroad", false);

    /** Xray outbound tags on the Iran server start with this; the balancer selects by the prefix. */
    val tagPrefix: String get() = "t-$id"

    companion object {
        fun byId(id: String): TunnelTransport? = entries.firstOrNull { it.id == id }
        /** The transports whose port moves with rotation (Hysteria2 keeps one UDP port, Reverse uses the phone's port). */
        val ROTATING = listOf(REALITY, XHTTP)
        /** The transports where the Iran server connects to the server abroad. */
        val FORWARD = listOf(REALITY, XHTTP, HYSTERIA2)

        /**
         * The ways to use, from what the check found: every way that can work, so the balancer has
         * the most paths to pick from. null means that direction could not be tested; it counts as open.
         */
        fun recommended(iranReachesAbroad: Boolean?, abroadReachesIran: Boolean?): Set<TunnelTransport> {
            val forward = iranReachesAbroad != false
            val reverse = abroadReachesIran != false
            return buildSet {
                if (forward) addAll(FORWARD)
                if (reverse) add(REVERSE)
            }.ifEmpty { entries.toSet() }
        }
    }
}

/**
 * Everything both servers of one Maximus Tunnel need. The phone connects to the Iran server
 * ("entry"); the Iran server forwards to the server abroad ("exit") over [transports] and Xray
 * picks the fastest working one. It is stored in the tunnel tool's settings on both servers.
 */
data class TunnelSpec(
    val iranId: String,
    val abroadId: String,
    val transports: Set<TunnelTransport>,
    /** Ports of the rotating transports change every this many hours; 0 keeps them fixed. */
    val rotationHours: Int,
    /** Shared secret both servers derive the rotating ports from. */
    val seed: String,
    /** Ports the derivation never picks (anything either server already used when it was set up). */
    val avoidPorts: Set<Int>,
    /** Fixed ports of the rotating transports, used when [rotationHours] is 0. */
    val fixedPorts: Map<TunnelTransport, Int>,
    // Phone -> Iran server
    val entryPort: Int,
    val entryUuid: String,
    val entrySni: String,
    val entryPublicKey: String = "",
    val entryShortId: String,
    // Iran server -> server abroad
    val linkUuid: String,
    val exitSni: String,
    val exitPublicKey: String = "",
    val exitShortId: String,
    val xhttpPath: String,
    val hyPort: Int,
    val hyAuth: String,
    val hyObfs: String,
    /** The exit's self-signed Hysteria2 certificate (PEM), trusted as the only CA by the Iran server. */
    val hyCertPem: String = "",
    /** Loopback SOCKS port of the Hysteria2 client on the Iran server. */
    val hyLocalPort: Int,
    /** Id the server abroad signs in with for [TunnelTransport.REVERSE]; Xray lets it only carry reverse traffic. */
    val reverseUuid: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    val fallbackTransport: TunnelTransport get() = TunnelTransport.entries.first { it in transports }

    fun toSettings(role: String): Map<String, String> = buildMap {
        put(K_ROLE, role)
        put("iranId", iranId); put("abroadId", abroadId)
        put("transports", TunnelTransport.entries.filter { it in transports }.joinToString(",") { it.id })
        put("rotationHours", rotationHours.toString()); put("seed", seed)
        put("avoid", avoidPorts.sorted().joinToString(","))
        put("fixed", fixedPorts.entries.joinToString(",") { "${it.key.id}:${it.value}" })
        put("entryPort", entryPort.toString()); put("entryUuid", entryUuid); put("entrySni", entrySni)
        put("entryPublicKey", entryPublicKey); put("entryShortId", entryShortId)
        put("linkUuid", linkUuid); put("exitSni", exitSni)
        put("exitPublicKey", exitPublicKey); put("exitShortId", exitShortId)
        put("xhttpPath", xhttpPath)
        put("hyPort", hyPort.toString()); put("hyAuth", hyAuth); put("hyObfs", hyObfs); put("hyCertPem", hyCertPem)
        put("hyLocalPort", hyLocalPort.toString()); put("reverseUuid", reverseUuid); put("createdAt", createdAt.toString())
    }

    companion object {
        const val K_ROLE = "role"
        const val ROLE_IRAN = "iran"
        const val ROLE_ABROAD = "abroad"
        val ROTATION_CHOICES = listOf(0, 6, 12, 24)

        fun fromSettings(s: Map<String, String>): TunnelSpec? = runCatching {
            fun i(k: String) = s.getValue(k).toInt()
            TunnelSpec(
                iranId = s.getValue("iranId"), abroadId = s.getValue("abroadId"),
                transports = s.getValue("transports").split(',').mapNotNull { TunnelTransport.byId(it.trim()) }.toSet(),
                rotationHours = i("rotationHours"), seed = s.getValue("seed"),
                avoidPorts = s["avoid"].orEmpty().split(',').mapNotNull { it.trim().toIntOrNull() }.toSet(),
                fixedPorts = s["fixed"].orEmpty().split(',').mapNotNull { e ->
                    val (k, v) = e.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
                    TunnelTransport.byId(k)?.let { t -> v.toIntOrNull()?.let { t to it } }
                }.toMap(),
                entryPort = i("entryPort"), entryUuid = s.getValue("entryUuid"), entrySni = s.getValue("entrySni"),
                entryPublicKey = s["entryPublicKey"].orEmpty(),
                entryShortId = s.getValue("entryShortId"),
                linkUuid = s.getValue("linkUuid"), exitSni = s.getValue("exitSni"),
                exitPublicKey = s["exitPublicKey"].orEmpty(),
                exitShortId = s.getValue("exitShortId"), xhttpPath = s.getValue("xhttpPath"),
                hyPort = i("hyPort"), hyAuth = s.getValue("hyAuth"), hyObfs = s.getValue("hyObfs"),
                hyCertPem = s["hyCertPem"].orEmpty(), hyLocalPort = i("hyLocalPort"),
                reverseUuid = s["reverseUuid"].orEmpty(),
                createdAt = s["createdAt"]?.toLongOrNull() ?: 0L
            ).takeIf { it.transports.isNotEmpty() }
        }.getOrNull()
    }
}

/** Random values for a new tunnel. */
object TunnelRandom {
    private val random = SecureRandom()

    fun hex(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }
    fun uuid(): String = UUID.randomUUID().toString()
    fun path(): String = "/" + hex(6)
}

/**
 * The rotating ports. Both servers compute them on their own from the shared seed and the clock,
 * so the phone never has to be online for a rotation: `port = 20000 + HMAC-SHA256(seed,
 * "name:epoch[:n]") mod 40000`, where the epoch is the number of whole rotation periods since
 * 1970 (UTC) and n counts up only to skip [TunnelSpec.avoidPorts]. The server-side script
 * (`openssl dgst -sha256 -hmac`) computes exactly the same numbers; a unit test pins vectors.
 */
object TunnelPorts {
    const val LOW = 20_000
    const val SPAN = 40_000

    fun epoch(rotationHours: Int, nowMs: Long = System.currentTimeMillis()): Long =
        if (rotationHours <= 0) 0 else (nowMs / 1000) / (rotationHours * 3600L)

    fun derive(seed: String, name: String, epoch: Long, avoid: Set<Int>): Int {
        var n = 0
        while (true) {
            val msg = if (n == 0) "$name:$epoch" else "$name:$epoch:$n"
            val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(seed.toByteArray(), "HmacSHA256")) }.doFinal(msg.toByteArray())
            val v = ((mac[0].toLong() and 0xff) shl 24) or ((mac[1].toLong() and 0xff) shl 16) or
                ((mac[2].toLong() and 0xff) shl 8) or (mac[3].toLong() and 0xff)
            val port = (LOW + v % SPAN).toInt()
            if (port !in avoid) return port
            n++
        }
    }

    /** The ports a rotating transport listens on now: this period's and, during rotation, the previous one's. */
    fun current(spec: TunnelSpec, t: TunnelTransport, nowMs: Long = System.currentTimeMillis()): List<Int> {
        if (spec.rotationHours <= 0) return listOfNotNull(spec.fixedPorts[t])
        val e = epoch(spec.rotationHours, nowMs)
        return listOf(derive(spec.seed, t.id, e, spec.avoidPorts), derive(spec.seed, t.id, e - 1, spec.avoidPorts)).distinct()
    }

    /** When the ports change next, in epoch milliseconds; null without rotation. */
    fun nextChangeMs(rotationHours: Int, nowMs: Long = System.currentTimeMillis()): Long? =
        if (rotationHours <= 0) null else (epoch(rotationHours, nowMs) + 1) * rotationHours * 3_600_000L
}
