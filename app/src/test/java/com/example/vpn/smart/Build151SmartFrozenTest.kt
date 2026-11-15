package com.example.vpn.smart

import com.example.data.model.VlessProfile
import com.example.vpn.diagnostics.FailureStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 151 (Samsung SM-F711B, Irancell metered, cf=0), replayed:
 * - 16:35:44 stage D picked p:b9917628cc (51.158.x.x) on one small request; it froze at 16050 B / 16384 B.
 * - 16:37:40 a new session started with that server selected; stage D then picked p:e63b7946e8 on the same /16,
 *   which froze at the same byte.
 * - 16:42:39 a reconnect passed one request; CONNECTED → DEGRADED on TIMEOUT 30 s later.
 */
class Build151SmartFrozenTest {
    private val net = "cell:43235"
    private fun p(id: String, address: String, security: String = "reality", transport: String = "tcp", sni: String = "") =
        VlessProfile(id = id, name = id, address = address, port = 443, uuid = "u", transport = transport,
            security = security, sni = sni, createdAt = 0L)

    private val b99 = p("b9917628cc", "51.158.10.20")
    private val e63 = p("e63b7946e8", "51.158.200.7")
    private val ccf = p("ccf9e98b43", "5.75.1.2")
    private val other = p("c7c9b07b2e", "91.107.3.4")
    private val cfA = p("cf-a", "104.16.1.1", security = "tls", transport = "ws", sni = "a.example.com")
    private val cfB = p("cf-b", "104.16.2.2", security = "tls", transport = "ws", sni = "b.example.com")
    private val all = listOf(b99, e63, ccf, other, cfA, cfB)

    private var stored: String? = null
    private var now = 1_794_760_601_687L
    private fun book() = FreezeBook({ stored }, { stored = it }, { now })

    @Test fun theFreezeMarkWrittenAt163641IsStillThereForTheSessionAt163740() {
        book().recordFrozen(net, b99.id)       // failover.freeze16k, before the uplink-shield retry
        now += 59_000                            // session ended, a new one started: a new FreezeBook over the same store
        assertEquals(setOf(b99.id), book().frozen(net))
    }

    @Test fun aServerOnTheFrozenServersSlash16IsTreatedAsFrozen() {
        val wide = FrozenGuard.widen(setOf(b99.id), all)
        assertEquals(setOf(b99.id, e63.id), wide)
        assertEquals("net:51.158.0.0/16", FrozenGuard.slash16(e63))
    }

    @Test fun cdnAndNameBasedServersAreNotGrouped() {
        assertEquals(setOf(cfA.id), FrozenGuard.widen(setOf(cfA.id), all))
        val named = p("named", "srv.example.com")
        assertEquals(setOf("named"), FrozenGuard.widen(setOf("named"), all + named))
        assertEquals(null, FrozenGuard.slash16(named))
    }

    @Test fun stageDAt163740NoLongerPicksTheSameSlash16() {
        val frozen = FrozenGuard.widen(book().also { it.recordFrozen(net, b99.id) }.frozen(net), all)
        val order = FreezeBook.order(listOf(e63, ccf, other), lastGood = emptyList(), frozen = frozen)
        val split = FrozenGuard.split(emptyList<VlessProfile>(), order.profiles, frozen, { it.id }, { it.id })
        assertEquals(listOf(ccf.id, other.id), split.candidates.map { it.id })
        assertEquals(1, split.leftOut)
    }

    @Test fun theSelectedFrozenServerIsSkippedWhileOthersRemainButIsTheLastResort() {
        val frozen = FrozenGuard.widen(setOf(b99.id), all)
        assertFalse(FrozenGuard.stealthAllowed(b99.id, frozen, all.map { it.id }))
        // e63 is selected and only shares the /16: also held while other servers remain.
        assertFalse(FrozenGuard.stealthAllowed(e63.id, frozen, all.map { it.id }))
        // Nothing else saved: the frozen ones are the last resort.
        assertTrue(FrozenGuard.stealthAllowed(b99.id, frozen, listOf(b99.id, e63.id)))
        val split = FrozenGuard.split(emptyList<VlessProfile>(), listOf(b99, e63), frozen, { it.id }, { it.id })
        assertEquals(listOf(b99.id, e63.id), split.candidates.map { it.id })
    }

    @Test fun theBackupPlanUsesTheSameWidening() {
        assertEquals(listOf(ccf.id, other.id),
            FailoverManager.withoutFrozen(listOf(e63, ccf, other), setOf(b99.id), all).map { it.id })
    }

    @Test fun theBulkGateWouldHaveCaughtB99BeforeItWonStageD() {
        // Stage D's winner on a metered network: gated.
        assertTrue(BulkGate.applies(smartPick = true, tunPath = true, metered = true, freezeHistory = false,
            recentlyGood = false, gatedSoFar = 0))
        // bulk.tun saw 16050 B then nothing: the gate's read of the same server.
        val read = BulkGate.Read.Cut(bytes = 16_050, idleMs = 4_900, timedOut = true, elapsedMs = 5_500)
        assertEquals(BulkGate.Verdict.FROZEN, BulkGate.verdict(read))
    }

    @Test fun anEarlyDegradeOnTimeoutIsAStrikeAndTwoMoreChecksSwitch() {
        val connectedAt = 1_794_760_961_957L          // 16:42:41
        val degradedAt = 1_794_760_989_305L           // 16:43:09
        assertTrue(StrikeWindow.earlyDegradeStrike(FailureStage.TIMEOUT, connectedAt, degradedAt))
        var t = degradedAt
        val w = StrikeWindow(clock = { t })
        w.fail()                                       // the early degrade
        t += 3_000; w.fail()                           // the watchdog's own failed checks
        assertFalse(w.shouldSwitch())
        t += 3_000; w.fail()
        assertTrue(w.shouldSwitch())
    }
}
