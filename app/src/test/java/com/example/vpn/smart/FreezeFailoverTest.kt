package com.example.vpn.smart

import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.RecoverySecurityGate
import com.example.vpn.smart.FreezeFailover.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The ~16 KB freeze: same server with deep fragmentation first, then a verified backup, never a pinned config. */
class FreezeFailoverTest {
    private val fr = VlessProfile(id = "fr14", name = "FR · VLESS 14", address = "1.2.3.4", port = 443, uuid = "u1",
        transport = "tcp", security = "reality", publicKey = "pbk", fingerprint = "chrome", createdAt = 0L)
    private fun p(id: String, transport: String = "tcp", security: String = "tls", address: String = "$id.example", uuid: String = "u") =
        VlessProfile(id = id, name = id, address = address, port = 443, uuid = uuid, transport = transport, security = security, createdAt = 0L)
    private val de = p("de")
    private val nl = p("nl")
    private val frozenServer = p("frozen")

    private fun input(
        rescueTried: Boolean = false, pinned: Boolean = false, switches: Int = 0,
        runnersUp: List<VlessProfile> = emptyList(), lastGood: List<VlessProfile> = emptyList(),
        frozen: Set<String> = setOf("fr14"), saved: List<VlessProfile> = emptyList(), server: VlessProfile = fr,
        engine: Boolean = false
    ) = FreezeFailover.Input(server, server, pinned, rescueTried, switches, runnersUp, lastGood, frozen, saved, engine)

    @Test fun firstFreezeRetriesTheSameServerWithDeepFragmentation() {
        val d = FreezeFailover.decide(input(runnersUp = listOf(de)))
        assertTrue(d is Decision.Retry)
        d as Decision.Retry
        assertEquals("fr14", d.serverId)
        assertEquals("fr14", d.variant.id)
        assertEquals(FreezeFailover.Rescue.DEEP_FRAGMENT, d.rescue)
        assertEquals(FreezeFailover.DEEP_FRAGMENT_MASK, d.variant.finalMask)
        // Only the mask changes: REALITY key, fingerprint and address stay as saved.
        assertEquals(fr.copy(finalMask = FreezeFailover.DEEP_FRAGMENT_MASK), d.variant)
        assertEquals("failover · freeze16k · FRAGMENT FR · VLESS 14", FreezeFailover.describe(d) { "FR · VLESS 14" })
    }

    @Test fun theDeepMaskIsUacsRescueAndPassesTheSecurityGate() {
        assertNull(RecoverySecurityGate.maskProblem(FreezeFailover.DEEP_FRAGMENT_MASK))
        val settings = org.json.JSONObject(FreezeFailover.DEEP_FRAGMENT_MASK).getJSONArray("tcp").getJSONObject(0).getJSONObject("settings")
        assertEquals("tlshello", settings.getString("packets"))
        assertEquals("100", settings.getString("maxSplit"))
        assertEquals("5", settings.getString("delay"))
    }

    @Test fun stillFrozenAfterTheRetrySwitchesToTheNextVerifiedBackup() {
        val d = FreezeFailover.decide(input(rescueTried = true, runnersUp = listOf(de), lastGood = listOf(nl)))
        assertEquals(Decision.Switch("fr14", de), d)
        assertEquals("failover · freeze16k · SWITCH FR -> de", FreezeFailover.describe(d) { if (it == "fr14") "FR" else it })
    }

    @Test fun backupsSkipFrozenServersAndTheCurrentOne() {
        val backups = FreezeFailover.backups("fr14", runnersUp = listOf(fr, frozenServer, de),
            lastGood = listOf(de, nl), frozen = setOf("frozen"))
        assertEquals(listOf("de", "nl"), backups.map { it.id })
        val d = FreezeFailover.decide(input(rescueTried = true, runnersUp = listOf(frozenServer), lastGood = listOf(nl),
            frozen = setOf("fr14", "frozen")))
        assertEquals(Decision.Switch("fr14", nl), d)
    }

    @Test fun withoutABackupTheSwitchIsARaceWithoutFrozenServers() {
        val d = FreezeFailover.decide(input(rescueTried = true))
        assertEquals(Decision.Switch("fr14", null), d)
        assertEquals("failover · freeze16k · SWITCH fr14 -> Smart Connect race (frozen servers left out)",
            FreezeFailover.describe(d) { it })
    }

    @Test fun switchesStopAtTheLimit() {
        val d = FreezeFailover.decide(input(rescueTried = true, switches = FreezeFailover.MAX_SWITCHES, runnersUp = listOf(de)))
        assertTrue(d is Decision.Fail)
        assertTrue(FreezeFailover.describe(d) { it }.startsWith("failover · freeze16k · FAIL"))
    }

    @Test fun aPinnedConfigIsNeverChangedOrSwitched() {
        val d = FreezeFailover.decide(input(pinned = true, runnersUp = listOf(de)))
        assertTrue(d is Decision.Fail)
    }

    @Test fun aTransportWithoutTcpTlsUsesTheSavedXhttpFormOfTheSameServer() {
        val quic = p("q", transport = "quic", address = "9.9.9.9", uuid = "uq")
        val xhttp = p("qx", transport = "xhttp", address = "9.9.9.9", uuid = "uq")
        val other = p("ox", transport = "xhttp", address = "8.8.8.8", uuid = "uq")
        val d = FreezeFailover.decide(input(server = quic, frozen = setOf("q"), saved = listOf(other, quic, xhttp)))
        assertEquals(Decision.Retry("q", xhttp, FreezeFailover.Rescue.XHTTP), d)
        assertEquals("failover · freeze16k · XHTTP q", FreezeFailover.describe(d) { it })
        // A frozen XHTTP form is not a rescue; no form at all goes straight to the switch.
        assertTrue(FreezeFailover.decide(input(server = quic, frozen = setOf("q", "qx"), saved = listOf(xhttp))) is Decision.Switch)
        assertTrue(FreezeFailover.decide(input(server = quic, frozen = setOf("q"))) is Decision.Switch)
    }

    @Test fun onlyTcpTlsOrRealityLinksTakeTheMask() {
        assertTrue(FreezeFailover.canDeepFragment(fr))
        assertTrue(FreezeFailover.canDeepFragment(p("ws", transport = "ws")))
        assertFalse(FreezeFailover.canDeepFragment(p("plain", security = "none")))
        assertFalse(FreezeFailover.canDeepFragment(p("quic", transport = "quic")))
        assertFalse(FreezeFailover.canDeepFragment(p("h3", transport = "xhttp").copy(alpn = "h3")))
        assertFalse(FreezeFailover.canDeepFragment(fr.copy(profileType = ProfileType.XRAY_JSON)))
        assertFalse(FreezeFailover.canDeepFragment(fr.copy(protocolType = ProtocolType.HYSTERIA2)))
        assertFalse(FreezeFailover.canDeepFragment(FreezeFailover.deepFragment(fr)))
    }

    @Test fun anEngineProgramGoesStraightToTheSwitch() {
        assertEquals(Decision.Switch("fr14", de), FreezeFailover.decide(input(engine = true, runnersUp = listOf(de))))
    }
}
