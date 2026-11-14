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

    @Test fun aRescanRevokesReadinessUntilTheNewTransportServes() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
            val child = PendingEngine()
            val process = SidecarProcess("aether", child, listener.localPort, AetherSidecar.readyLine(listener.localPort), logOutput = false)
            try {
                child.serving(listener.localPort)
                assertTrue(process.awaitReady(2_000))
                val before = process.transportGeneration
                child.line("MAXIMUS_AETHER_STAGE:scan")
                val deadline = System.nanoTime() + 2_000_000_000L
                while (process.transportGeneration == before && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(process.transportGeneration > before)
                assertFalse(process.awaitReady(150))
                child.serving(listener.localPort)
                assertTrue(process.awaitReady(2_000))
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

    @Test fun deadAccountHelperStopsWaitingWithoutAcceptingBoundPort() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
            val child = PendingEngine()
            val process = SidecarProcess("aether", child, listener.localPort, AetherSidecar.readyLine(listener.localPort), logOutput = false)
            try {
                val began = System.nanoTime()
                assertFalse(process.awaitReadyWhile(180_000) { false })
                assertTrue((System.nanoTime() - began) / 1_000_000 < 1_000)
                assertTrue(process.startupFailure.orEmpty().contains("helper stopped"))
            } finally { process.stop() }
        }
    }

    @Test fun interruptedReadinessStopsRatherThanWaitingForAllCarriers() {
        val child = PendingEngine()
        val port = ServerSocket(0).use { it.localPort }
        val process = SidecarProcess("aether", child, port, AetherSidecar.readyLine(port), logOutput = false)
        val cancelled = java.util.concurrent.atomic.AtomicBoolean()
        val worker = Thread {
            try { process.awaitReady(180_000) }
            catch (_: InterruptedException) { cancelled.set(true) }
            finally { process.stop() }
        }
        worker.start()
        worker.interrupt()
        worker.join(2_000)
        assertFalse("An interrupted engine wait must release its worker", worker.isAlive)
        assertTrue(cancelled.get())
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

    @Test fun irancellRegistrationFailureEndsTheWaitWithoutBurningTheWindow() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
            val child = PendingEngine()
            val process = SidecarProcess("aether", child, listener.localPort, AetherSidecar.readyLine(listener.localPort), logOutput = false)
            try {
                child.line("MAXIMUS_AETHER_STAGE:account")
                child.line("registration failed: tls handshake failed")
                val watch = com.example.vpn.connections.IrancellWarp.RegistrationWatch(graceMs = 200L)
                val started = System.nanoTime()
                assertFalse(process.awaitReadyUnless(10_000L) { watch.shouldStop(process.startupFailure, System.currentTimeMillis()) })
                val tookMs = (System.nanoTime() - started) / 1_000_000
                assertTrue("gave up after the grace, not the 10 s window (took $tookMs ms)", tookMs < 5_000L)
                assertTrue(process.startupFailure.orEmpty().startsWith("WARP API registration failed"))
            } finally { process.stop() }
        }
    }

    @Test fun registrationClearedByALaterStageKeepsWaiting() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
            val child = PendingEngine()
            val process = SidecarProcess("aether", child, listener.localPort, AetherSidecar.readyLine(listener.localPort), logOutput = false)
            try {
                child.line("MAXIMUS_AETHER_STAGE:account")
                child.line("registration retry failed: tls handshake failed")
                child.line("MAXIMUS_AETHER_STAGE:identity")
                val watch = com.example.vpn.connections.IrancellWarp.RegistrationWatch(graceMs = 300L)
                val started = System.nanoTime()
                assertFalse(process.awaitReadyUnless(700L) { watch.shouldStop(process.startupFailure, System.currentTimeMillis()) })
                assertTrue("waited the whole window", (System.nanoTime() - started) / 1_000_000 >= 650L)
                assertFalse(process.startupFailure.orEmpty().startsWith("WARP API registration failed"))
            } finally { process.stop() }
        }
    }

    @Test fun servingEngineIsReadyEvenWithAGiveUpCheck() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
            val child = PendingEngine()
            val process = SidecarProcess("aether", child, listener.localPort, AetherSidecar.readyLine(listener.localPort), logOutput = false)
            try {
                child.serving(listener.localPort)
                assertTrue(process.awaitReadyUnless(2_000L) { false })
            } finally { process.stop() }
        }
    }
}
