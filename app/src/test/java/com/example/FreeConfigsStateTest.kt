package com.example

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.ui.freeconfigs.FreeConfigsUiState
import com.example.ui.freeconfigs.FreeNode
import com.example.ui.freeconfigs.FreeSort
import com.example.ui.freeconfigs.NodeHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the Free Configs screen counts, lists and offers as the fastest server. */
class FreeConfigsStateTest {
    private fun node(
        id: String, country: String?, ms: Long?, health: NodeHealth = FreeNode.healthOf(ms),
        type: ProtocolType = ProtocolType.VLESS, passes: Int = 0, runs: Int = 0,
        security: String = "reality", transport: String = "tcp", sites: Set<String> = emptySet()
    ) = FreeNode(
        VlessProfile(id = id, name = id, address = "203.0.113.1", port = 443, uuid = "u", security = security, transport = transport, protocolType = type),
        country, ms, health, passes, runs, sites
    )

    private val de = node("de", "DE", 142, passes = 2, runs = 3)
    private val nl = node("nl", "NL", 905, passes = 3, runs = 3)
    private val us = node("us", "US", 310, type = ProtocolType.TROJAN, security = "tls", transport = "ws", passes = 1, runs = 3)
    private val down = node("down", "FR", null, NodeHealth.OFFLINE)
    private val waiting = node("wait", null, null)
    private val state = FreeConfigsUiState(nodes = listOf(nl, down, us, de, waiting))

    @Test fun countsFollowTheTests() {
        assertEquals(5, state.total)
        assertEquals(listOf(nl, us, de), state.online)
        assertEquals(2, state.fast)
        assertEquals(1, state.slow)
        assertEquals(listOf(down), state.offline)
        assertEquals(1, state.queued)
        assertEquals(4, state.tested)
        // Untested servers count too: tests run only when the user asks.
        assertEquals(listOf("VLESS" to 3, "Trojan" to 1), state.protocolCounts)
    }

    @Test fun theListIsSortedAndFilteredAsChosen() {
        // Untested servers are listed last; servers that failed here are hidden.
        assertEquals(listOf(de, us, nl, waiting), state.visible)
        assertEquals(listOf(nl, de, us, waiting), state.copy(sort = FreeSort.STABLE).visible)
        assertEquals(listOf(de, nl, us, waiting), state.copy(sort = FreeSort.COUNTRY).visible)
        assertEquals(listOf(us), state.copy(protocol = "Trojan").visible)
    }

    @Test fun theSiteFilterKeepsServersThatOpenedTheSite() {
        val yt = node("yt", "DE", 200, sites = setOf("YT", "TG"))
        val tg = node("tg", "NL", null, sites = setOf("TG"))
        val gone = node("gone", "FR", null, NodeHealth.OFFLINE, sites = setOf("X"))
        val s = FreeConfigsUiState(nodes = listOf(yt, tg, gone))
        assertEquals(listOf("YT" to 1, "TG" to 2), s.siteCounts)
        assertEquals(listOf(yt), s.copy(site = "YT").visible)
        assertEquals(listOf(yt, tg), s.copy(site = "TG").visible)
        assertEquals(emptyList<FreeNode>(), s.copy(site = "X").visible)
    }

    @Test fun theFastestServerIgnoresTheFilter() {
        assertEquals(de, state.copy(protocol = "Trojan").best)
        assertNull(FreeConfigsUiState(nodes = listOf(down, waiting)).best)
    }

    @Test fun tagsNameSecurityAndTransport() {
        assertEquals(listOf("REALITY", "TCP"), de.tags)
        assertEquals(listOf("TLS", "WS"), us.tags)
        assertEquals(listOf("QUIC", "UDP"), node("h", null, 1, type = ProtocolType.HYSTERIA2).tags)
    }

    @Test fun countriesHaveNamesAndFlags() {
        assertEquals("Germany", FreeConfigsUiState.countryName("DE"))
        assertNull(FreeConfigsUiState.countryName(null))
        assertEquals("🇩🇪", FreeConfigsUiState.flagOf("DE"))
        assertNull(FreeConfigsUiState.flagOf("D1"))
    }

    @Test fun theNextUpdateFollowsTheInterval() {
        assertEquals(0L, FreeConfigsUiState().nextUpdate)
        assertEquals(1_000L + 360 * 60_000L, FreeConfigsUiState(lastUpdated = 1_000L).nextUpdate)
    }

    @Test fun lifecycleCountsKeepPhoneEvidenceApartFromTheBuildersCheck() {
        val s = FreeConfigsUiState(nodes = listOf(
            de.copy(lifecycle = com.example.vpn.hub.FreeConfigLifecycle.LOCAL_NETWORK_VERIFIED), nl.copy(lifecycle = com.example.vpn.hub.FreeConfigLifecycle.LOCAL_PROBATION),
            us.copy(lifecycle = com.example.vpn.hub.FreeConfigLifecycle.DEAD), down.copy(lifecycle = com.example.vpn.hub.FreeConfigLifecycle.DEGRADED), waiting
        ))
        assertEquals(1, s.verifiedHere)
        assertEquals(1, s.onTrial)
        assertEquals(2, s.failingHere)
        assertEquals(1, s.checkedOutside)
    }
}
