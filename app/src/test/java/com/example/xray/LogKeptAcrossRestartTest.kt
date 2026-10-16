package com.example.xray

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The log is written through to disk so a crash or forced stop does not wipe it; panic deletes it. */
class LogKeptAcrossRestartTest {
    @Test fun previousRunIsShownAfterARestartAndPanicWipesIt() {
        val dir = Files.createTempDirectory("logs").toFile()
        // What an earlier run left on disk before the process died.
        File(dir, "session.log").writeText("[10:00:00.000] [VPN] before the crash\n")
        XrayLogManager.attachDisk(dir)
        XrayLogManager.i("TEST", "after the restart")
        val logs = XrayLogManager.getLogs()
        assertTrue(logs.any { it.contains("before the crash") })
        assertTrue(logs.any { it.contains("Previous run") })
        assertTrue(File(dir, "session.log").readText().contains("after the restart"))

        XrayLogManager.wipeDisk()
        assertTrue(XrayLogManager.getLogs().isEmpty())
        assertFalse(File(dir, "previous-session.log").exists())
        assertFalse(File(dir, "session.log").readText().contains("after the restart"))
    }
}
