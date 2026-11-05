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
        fun serving() { writer.write("MAXIMUS_AETHER_SOCKS_READY\n".toByteArray()); writer.flush() }
    }

    @Test fun boundPortDoesNotPassUntilTunnelStartsServing() {
        ServerSocket(0, 8, InetAddress.getByName("127.0.0.1")).use { listener ->
            val child = PendingEngine()
            val process = SidecarProcess("aether", child, listener.localPort, AetherSidecar.READY_LINE, logOutput = false)
            try {
                assertFalse("A bound socket is not a ready WARP tunnel", process.awaitReady(150))
                child.serving()
                assertTrue("Serving marker and listener must both be ready", process.awaitReady(2000))
            } finally { process.stop() }
        }
    }

    @Test fun servingMarkerCannotPassWithNoListener() {
        val port = ServerSocket(0).use { it.localPort }
        val child = PendingEngine()
        val process = SidecarProcess("aether", child, port, AetherSidecar.READY_LINE, logOutput = false)
        try {
            child.serving()
            assertFalse(process.awaitReady(200))
        } finally { process.stop() }
    }
}
