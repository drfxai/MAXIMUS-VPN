package com.example

import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.SidecarProcess
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch

class AetherReadinessTest {
    /** Models Aether holding its listener while registration and gateway scanning are pending. */
    private class PendingEngine : Process() {
        private val output = PipedInputStream()
        private val writer = PipedOutputStream(output)
        private val ended = CountDownLatch(1)
        override fun getInputStream() = output
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun exitValue(): Int {
            if (ended.count > 0) throw IllegalThreadStateException()
            return 0
        }
        override fun waitFor(): Int { ended.await(); return 0 }
        override fun destroy() { writer.close(); ended.countDown() }
        fun line(value: String) { writer.write("$value\n".toByteArray()); writer.flush() }
        fun serving(port: Int) = line("MAXIMUS_AETHER_SOCKS_READY:$port")
    }

    @Test fun boundPortDoesNotPassUntilTunnelStartsServing() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
            val child = PendingEngine()
            val process = SidecarProcess("aether", child, listener.localPort, AetherSidecar.readyLine(listener.localPort), logOutput = false)
            try {
                assertFalse("A bound socket is not a ready WARP tunnel", process.awaitReady(150))
                child.serving(listener.localPort)
                assertTrue("Serving marker and listener must both be ready", process.awaitReady(2000))
            } finally { process.stop() }
        }
    }

    @Test fun accountTimeoutRetainsCauseAndCannotPassAsReady() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
            val child = PendingEngine()
            val process = SidecarProcess("aether", child, listener.localPort, AetherSidecar.readyLine(listener.localPort), logOutput = false)
            try {
                child.line("MAXIMUS_AETHER_STAGE:account")
                child.line("registration retry: network operation timed out; private-token-must-not-escape")
                assertFalse(process.awaitReady(500))
                val failure = process.startupFailure.orEmpty()
                assertTrue(failure.startsWith("WARP API registration failed"))
                assertTrue(failure.contains("network operation timed out"))
                assertFalse(failure.contains("private-token-must-not-escape"))
            } finally { process.stop() }
        }
    }

    @Test fun completedIdentityFollowedByScanIsNotMisreportedAsRegistrationFailure() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
            val child = PendingEngine()
            val process = SidecarProcess("aether", child, listener.localPort, AetherSidecar.readyLine(listener.localPort), logOutput = false)
            try {
                child.line("MAXIMUS_AETHER_STAGE:account")
                child.line("registration retry failed: HTTP 429")
                child.line("MAXIMUS_AETHER_STAGE:identity")
                child.line("MAXIMUS_AETHER_STAGE:scan")
                assertFalse(process.awaitReady(500))
                assertTrue(process.startupFailure.orEmpty().contains("gateway scan"))
                assertFalse(process.startupFailure.orEmpty().contains("registration failed"))
            } finally { process.stop() }
        }
    }

    @Test fun arbitraryStageTextIsNeverPublished() {
        assertNull(SidecarProcess.aetherStage("MAXIMUS_AETHER_STAGE:secret-token"))
        assertNull(SidecarProcess.aetherStage("scan"))
        assertNull(SidecarProcess.aetherStage("prefix MAXIMUS_AETHER_STAGE:account"))
    }

    @Test fun servingMarkerCannotPassWithNoListener() {
        val port = ServerSocket(0).use { it.localPort }
        val child = PendingEngine()
        val process = SidecarProcess("aether", child, port, AetherSidecar.readyLine(port), logOutput = false)
        try {
            child.serving(port)
            assertFalse(process.awaitReady(200))
        } finally { process.stop() }
    }
}
