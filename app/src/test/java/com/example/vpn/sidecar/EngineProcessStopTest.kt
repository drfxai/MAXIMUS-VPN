package com.example.vpn.sidecar

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class EngineProcessStopTest {
    private class StubbornProcess : Process() {
        var dead = false
        var terms = 0
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int = exitValue()
        override fun exitValue(): Int = if (dead) 0 else throw IllegalThreadStateException()
        override fun destroy() { terms++ }
    }

    @Test fun cancellationCannotSkipForcedCleanupAndInterruptIsPreserved() {
        val process = StubbornProcess()
        Thread.currentThread().interrupt()
        try {
            EngineProcessStop.stop(process, graceMs = 1) { (it as StubbornProcess).dead = true }
            assertTrue(process.dead)
            assertEquals(1, process.terms)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }
}
