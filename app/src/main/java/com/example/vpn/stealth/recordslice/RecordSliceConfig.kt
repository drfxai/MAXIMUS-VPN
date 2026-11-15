package com.example.vpn.stealth.recordslice

import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.EngineSelectionPolicy
import com.example.vpn.engine.ProfileExtras
import org.json.JSONArray
import org.json.JSONObject

/**
 * How a profile asks for TLS record slicing ([TlsRecordSlicer]) and how its Xray config is pointed at the local
 * bridge that does it ([RecordSliceBridge]).
 *
 * The profile carries the vector in its extras ([EXTRA_VECTOR], [EXTRA_BURSTS]). The config keeps Xray's own TLS
 * or REALITY client unchanged; only its TCP dial goes through the bridge: the proxy outbound gets
 * `sockopt.dialerProxy` → a `freedom` outbound that redirects to `127.0.0.1:<bridge port>`, and the bridge dials
 * the real server outside the VPN (VpnService.protect). Any TCP `fragment` finalmask entry is removed, since the
 * bridge does the splitting. Pure: the caller supplies the bridge port.
 */
object RecordSliceConfig {
    const val EXTRA_VECTOR = "recordSlice"
    const val EXTRA_BURSTS = "recordSliceBursts"
    const val DIALER_TAG = "record-slice-dialer"

    data class Spec(val vector: TlsRecordSlicer.Vector, val bursts: Int, val mss: Int? = null)

    private val TCP_TRANSPORTS = setOf("tcp", "raw", "ws", "grpc", "http", "h2", "httpupgrade", "xhttp", "splithttp")

    /**
     * TLS or REALITY carried by Xray over TCP, without ECH (splitting an ECH ClientHello breaks it, see
     * [com.example.vpn.smart.FragmentPolicy]) and not over HTTP/3.
     */
    fun eligible(p: VlessProfile): Boolean =
        p.profileType == ProfileType.VLESS &&
            p.protocolType in setOf(ProtocolType.VLESS, ProtocolType.VMESS, ProtocolType.TROJAN) &&
            p.security.lowercase() in setOf("tls", "reality") &&
            p.echConfigList.isBlank() &&
            p.transport.lowercase().ifBlank { "tcp" } in TCP_TRANSPORTS &&
            !p.alpn.lowercase().split(',').map { it.trim() }.contains("h3") &&
            p.address.isNotBlank() && p.port in 1..65535 &&
            runCatching { EngineSelectionPolicy.select(p) == EngineSelectionPolicy.Runtime.XRAY }.getOrDefault(false)

    /** [p] with record slicing [vector] in [bursts] writes. */
    fun enable(p: VlessProfile, vector: TlsRecordSlicer.Vector, bursts: Int = vector.defaultBursts): VlessProfile =
        ProfileExtras.with(ProfileExtras.with(p, EXTRA_VECTOR, vector.key), EXTRA_BURSTS, bursts.coerceIn(1, 2))

    /** What [p] asks for, when it asks for slicing and can take it. */
    fun spec(p: VlessProfile, mss: Int? = null): Spec? {
        val extras = ProfileExtras.read(p)
        val vector = TlsRecordSlicer.Vector.of(extras.optString(EXTRA_VECTOR).ifBlank { null }) ?: return null
        if (!eligible(p)) return null
        val bursts = extras.optInt(EXTRA_BURSTS, vector.defaultBursts).coerceIn(1, 2)
        return Spec(vector, bursts, mss)
    }

    /** [root]'s `proxy` outbound dialed through `127.0.0.1:[bridgePort]`; its TCP fragment masks are dropped. */
    fun apply(root: JSONObject, bridgePort: Int): JSONObject {
        require(bridgePort in 1..65535)
        val outbounds = root.optJSONArray("outbounds") ?: return root
        var found = false
        for (i in 0 until outbounds.length()) {
            val o = outbounds.optJSONObject(i) ?: continue
            if (o.optString("tag") == DIALER_TAG) return root
            if (o.optString("tag") != "proxy") continue
            found = true
            val stream = o.optJSONObject("streamSettings") ?: JSONObject().also { o.put("streamSettings", it) }
            val sockopt = stream.optJSONObject("sockopt") ?: JSONObject().also { stream.put("sockopt", it) }
            sockopt.put("dialerProxy", DIALER_TAG)
            // The bridge sets the MSS on the real server socket; on the loopback leg it means nothing.
            sockopt.remove("tcpMaxSeg")
            dropTcpFragments(stream)
        }
        if (!found) return root
        outbounds.put(JSONObject().put("tag", DIALER_TAG).put("protocol", "freedom")
            .put("settings", JSONObject().put("redirect", "127.0.0.1:$bridgePort")))
        return root
    }

    private fun dropTcpFragments(stream: JSONObject) {
        val mask = stream.optJSONObject("finalmask") ?: return
        val tcp = mask.optJSONArray("tcp") ?: return
        val kept = JSONArray()
        for (i in 0 until tcp.length()) {
            val e = tcp.opt(i)
            if (e is JSONObject && e.optString("type").equals("fragment", ignoreCase = true)) continue
            kept.put(e)
        }
        if (kept.length() > 0) mask.put("tcp", kept) else mask.remove("tcp")
        if (mask.length() == 0) stream.remove("finalmask")
    }
}
