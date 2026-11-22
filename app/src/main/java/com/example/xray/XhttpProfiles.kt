package com.example.xray

import com.example.vpn.connectivity.NetworkFirewalls
import org.json.JSONObject

/**
 * XHTTP speed profiles: defaults for the `extra` block of an Xray XHTTP outbound (xmux, packet-up post size and
 * interval, padding), picked by the network the phone is on. A value the link (or imported JSON) already sets is
 * never touched: only keys missing from `extra` are filled, and an `xmux` the link carries is kept whole.
 *
 * - [Profile.FAST] (Wi-Fi, Ethernet): Xray's own xmux reuse, short post interval, full-size posts.
 * - [Profile.CONSERVATIVE] (cellular, Irancell, or unknown): fewer streams per connection and shorter connection
 *   lifetimes, so a per-connection cap or freeze hurts fewer flows; smaller posts spaced further apart for lossy
 *   uplinks; a little more padding.
 *
 * Every value stays inside what a stock Xray server accepts (server scMaxEachPostBytes default 1,000,000).
 */
object XhttpProfiles {
    enum class Profile(val key: String) { FAST("fast"), CONSERVATIVE("conservative") }

    /** Set by the VPN service to the current [com.example.vpn.smart.NetworkKey] ("wifi", "cell:43235"); null when unknown. */
    @Volatile var networkKey: String? = null

    /** The profile for [networkKey]: fast on Wi-Fi/Ethernet, conservative on cellular (Irancell included) or unknown. */
    fun forNetwork(networkKey: String?): Profile = when {
        networkKey == null -> Profile.CONSERVATIVE
        NetworkFirewalls.detect(networkKey) == NetworkFirewalls.Firewall.IRANCELL -> Profile.CONSERVATIVE
        networkKey.startsWith("cell") -> Profile.CONSERVATIVE
        networkKey == "wifi" || networkKey.startsWith("wifi:") || networkKey == "ethernet" -> Profile.FAST
        else -> Profile.CONSERVATIVE
    }

    fun defaults(profile: Profile): JSONObject = when (profile) {
        Profile.FAST -> JSONObject()
            .put("xPaddingBytes", "100-1000")
            .put("scMaxEachPostBytes", "1000000")
            .put("scMinPostsIntervalMs", "10-30")
            .put("xmux", JSONObject()
                .put("maxConcurrency", "16-32")
                .put("cMaxReuseTimes", 0)
                .put("hMaxRequestTimes", "600-900")
                .put("hMaxReusableSecs", "1800-3000"))
        Profile.CONSERVATIVE -> JSONObject()
            .put("xPaddingBytes", "200-1200")
            .put("scMaxEachPostBytes", "250000-500000")
            .put("scMinPostsIntervalMs", "40-80")
            .put("xmux", JSONObject()
                .put("maxConcurrency", "4-8")
                .put("cMaxReuseTimes", 0)
                .put("hMaxRequestTimes", "100-200")
                .put("hMaxReusableSecs", "300-600"))
    }

    /**
     * [extra] (the link's own, may be null) with the [profile] defaults added for every key it does not set.
     * Never changes a key that is present; returns a new object.
     */
    fun apply(extra: JSONObject?, profile: Profile = forNetwork(networkKey)): JSONObject {
        val out = if (extra != null) JSONObject(extra.toString()) else JSONObject()
        val d = defaults(profile)
        d.keys().forEach { k -> if (!out.has(k)) out.put(k, d.get(k)) }
        return out
    }
}
