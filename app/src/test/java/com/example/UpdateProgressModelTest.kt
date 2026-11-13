package com.example

import com.example.update.AppUpdateChecker
import com.example.update.UpdateProgressModel
import com.example.update.UpdateProgressModel.Phase
import com.example.update.UpdateProgressModel.Step
import org.junit.Assert.*
import org.junit.Test

class UpdateProgressModelTest {
    private fun state(downloading: Boolean = true, verified: Boolean = false, error: String? = null,
                      status: String? = null, percent: Int = 0, size: Long? = 135_920_640L) =
        UpdateProgressModel.state(downloading, verified, error, status, percent, size)

    @Test fun phasesFollowTheUpdaterStatusLines() {
        assertEquals(Phase.IDLE, state(downloading = false).phase)
        assertEquals(Phase.CONNECTING, state(status = "Connecting to official GitHub release…").phase)
        assertEquals(Phase.DOWNLOADING, state(status = "Received 2569 / 132735 KiB", percent = 2).phase)
        assertEquals(Phase.CHECKING_DIGEST, state(status = "Checking SHA-256 digest…", percent = 100).phase)
        assertEquals(Phase.CHECKING_SIGNATURE, state(status = "Verifying publisher signature and Android package…", percent = 100).phase)
        assertEquals(Phase.READY, state(downloading = false, verified = true).phase)
        assertEquals(Phase.FAILED, state(downloading = false, error = "APK SHA-256 verification failed").phase)
    }

    @Test fun stepsCompleteInOrder() {
        val downloading = state(status = "Received 10 / 100 KiB", percent = 10)
        assertEquals(Step.DOWNLOAD, downloading.activeStep)
        assertFalse(downloading.isDone(Step.DOWNLOAD))
        val verifying = state(status = "Checking SHA-256 digest…", percent = 100)
        assertEquals(Step.VERIFY, verifying.activeStep)
        assertTrue(verifying.isDone(Step.DOWNLOAD))
        assertFalse(verifying.isDone(Step.VERIFY))
        val ready = state(downloading = false, verified = true)
        assertTrue(ready.isDone(Step.DOWNLOAD) && ready.isDone(Step.VERIFY))
        assertFalse("Installing is Android's step, after the user taps", ready.isDone(Step.INSTALL))
        assertEquals(100, ready.percent)
    }

    @Test fun bytesComeFromTheUpdaterOrTheDeclaredSize() {
        val reported = state(status = "Received 2569 / 132735 KiB", percent = 2)
        assertEquals(2569L * 1024, reported.receivedBytes)
        assertEquals(132735L * 1024, reported.totalBytes)
        val derived = state(status = "Connected • receiving 129 MiB signed APK", percent = 50, size = 1_000_000L)
        assertEquals(500_000L, derived.receivedBytes)
        assertNull("No invented bytes before the first one arrives", state(percent = 0).receivedBytes)
    }

    @Test fun humanReadableSizesSpeedAndEta() {
        assertEquals("2.6 MB", UpdateProgressModel.megabytes(2_630_656L))
        assertEquals("136 MB", UpdateProgressModel.megabytes(135_920_640L))
        assertNull(UpdateProgressModel.bytesPerSecond(listOf(0L to 0L)))
        assertNull(UpdateProgressModel.bytesPerSecond(listOf(0L to 0L, 300L to 1_000L)))
        assertEquals(1_000_000.0, UpdateProgressModel.bytesPerSecond(listOf(0L to 0L, 2_000L to 2_000_000L))!!, 0.1)
        assertEquals("1.5 MB/s", UpdateProgressModel.speed(1_500_000.0))
        assertEquals("640 KB/s", UpdateProgressModel.speed(640_000.0))
        assertEquals("~30 s left", UpdateProgressModel.eta(30_000_000L, 1_000_000.0))
        assertEquals("~2 min left", UpdateProgressModel.eta(120_000_000L, 1_000_000.0))
        assertNull(UpdateProgressModel.eta(1_000L, null))
        assertNull(UpdateProgressModel.eta(0L, 1_000.0))
    }

    @Test fun releaseNotesArePlainTextWithoutTheBuildMarker() {
        assertEquals(emptyList<String>(), AppUpdateChecker.releaseNotes("Build 136\n\n<!-- maximus-build: 136 -->"))
        val notes = AppUpdateChecker.releaseNotes("""
            ## What's new
            - **Aether** retries WireGuard with [obfuscation](https://example.com)
            * <b>Faster</b> Smart Connect
            <!-- maximus-build: 140 -->
        """.trimIndent())
        assertEquals(listOf("What's new", "Aether retries WireGuard with obfuscation", "Faster Smart Connect"), notes)
        val long = AppUpdateChecker.releaseNotes((1..20).joinToString("\n") { "- line $it " + "x".repeat(300) })
        assertEquals(AppUpdateChecker.MAX_NOTES, long.size)
        assertTrue(long.all { it.length <= AppUpdateChecker.MAX_NOTE_CHARS })
    }
}
