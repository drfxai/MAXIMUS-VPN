package com.example.vpn.smart

import com.example.vpn.connections.IrancellWarp
import com.example.vpn.sidecar.TorSidecar
import org.junit.Assert.*
import org.junit.Test

class RecoveryEngineCapsTest {
    private val wt1 = "webtunnel 192.0.2.3:443 AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA url=https://alwaysnewbie.example/path ver=0.0.1"
    private val wt2 = "webtunnel 192.0.2.4:443 BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB url=https://nextcloud.example/other ver=0.0.1"

    @Test fun byedpiAndTorAreCappedOnlyOnConstrainedNetworks() {
        assertTrue(RecoveryEngineCaps.constrained(metered = true, cloudflareBlocked = false))
        assertTrue(RecoveryEngineCaps.constrained(metered = false, cloudflareBlocked = true))
        assertFalse(RecoveryEngineCaps.constrained(metered = null, cloudflareBlocked = false))

        val open = RecoveryEngineCaps.Budget(constrained = false)
        assertEquals(420_000L, open.window("tor", RecoveryAttemptPolicy.window("tor", 300_000)))
        assertEquals(90_000L, open.window("byedpi", 90_000))

        val capped = RecoveryEngineCaps.Budget(constrained = true)
        assertEquals(45_000L, capped.window("tor", RecoveryAttemptPolicy.window("tor", 300_000)))
        assertEquals(10_000L, capped.window("byedpi", 90_000))
        // Other engines keep their usual window.
        assertEquals(90_000L, capped.window("psiphon", 90_000))
        assertNull(capped.left("psiphon"))
    }

    @Test fun capsAreSharedAcrossEveryAttemptOfTheSameEngineInOneSearch() {
        val b = RecoveryEngineCaps.Budget(constrained = true)
        b.charge("byedpi", 6_000)
        assertEquals(4_000L, b.window("byedpi", 90_000))
        b.charge("byedpi", 3_000)
        assertNull("under 2 s left: no ByeDPI start", b.window("byedpi", 90_000))

        b.charge("tor", 20_500) // WebTunnel aborted for no progress
        assertEquals(24_500L, b.window("tor", 420_000)) // Snowflake/obfs4/meek still fit in the cap
        b.charge("tor", 24_500)
        assertNull("Tor stays within 45 s in total", b.window("tor", 420_000))
        assertEquals(45_000L, b.spent("tor"))
    }

    @Test fun byedpiStopsAfterTwoConsecutiveRecvTimeouts() {
        val w = RecoveryEngineCaps.ByedpiWatch()
        w.saw("recv: Connection timed out")
        assertNull(w.abortReason())
        w.saw("connect: 192.0.2.1:443 ok")
        w.saw("recv: Connection timed out")
        assertNull("the run was broken by another line", w.abortReason())
        w.saw("recv: Connection timed out")
        assertNotNull(w.abortReason())
        assertTrue(w.abortReason()!!.contains("2 consecutive"))
    }

    @Test fun torStopsWhenBootstrapMakesNoProgressFor20Seconds() {
        val w = RecoveryEngineCaps.TorWatch(startedAt = 0)
        w.saw("Tor 10%: Finishing handshake with directory server", 1_000)
        w.saw("Tor 25%: Asking for networkstatus consensus", 8_000)
        assertNull(w.abortReason(27_000))
        w.saw("Tor 25%: Asking for networkstatus consensus", 20_000) // the same percentage is not progress
        assertNull(w.abortReason(27_999))
        val reason = w.abortReason(28_000)
        assertNotNull(reason)
        assertTrue(reason!!.contains("stuck at 25%"))
        // A start that never reports any percentage is also stopped.
        assertNotNull(RecoveryEngineCaps.TorWatch(startedAt = 0).abortReason(RecoveryEngineCaps.TOR_NO_PROGRESS_MS))
    }

    @Test fun aWebTunnelBridgeIsDroppedAfterThreeResetsOrUnrecognizedReplies() {
        val w = RecoveryEngineCaps.TorWatch(startedAt = 0)
        w.bridge(wt1); w.bridge(wt2)
        repeat(2) { w.saw("webtunnel dial alwaysnewbie.example: read tcp: connection reset by peer", 1_000L + it) }
        assertTrue(w.dropped().isEmpty())
        w.saw("[warn] Proxy Client: unable to connect OR connection with 192.0.2.3:443: connection reset by peer", 2_000)
        assertEquals(setOf("alwaysnewbie.example"), w.dropped())
        assertNull("one bridge is still untried", w.abortReason(3_000))
        repeat(3) { w.saw("nextcloud.example: unrecognized reply", 4_000L + it) }
        assertEquals(setOf("alwaysnewbie.example", "nextcloud.example"), w.dropped())
        assertTrue(w.abortReason(5_000)!!.contains("every WebTunnel bridge"))
    }

    @Test fun unnamedFailuresCountOnlyWhenTheBridgeIsUnambiguous() {
        // Both seeds resolved to the same front address (the Irancell field case): an unnamed reset counts for both.
        val same = RecoveryEngineCaps.TorWatch(startedAt = 0)
        same.bridge("$wt1 addr=198.51.100.7:443"); same.bridge("$wt2 addr=198.51.100.7:443")
        repeat(3) { same.saw("read: connection reset by peer", it.toLong()) }
        assertEquals(2, same.dropped().size)

        // Different addresses: an unnamed reset is not pinned on either bridge.
        val apart = RecoveryEngineCaps.TorWatch(startedAt = 0)
        apart.bridge("$wt1 addr=198.51.100.7:443"); apart.bridge("$wt2 addr=198.51.100.8:443")
        repeat(3) { apart.saw("read: connection reset by peer", it.toLong()) }
        assertTrue(apart.dropped().isEmpty())
    }

    @Test fun droppedBridgesAreRememberedPerNetworkForSixHours() {
        var now = 0L
        val book = WebTunnelFailureBook(clock = { now })
        book.drop("irancell", "alwaysnewbie.example")
        assertEquals(listOf(wt2), book.usable("irancell", listOf(wt1, wt2)))
        assertEquals("another network is unaffected", listOf(wt1, wt2), book.usable("wifi-home", listOf(wt1, wt2)))
        val snowflake = TorSidecar.SNOWFLAKE.first()
        assertEquals("non-WebTunnel bridges are kept", listOf(snowflake), book.usable("irancell", listOf(wt1, snowflake)))
        now = RecoveryEngineCaps.BRIDGE_MEMORY_MS - 1
        assertTrue(book.dropped("irancell", "alwaysnewbie.example"))
        now = RecoveryEngineCaps.BRIDGE_MEMORY_MS
        assertFalse(book.dropped("irancell", "alwaysnewbie.example"))
    }

    @Test fun droppedSeedsLeaveTheRecoveryCatalogButSnowflakeStays() {
        val book = WebTunnelFailureBook(clock = { 0L })
        book.drop("irancell", "alwaysnewbie.example")
        book.drop("irancell", "nextcloud.example")
        val all = RecoveryEngineCatalog.candidates(emptyList(), seedBridges = listOf(wt1, wt2)) { true }
        assertTrue(all.any { it.id.startsWith("recovery-webtunnel-") })
        val left = RecoveryEngineCatalog.candidates(emptyList(), seedBridges = book.usable("irancell", listOf(wt1, wt2))) { true }
        assertFalse(left.any { it.id.startsWith("recovery-webtunnel-") })
        assertTrue(left.any { it.id == "recovery-tor-snowflake" })
    }

    @Test fun irancellWarpRunsBeforeTorOnlyOnIrancellWithCloudflareBlocked() {
        assertTrue(RecoveryEngineCaps.irancellWarpFirst(irancell = true, cloudflareBlocked = true, aetherHeldBack = true))
        assertFalse(RecoveryEngineCaps.irancellWarpFirst(irancell = false, cloudflareBlocked = true, aetherHeldBack = true))
        assertFalse(RecoveryEngineCaps.irancellWarpFirst(irancell = true, cloudflareBlocked = false, aetherHeldBack = true))
        assertFalse(RecoveryEngineCaps.irancellWarpFirst(irancell = true, cloudflareBlocked = true, aetherHeldBack = false))
        // The WARP window is IrancellWarp's 15 s budget plus a short traffic check: far below Tor's cap.
        assertEquals(IrancellWarp.BUDGET_MS + 5_000L, RecoveryEngineCaps.IRANCELL_WARP_WINDOW_MS)
        assertTrue(RecoveryEngineCaps.IRANCELL_WARP_WINDOW_MS < RecoveryEngineCaps.TOR_CAP_MS)
    }

    @Test fun escalationSummaryIsOneLinePerSearch() {
        val s = RecoveryEngineCaps.EscalationSummary()
        assertTrue(s.line("no path").contains("no engine was tried"))
        s.add("irancell-warp", 15_200, "failed (registration)")
        s.add("byedpi", 4_100, "aborted (2 consecutive recv timeouts)")
        s.add("tor", 20_300, "aborted (no bootstrap progress for 20s)")
        s.add("tor", 24_700, "capped")
        val line = s.line("no path (every available family was tested and none carried traffic)")
        assertFalse(line.contains('\n'))
        assertEquals("Escalation summary: irancell-warp 15.2s failed (registration) · byedpi 4.1s aborted (2 consecutive recv timeouts) · " +
            "tor×2 45.0s aborted (no bootstrap progress for 20s); capped → no path (every available family was tested and none carried traffic)", line)
    }
}
