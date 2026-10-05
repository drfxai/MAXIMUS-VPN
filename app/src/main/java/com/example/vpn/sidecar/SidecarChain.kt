package com.example.vpn.sidecar

import com.example.data.model.ProfileType
import com.example.data.model.VlessProfile
import org.json.JSONArray
import org.json.JSONObject

/** The profile Xray runs while a sidecar carries the traffic: one SOCKS5 outbound to its port. */
object SidecarChain {
    fun xrayProfile(original: VlessProfile, context: SidecarContext, launch: SidecarLaunch): VlessProfile {
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
}
