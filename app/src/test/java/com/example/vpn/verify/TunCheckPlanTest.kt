package com.example.vpn.verify

import com.example.vpn.diagnostics.events.DiagEvent
import com.example.vpn.diagnostics.events.ErrorAggregator
import com.example.vpn.diagnostics.events.TestRegistry
import com.example.vpn.routing.SplitTunnel
import com.example.vpn.routing.SplitTunnelPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.net.ConnectException
import java.net.SocketException

/**
 * Build 165: with a separate engine program the app is excluded from its own VPN, and every bind-based bulk.tun
 * failed in ~200-430 ms with a SocketException and 0 bytes. That is a check that cannot apply, not a broken TUN.
 */
class TunCheckPlanTest {
    private val self = "com.example"

    @Test fun anEngineProgramLeavesTheAppOutsideItsVpnSoTheTunCheckIsNotTestable() {
        val plan = SplitTunnelPlan.of(SplitTunnel(), self, excludeSelf = true) { true }
        assertTrue("the app is disallowed from its own VPN", self in plan.disallowed)
        assertEquals(TunCheckPlan.Mode.SELF_EXCLUDED, TunCheckPlan.mode(appOutsideVpn = true))
        // In-process Xray: the app stays inside and the bind-based check keeps running.
        val inside = SplitTunnelPlan.of(SplitTunnel(), self, excludeSelf = false) { true }
        assertFalse(self in inside.disallowed)
        assertEquals(TunCheckPlan.Mode.BIND_PROBE, TunCheckPlan.mode(appOutsideVpn = false))
    }

    @Test fun aSelfExcludedBulkTunIsRecordedNotTestableAndNeverCountsAsAFailure() {
        val events = mutableListOf<DiagEvent>()
        val reg = TestRegistry(clock = { 1_000L }, log = { events += it })
        reg.beginSession("s1")
        val id = reg.queue("bulk.tun", "s1", "p:1")
        reg.start(id)
        assertTrue(reg.notTestable(id, TunCheckPlan.selfExcludedResult("aether")))
        val e = reg.tests.value.single()
        assertEquals(TestRegistry.State.NOT_TESTABLE, e.state)
        assertNull("no failure stage", e.failureStage)
        assertTrue(e.finished)
        assertTrue(e.result!!.startsWith(TunCheckPlan.NOT_TESTABLE_SELF_EXCLUDED))
        assertTrue(e.result!!.contains(TunCheckPlan.SELF_EXCLUDED_LINE))
        assertEquals("NOT_TESTABLE", events.last().result)
        assertTrue("not among the most frequent failures", ErrorAggregator.aggregate(events).isEmpty())
        // A later result for it is refused: it is finished.
        assertFalse(reg.finish(id, false, "late"))
    }

    @Test fun androidRefusingTheVpnBindMapsToNotTestable() {
        val eperm = SocketException("Binding socket to network 103 failed: EPERM (Operation not permitted)")
        assertTrue(TunCheckPlan.isBindRefusal(eperm))
        assertTrue(TunCheckPlan.isBindRefusal(java.io.IOException("wrapped", eperm)))
        assertTrue(TunCheckPlan.isBindRefusal(SocketException("setsockopt failed: EPERM (Operation not permitted)")))
        assertTrue(TunCheckPlan.isBindRefusal(VpnBindRefusedException("refused")))
        // Ordinary route failures stay failures.
        assertFalse(TunCheckPlan.isBindRefusal(SocketException("Connection reset")))
        assertFalse(TunCheckPlan.isBindRefusal(ConnectException("Connection refused")))
        assertFalse(TunCheckPlan.isBindRefusal(java.net.SocketTimeoutException("timeout")))
        assertFalse(TunCheckPlan.isBindRefusal(null))

        val reason = TunCheckPlan.bindPreflight { throw eperm }
        assertTrue(reason!!.contains("refused to bind"))
        assertTrue(reason.contains("EPERM"))
        assertNull(TunCheckPlan.bindPreflight { Closeable { } })
        // Another problem is not a refusal: the check itself runs and reports it.
        assertNull(TunCheckPlan.bindPreflight { throw IllegalStateException("VPN network unavailable") })
        assertTrue(TunCheckPlan.bindRefusedResult("x").startsWith(TunCheckPlan.NOT_TESTABLE_BIND_REFUSED))
    }

    @Test fun aRefusedBindGivesNotTestedLegsNotAFailedTransfer() {
        val refused = Dialer { _, _, _ -> throw VpnBindRefusedException("Android refused to bind the app's socket to the VPN network") }
        val evidence = TrafficCheck(refused, tls = false,
            downloads = listOf(TrafficCheck.Target("httpbin.org", 443, "/bytes/65536", 65536)),
            uploads = listOf(TrafficCheck.Target("speed.cloudflare.com", 443, "/__up", echoes = false)),
            stepTimeoutMs = 1_000).both("a1", 0, ProbePath.TUN)
        assertTrue(evidence.download is LegResult.NotTested)
        assertTrue(evidence.upload is LegResult.NotTested)
        assertTrue(evidence.bindRefused)
        assertFalse(evidence.bidirectional)
        assertFalse(evidence.bulkStalled)
        assertFalse(evidence.fixedSizeFreeze)
        assertFalse(evidence.uploadFreezeDeadDownload)

        // A plain SocketException (what build 165 recorded) is still a failure when it is not a refused bind.
        val reset = Dialer { _, _, _ -> throw SocketException("Connection reset") }
        val failed = TrafficCheck(reset, tls = false,
            downloads = listOf(TrafficCheck.Target("httpbin.org", 443, "/bytes/65536", 65536)),
            uploads = listOf(TrafficCheck.Target("speed.cloudflare.com", 443, "/__up", echoes = false)),
            stepTimeoutMs = 1_000).both("a1", 0, ProbePath.TUN)
        assertFalse(failed.bindRefused)
        assertTrue(failed.download is LegResult.Failed)
    }
}
