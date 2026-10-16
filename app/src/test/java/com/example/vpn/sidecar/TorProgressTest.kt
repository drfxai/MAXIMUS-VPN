package com.example.vpn.sidecar

import org.junit.Assert.assertEquals
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
}
