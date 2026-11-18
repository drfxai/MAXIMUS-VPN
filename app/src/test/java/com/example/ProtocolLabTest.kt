package com.example

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.panels.InboundGenerationResult
import com.example.vpn.lab.LabFamily
import com.example.vpn.lab.LabPriority
import com.example.vpn.lab.LabResult
import com.example.vpn.lab.ProtocolLab
import com.example.xray.RealDelayProbe.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolLabTest {

    private fun profile(name: String, family: LabFamily): VlessProfile {
        val base = VlessProfile(name = name, address = "198.51.100.7", port = 443, uuid = name)
        return when (family) {
            LabFamily.REALITY -> base.copy(security = "reality", transport = "tcp")
            LabFamily.XHTTP -> base.copy(security = "reality", transport = "xhttp")
            LabFamily.HYSTERIA2 -> base.copy(protocolType = ProtocolType.HYSTERIA2, transport = "hysteria", security = "tls")
            LabFamily.WIREGUARD -> base.copy(protocolType = ProtocolType.WIREGUARD, transport = "udp")
            LabFamily.HTTPUPGRADE -> base.copy(transport = "httpupgrade", security = "none")
            LabFamily.WEBSOCKET -> base.copy(transport = "ws", security = "none")
            LabFamily.CDN -> base.copy(transport = "ws", security = "tls")
            LabFamily.VLESS_TCP -> base.copy(transport = "tcp", security = "none")
            LabFamily.VMESS -> base.copy(protocolType = ProtocolType.VMESS)
            LabFamily.TROJAN -> base.copy(protocolType = ProtocolType.TROJAN)
            LabFamily.SHADOWSOCKS -> base.copy(protocolType = ProtocolType.SHADOWSOCKS)
            LabFamily.OTHER -> base.copy(protocolType = ProtocolType.SOCKS5, transport = "tcp", security = "none")
        }
    }

    private fun Long?.outcome(): Outcome = this?.let { Outcome.Delay(it) } ?: Outcome.Failed("Timed out")

    private fun generated(family: LabFamily) = InboundGenerationResult(
        profile = profile(family.name, family), inboundJson = "{}", clientUri = "", protocol = family.serverProtocol!!,
        security = family.serverSecurity!!, port = 443, uuid = family.name, serverRemark = family.name,
        publishedToPanel = true, panelHost = "198.51.100.7", statusMessage = "", inboundId = family.ordinal + 1
    )

    @Test
    fun familiesAreRecognisedFromSavedConfigs() {
        for (family in LabFamily.values()) {
            assertEquals(family, LabFamily.of(profile("x", family)))
        }
    }

    @Test
    fun serverRunCreatesEveryFamilyAndRanksWorkingOnesByQuality() {
        // REALITY is blocked, WireGuard answers only sometimes, Hysteria2 is fast and steady.
        val latency = mapOf(
            LabFamily.REALITY.name to listOf<Long?>(null, null, null),
            LabFamily.XHTTP.name to listOf<Long?>(320, 340, 300),
            LabFamily.HYSTERIA2.name to listOf<Long?>(90, 95, 88),
            LabFamily.WIREGUARD.name to listOf<Long?>(70, null, null),
            LabFamily.HTTPUPGRADE.name to listOf<Long?>(210, 220, 900),
            LabFamily.WEBSOCKET.name to listOf<Long?>(250, 260, 255)
        )
        var round = -1
        val lab = ProtocolLab(
            measure = { profiles -> round++; profiles.map { latency.getValue(it.uuid)[round].outcome() } },
            provision = { generated(it) }
        )
        val results = lab.runOnServer()

        assertEquals(6, results.size)
        assertEquals(LabFamily.HYSTERIA2, results.first().family)
        assertEquals(LabFamily.REALITY, results.last().family)
        assertFalse(results.last().works)
        assertTrue(results.last().note.isNotBlank())
        // Steady 255 ms WebSocket beats HTTPUpgrade with a 900 ms spike, and the flaky WireGuard.
        val order = results.map { it.family }
        assertTrue(order.indexOf(LabFamily.WEBSOCKET) < order.indexOf(LabFamily.HTTPUPGRADE))
        assertTrue(order.indexOf(LabFamily.XHTTP) < order.indexOf(LabFamily.WIREGUARD))
        assertEquals(90L, results.first().medianMs)
        assertEquals(7L, results.first().jitterMs)
        assertTrue(results.all { it.generated != null })
    }

    @Test
    fun aFamilyTheServerCannotCreateIsReportedNotDropped() {
        val lab = ProtocolLab(
            measure = { profiles -> profiles.map { Outcome.Delay(100L) } },
            provision = { if (it == LabFamily.HYSTERIA2) error("no certificate") else generated(it) }
        )
        val results = lab.runOnServer(listOf(LabFamily.REALITY, LabFamily.HYSTERIA2), rounds = 1)
        assertEquals(listOf(LabFamily.REALITY, LabFamily.HYSTERIA2), results.map { it.family })
        assertTrue(results[1].note.contains("no certificate"))
        assertFalse(results[1].works)
    }

    @Test
    fun keepingOneConfigRemovesEveryOtherTestConfig() {
        val removed = mutableListOf<Int>()
        val lab = ProtocolLab(
            measure = { profiles -> profiles.map { Outcome.Delay(100L) } },
            provision = { generated(it) },
            discard = { removed += it.inboundId }
        )
        val results = lab.runOnServer(rounds = 1)
        lab.keepOnly(results[2], results)
        assertEquals(results.size - 1, removed.size)
        assertFalse(results[2].generated!!.inboundId in removed)
    }

    @Test
    fun savedConfigsAreTestedPerFamilyAndTheBestOfEachIsShown() {
        val slowReality = profile("reality-slow", LabFamily.REALITY)
        val fastReality = profile("reality-fast", LabFamily.REALITY)
        val worker = profile("bpb", LabFamily.CDN)
        val lab = ProtocolLab(measure = { profiles ->
            profiles.map { (when (it.uuid) { "reality-slow" -> 700L; "reality-fast" -> 120L; else -> null }).outcome() }
        })
        val results = lab.runOnSaved(listOf(slowReality, worker, fastReality), rounds = 2)
        assertEquals(listOf(LabFamily.REALITY, LabFamily.CDN), results.map { it.family })
        assertEquals("reality-fast", results[0].profile?.uuid)
        assertFalse(results[1].works)
    }

    @Test
    fun aConfigThatAnsweredRecentlyIsTestedEvenWhenSavedLast() {
        val now = 10_000_000_000L
        val dead = (1..5).map { profile("dead$it", LabFamily.REALITY) }
        val alive = profile("alive", LabFamily.REALITY).copy(lastLatencyMs = 300, lastTestedTimestamp = now - 60_000)
        val lab = ProtocolLab(measure = { profiles -> profiles.map { (if (it.uuid == "alive") 300L else null).outcome() } })
        val results = lab.runOnSaved(dead + alive, rounds = 1, now = now)
        assertEquals("alive", results.single().profile?.uuid)
        assertTrue(results.single().works)
    }

    @Test
    fun skippedOrInvalidConfigsDoNotCountAsNetworkFailures() {
        val lab = ProtocolLab(measure = { profiles -> profiles.map {
            when (it.uuid) {
                "busy" -> Outcome.NotRun("Core busy")
                "invalid" -> Outcome.Failed("Invalid config", request = false)
                else -> Outcome.Failed("Timed out")
            }
        } })
        val results = lab.runOnSaved(listOf(profile("busy", LabFamily.REALITY),
            profile("timed-out", LabFamily.REALITY), profile("invalid", LabFamily.TROJAN)), rounds = 2)
        assertEquals("timed-out", results.first().profile?.uuid)
        assertEquals(2, results.first().measuredRounds)
        assertEquals(0, results.last().measuredRounds)
        assertEquals("Invalid config", results.last().note)
    }

    @Test
    fun failedRunCleansUpCreatedInbounds() {
        val removed = mutableListOf<Int>()
        val lab = ProtocolLab(
            measure = { throw IllegalStateException("Probe failed") },
            provision = { generated(it) },
            discard = { removed += it.inboundId }
        )
        org.junit.Assert.assertThrows(IllegalStateException::class.java) {
            lab.runOnServer(listOf(LabFamily.REALITY, LabFamily.XHTTP))
        }
        assertEquals(listOf(generated(LabFamily.REALITY).inboundId, generated(LabFamily.XHTTP).inboundId), removed)
    }

    @Test
    fun priorityChangesTheWeighting() {
        // Fast but drops a round vs. slower but never fails.
        val fastFlaky = LabResult(LabFamily.HYSTERIA2, null, listOf(60, 70, null), 0)
        val slowSteady = LabResult(LabFamily.REALITY, null, listOf(400, 410, 405), 0)
        assertTrue(ProtocolLab.score(fastFlaky, LabPriority.FASTEST) > ProtocolLab.score(slowSteady, LabPriority.FASTEST))
        assertTrue(ProtocolLab.score(slowSteady, LabPriority.MOST_STABLE) > ProtocolLab.score(fastFlaky, LabPriority.MOST_STABLE))
        assertEquals(0, ProtocolLab.score(LabResult(LabFamily.WIREGUARD, null, listOf(null, null), 0), LabPriority.BALANCED))
    }
}
