package com.example.vpn.sidecar

import com.example.data.model.ProfileType
import com.example.data.model.VlessProfile
import org.json.JSONArray
import org.json.JSONObject

/** The profile Xray runs while a sidecar carries the traffic: one SOCKS5 outbound to its port. */
object SidecarChain {
    fun xrayProfile(original: VlessProfile, context: SidecarContext, launch: SidecarLaunch): VlessProfile {
        if (ByedpiSidecar.handles(original)) return ByedpiSidecar.wrap(original, context)
        val server = JSONObject()
            .put("address", "127.0.0.1")
            .put("port", context.socksPort)
        if (launch.socksAuth) {
            server.put("users", JSONArray().put(JSONObject().put("user", context.socksUser).put("pass", context.socksPass)))
        }
        val outbound = JSONObject()
            .put("tag", "proxy")
            .put("protocol", "socks")
            .put("settings", JSONObject().put("servers", JSONArray().put(server)))
        val config = JSONObject().put("outbounds", JSONArray().put(outbound))
        return original.copy(profileType = ProfileType.XRAY_JSON, rawConfig = config.toString())
    }

    /**
     * [xrayProfile] for a request test. The engine's marker in extraSettings is dropped: left in, the
     * probe still sees an engine profile, skips it as "carried by its own engine", and a ready Tor,
     * Psiphon or DNS tunnel is recorded as not tested instead of being measured.
     */
    fun probeProfile(original: VlessProfile, context: SidecarContext, launch: SidecarLaunch): VlessProfile =
        xrayProfile(original, context, launch).copy(extraSettings = "")
}
