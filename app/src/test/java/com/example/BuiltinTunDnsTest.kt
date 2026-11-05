package com.example

import com.example.data.model.*
import com.example.vpn.sidecar.*
import com.example.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class BuiltinTunDnsTest {
    @Test fun hostRestoresDomainsBeforeForwardingToEitherBuiltin() {
        for ((id, profile) in listOf("aether" to AetherSidecar.profile(), "direct" to ServerlessSidecar.profile())) {
            val ctx = SidecarContext(File("."), File("unused"), 12345, "test-user", "test-pass", OperationalMode.DAILY)
            val forwarding = SidecarChain.xrayProfile(profile, ctx, SidecarLaunch(emptyList()))
            val config = JSONObject(XrayConfigBuilder.buildJson(forwarding,
                AppSettings(dnsServer = "https://8.8.8.8/dns-query", ipv6Enabled = false)))
            assertEquals("fakedns", config.getJSONObject("dns").getJSONArray("servers").getString(0))
            assertEquals("AsIs", config.getJSONObject("routing").getString("domainStrategy"))
            assertEquals("198.18.0.0/15", config.getJSONArray("fakedns").getJSONObject(0).getString("ipPool"))
            assertEquals("test-user", config.getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
                .getJSONArray("users").getJSONObject(0).getString("user"))
            System.getenv("MAXIMUS_SERVERLESS_FIXTURES")?.let { path ->
                File(File(path).parentFile, "host-fixtures").apply { mkdirs() }
                    .resolve("$id.json").writeText(config.toString())
            }
        }
    }
}
