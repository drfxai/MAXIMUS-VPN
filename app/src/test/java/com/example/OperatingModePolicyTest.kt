package com.example

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.data.model.RoutingMode
import com.example.vpn.safety.OperatingModePolicy
import com.example.xray.XrayConfigParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** V1.0.1 step 4: DAILY keeps the user's choices; GOD MODE proxies everything and fails closed. */
class OperatingModePolicyTest {
    private val userChoices = AppSettings(
        routingMode = RoutingMode.BYPASS_SELECTED, customBypassRules = "example.ir", ipv6Enabled = true,
        autoFailoverEnabled = false, autoReconnect = false, dnsServer = "https://8.8.8.8/dns-query"
    )

    @Test fun dailyRunsTheSettingsAsTheUserLeftThem() {
        assertSame(userChoices, OperatingModePolicy.DAILY.apply(userChoices))
        assertFalse(OperatingModePolicy.DAILY.alwaysSmartConnect)
    }

    @Test fun godModeProxiesEverythingBlocksIpv6AndKeepsRecoveryOn() {
        val god = OperatingModePolicy.GOD_MODE.apply(userChoices.copy(operationalMode = OperationalMode.GOD_MODE))
        assertEquals(RoutingMode.GLOBAL, god.routingMode)
        assertFalse(god.ipv6Enabled)
        assertTrue(god.autoFailoverEnabled)
        assertTrue(god.autoReconnect)
        assertTrue(OperatingModePolicy.of(OperationalMode.GOD_MODE).alwaysSmartConnect)
    }

    @Test fun godModeDropsDirectRulesFromImportedConfigs() {
        val raw = """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4"}]}},{"tag":"direct","protocol":"freedom"}],
            "routing":{"rules":[{"type":"field","domain":["geosite:ir"],"outboundTag":"direct"},{"type":"field","port":"0-65535","outboundTag":"proxy"}]}}"""
        fun directRules(mode: OperationalMode): Int {
            val rules = JSONObject(XrayConfigParser.sanitizeForExecution(raw, userChoices.copy(operationalMode = mode)))
                .getJSONObject("routing").getJSONArray("rules")
            return (0 until rules.length()).count { rules.getJSONObject(it).optString("outboundTag") == "direct" }
        }
        assertEquals(1, directRules(OperationalMode.DAILY))
        assertEquals(0, directRules(OperationalMode.GOD_MODE))
    }
}
