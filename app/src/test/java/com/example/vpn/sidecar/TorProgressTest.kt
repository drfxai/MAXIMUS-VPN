package com.example.vpn.sidecar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Tor's own progress reaches the app log, so a stalled start shows where it stalled. */
class TorProgressTest {
    @Test fun reportsBootstrapStepsAndWarningsOnlyOnce() {
        val file = Files.createTempFile("tor", ".log").toFile()
        val progress = TorProgress(file)
        file.appendText("Oct 16 20:45:26.000 [notice] Opening Socks listener on 127.0.0.1:9050\n")
        file.appendText("Oct 16 20:45:27.000 [notice] Bootstrapped 1% (conn_pt): Connecting to pluggable transport\n")
        file.appendText("Oct 16 20:45:40.000 [notice] Bootstrapped 10% (conn_done): Conn")
        assertEquals(listOf("Tor 1%: Connecting to pluggable transport"), progress.newLines())
        file.appendText("ected to a relay\nOct 16 20:46:00.000 [warn] Proxy Client: unable to connect OR connection (handshaking (proxy))\n")
        val next = progress.newLines()
        assertEquals("Tor 10%: Connected to a relay", next[0])
        assertTrue(next[1].startsWith("Tor warning: Proxy Client"))
        assertTrue(progress.newLines().isEmpty())
    }

    @Test fun aStartThatKeepsAdvancingGetsMoreTimeUpToTheCap() {
        val d = TorStartDeadline(startedAt = 0, baseMs = 120_000)
        d.saw("Tor 10%: Connected to a relay", 13_000)
        d.saw("Tor 50%: Loading relay descriptors", 34_000)
        // The phone run of 2026-10-17 was cut at 128 s while loading descriptors.
        assertFalse(d.expired(128_000))
        assertTrue(d.expired(34_000 + TorStartDeadline.STALL_MS))
        d.saw("Tor 75%: Connecting to a relay to build circuits", 180_000)
        d.saw("Tor 90%: Establishing a Tor circuit", 300_000)
        assertTrue(d.expired(TorStartDeadline.CAP_MS))
    }

    @Test fun aStartThatStopsAdvancingEndsOnTheBaseTimeout() {
        val d = TorStartDeadline(startedAt = 0, baseMs = 120_000)
        d.saw("Tor 50%: Loading relay descriptors", 10_000)
        d.saw("Tor 50%: Loading relay descriptors", 100_000)
        d.saw("Tor warning: something", 110_000)
        assertFalse(d.expired(159_000))
        assertTrue(d.expired(160_000))
        val quiet = TorStartDeadline(startedAt = 0, baseMs = 120_000)
        assertTrue(quiet.expired(120_000))
    }
}
