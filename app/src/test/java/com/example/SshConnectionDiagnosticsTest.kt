package com.example

import com.example.panels.PanelProvisioner
import com.example.panels.XuiInstallRequest
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import kotlin.concurrent.thread

class SshConnectionDiagnosticsTest {

    private val fingerprint = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    private fun provisioner() = PanelProvisioner(
        retryDelayMs = 0L,
        sshAttemptTimeoutsMs = listOf(700, 700, 700)
    )

    private fun install(port: Int, logs: MutableList<String> = mutableListOf()) =
        provisioner().installXui(XuiInstallRequest("127.0.0.1", port, "root", "pw", fingerprint), logs::add)

    @Test
    fun aClosedPortIsReportedAsUnreachableNotAsAnSshTimeout() {
        val port = ServerSocket(0).use { it.localPort } // free port, nothing listening
        try {
            install(port)
            fail("must fail")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains("not reachable"))
        }
    }

    @Test
    fun aServerThatAcceptsButNeverAnswersIsRetriedAndExplained() {
        val server = ServerSocket(0)
        val accepted = java.util.concurrent.atomic.AtomicInteger()
        val held = mutableListOf<java.net.Socket>()
        val acceptor = thread(isDaemon = true) {
            try {
                while (true) { held += server.accept(); accepted.incrementAndGet() }
            } catch (_: Exception) {}
        }
        val logs = mutableListOf<String>()
        try {
            install(server.localPort, logs)
            fail("must fail")
        } catch (e: IllegalStateException) {
            val msg = e.message.orEmpty()
            assertTrue(msg, msg.contains("did not complete"))
            assertTrue(msg, msg.contains("password login"))
            // 1 preflight connection + 3 SSH attempts
            assertTrue("attempts=${accepted.get()}", accepted.get() >= 4)
            assertTrue(logs.any { it.contains("retrying") })
        } finally {
            server.close()
            held.forEach { runCatching { it.close() } }
            acceptor.join(1000)
        }
    }
}
