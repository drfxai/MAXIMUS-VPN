package com.example.vpn.stealth.recordslice

import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.EngineSelectionPolicy
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.verify.TrafficEvidence
import org.json.JSONObject
import kotlin.random.Random

/**
 * "Uplink Shield": shapes the first 16 KB a connection uploads, for the Irancell upload stall where the graded
 * upload stops at exactly the 16384-byte grade ([TrafficEvidence.uploadFreeze]) while small requests pass.
 *
 * The server socket is opened with TCP_MAXSEG [MSS] before it connects (Xray's own `sockopt.tcpMaxSeg`, set
 * on the proxy outbound), so every upstream segment is at most ~1360 bytes and the peer is told the same MSS.
 * When the record-slicing bridge carries the connection, the bridge sets the option on its own socket and also
 * writes the first [WINDOW_BYTES] in [MSS]±[JITTER]-byte pieces ([chunkSizes]).
 *
 * It is a probed option per network: started only after the stall signature is seen ([signature]) as the same-
 * server rescue ([com.example.vpn.smart.FreezeFailover.Rescue.UPLINK_SHIELD]); whether it cleared the stall is
 * kept per network ([com.example.vpn.smart.UplinkShieldBook]), and a network where it helped gets it on every
 * connect ([networkMss]). Whether it helps is not known in advance: a per-connection byte cap would ignore it.
 *
 * Idea from Alan-RIP-Dpi's Uplink Shield (DrAlanK / LabAlan, MIT; see THIRD_PARTY_NOTICES.md).
 */
object UplinkShield {
    const val EXTRA_KEY = "uplinkShield"
    const val MSS = 1360
    const val JITTER = 24
    const val WINDOW_BYTES = 16384
    /** Accepted MSS values: anything below the IPv4 minimum or above a plain 1500-byte Ethernet MSS is ignored. */
    val MSS_RANGE = 536..1460

    /** Set by the VPN service for the current network when the shield helped there before; null otherwise. */
    @Volatile var networkMss: Int? = null

    /** The upload stalled at the 16 KB grade on every reference measured, with none confirming more. */
    fun signature(evidence: TrafficEvidence): Boolean = evidence.uploadFreeze

    private val TCP_TRANSPORTS = setOf("tcp", "raw", "ws", "grpc", "http", "h2", "httpupgrade", "xhttp", "splithttp")

    /** A config Xray carries over TCP (any security, REALITY and ECH included: the MSS does not touch TLS). */
    fun canShield(p: VlessProfile): Boolean =
        p.profileType == ProfileType.VLESS &&
            p.protocolType in setOf(ProtocolType.VLESS, ProtocolType.VMESS, ProtocolType.TROJAN, ProtocolType.SHADOWSOCKS) &&
            runCatching { EngineSelectionPolicy.select(p) == EngineSelectionPolicy.Runtime.XRAY }.getOrDefault(false) &&
            p.transport.lowercase().ifBlank { "tcp" } in TCP_TRANSPORTS &&
            !p.alpn.lowercase().split(',').map { it.trim() }.contains("h3")

    /** The MSS [p] asks for, if any. */
    fun mss(p: VlessProfile): Int? =
        ProfileExtras.read(p).optInt(EXTRA_KEY, 0).takeIf { it in MSS_RANGE }

    /** [p] with the shield on; everything else unchanged. */
    fun enable(p: VlessProfile, mss: Int = MSS): VlessProfile {
        require(mss in MSS_RANGE)
        return ProfileExtras.with(p, EXTRA_KEY, mss)
    }

    /** The MSS the config of [p] gets: its own, else the network's when [p] can take it. */
    fun effectiveMss(p: VlessProfile): Int? = mss(p) ?: networkMss?.takeIf { it in MSS_RANGE && canShield(p) }

    /** Sets `sockopt.tcpMaxSeg` on the outbound tagged `proxy` in [root]. */
    fun applyNative(root: JSONObject, mss: Int): JSONObject {
        val outbounds = root.optJSONArray("outbounds") ?: return root
        for (i in 0 until outbounds.length()) {
            val o = outbounds.optJSONObject(i) ?: continue
            if (o.optString("tag") != "proxy") continue
            val stream = o.optJSONObject("streamSettings") ?: JSONObject().also { o.put("streamSettings", it) }
            val sockopt = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
            sockopt.put("tcpMaxSeg", mss)
        }
        return root
    }

    /** Write sizes that cover [total] bytes: [mss]±[JITTER] each (never under 512), the last one what is left. */
    fun chunkSizes(total: Int, mss: Int = MSS, random: Random = Random.Default): List<Int> {
        val out = mutableListOf<Int>()
        var left = total
        while (left > 0) {
            val size = maxOf(512, mss + random.nextInt(-JITTER, JITTER + 1))
            val take = minOf(size, left)
            out += take
            left -= take
        }
        return out
    }
}
