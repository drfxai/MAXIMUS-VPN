package com.example.vpn.shadowlink

import com.example.vpn.connectivity.TransportCapabilityEngine.Transport
import com.example.vpn.lab.NetworkState
import org.json.JSONArray
import org.json.JSONObject

/**
 * ShadowLink's one-line read on how reachable the open internet is from this network, fused from every
 * discovery signal (physical capability, state classifier, service reachability, captive portal).
 */
enum class Egress(val title: String) {
    /** International sites answer directly and little or no interference was seen. */
    OPEN("Open"),
    /** The way out works but with filtering; an evasion transport is advised. */
    FILTERED("Filtered"),
    /** Only a tunnel (or verified DNS egress) is likely to carry traffic abroad. */
    TUNNEL_ONLY("Tunnel only"),
    /** No way out was verified by any method yet. */
    BLOCKED("Blocked"),
    UNKNOWN("Unknown")
}

/** One recommended transport family, with why ShadowLink ordered it here. */
data class TransportHint(val transport: Transport, val reason: String) {
    fun toJson(): JSONObject = JSONObject().put("t", transport.name).put("why", reason)
}

/**
 * The unified result of one ShadowLink discovery pass. It does not duplicate the underlying engines; it
 * fuses their signals into a single verdict, an adaptive transport order, and a recall of what worked
 * on this network before. Everything here is anonymous: a salted network key, never an operator name.
 */
data class ShadowLinkReport(
    val networkKey: String,
    val networkLabel: String,
    val egress: Egress,
    val state: NetworkState,
    val firewall: String,
    val captivePortal: String,
    /** Resolver honesty: true = answers trusted, false = tampering seen, null = not measured. */
    val dnsHonest: Boolean?,
    val blockedServices: List<String>,
    val workingServices: List<String>,
    val exitCountry: String?,
    val recommendedTransports: List<TransportHint>,
    /** Transport kinds that succeeded on this network in the past (from NetworkMemory). */
    val priorWorkingKinds: List<String>,
    val confidence: Double,
    val summary: String,
    val at: Long
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", networkKey)
        put("label", networkLabel)
        put("egress", egress.name)
        put("state", state.name)
        put("firewall", firewall)
        put("portal", captivePortal)
        dnsHonest?.let { put("dnsHonest", it) }
        put("blocked", JSONArray(blockedServices))
        put("working", JSONArray(workingServices))
        exitCountry?.let { put("exit", it) }
        put("transports", JSONArray().apply { recommendedTransports.forEach { put(it.toJson()) } })
        put("priorKinds", JSONArray(priorWorkingKinds))
        put("confidence", confidence)
        put("summary", summary)
        put("at", at)
    }
}
