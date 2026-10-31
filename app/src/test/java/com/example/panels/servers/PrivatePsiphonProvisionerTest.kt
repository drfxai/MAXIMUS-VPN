package com.example.panels.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import com.example.PrivatePsiphonFixtures
import java.security.MessageDigest

class PrivatePsiphonProvisionerTest {
    private val server = ManagedServer(name = "test", host = "203.0.113.7",
        username = "root", location = ServerLocation.ABROAD,
        hostKeySha256 = "SHA256:example", password = "test-only")
    private val binary = ByteArray(1_000_000) { (it % 251).toByte() }
    private val digest = MessageDigest.getInstance("SHA-256").digest(binary)
        .joinToString("") { "%02x".format(it.toInt() and 255) }
    private val progress = object : ActionProgress {
        override fun step(index: Int) = Unit
        override fun log(line: String) = Unit
    }
    private class SimulatedShell(private val occupied: Boolean = false) : RemoteShell {
        var uploaded = false
        var installStarted = false
        override fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): ShellResult {
            if (script == ServerFacts.SCRIPT) return ShellResult(0,
                "---MX-FACTS---\nUID=0\nSYSTEMD=1\nARCH=x86_64\nSOCKETS=1\n" +
                    if (occupied) "TCP=8888\n" else "TCP=22\n")
            if (script.contains("MAXIMUS_INSTALLER_LOCK_V1")) {
                installStarted = true
                onLine("##RESULT PSIPHON_UNIT_ACTIVE=1")
                return ShellResult(0, "##RESULT PSIPHON_UNIT_ACTIVE=1\n")
            }
            return ShellResult(0, "")
        }
        override fun upload(data: ByteArray, remotePath: String) {
            assertTrue(remotePath.startsWith("/tmp/maximus-psiphond-"))
            uploaded = true
        }
        override fun download(remotePath: String, maxBytes: Int) =
            PrivatePsiphonFixtures.entry().toByteArray()
        override fun close() = Unit
    }

    @Test fun verifiedBinaryCanGenerateBootstrapWithoutExposingItToLogs() {
        val remote = SimulatedShell()
        val result = PrivatePsiphonProvisioner { remote }.installWithPinnedBinary(
            server, "203.0.113.7", 8888, binary, digest, progress)
        assertTrue(remote.uploaded)
        assertTrue(remote.installStarted)
        assertEquals(8888, result.port)
        assertEquals(PrivatePsiphonFixtures.entry(), result.serverEntry)
        assertEquals(PrivatePsiphonServerScripts.TOOL_ID, result.server.tools.last().id)
    }

    @Test fun alteredOrUnpinnedBinaryNeverOpensSsh() {
        var connected = false
        val worker = PrivatePsiphonProvisioner { connected = true; SimulatedShell() }
        assertThrows(IllegalArgumentException::class.java) {
            worker.installWithPinnedBinary(server, "203.0.113.7", 8888, binary,
                "0".repeat(64), progress)
        }
        assertFalse(connected)
    }

    @Test fun portCollisionBlocksBeforeUpload() {
        val remote = SimulatedShell(occupied = true)
        assertThrows(IllegalStateException::class.java) {
            PrivatePsiphonProvisioner { remote }.installWithPinnedBinary(
                server, "203.0.113.7", 8888, binary, digest, progress)
        }
        assertFalse(remote.uploaded)
    }

    @Test fun injectionInPublicIpOrPortIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            PrivatePsiphonServerScripts.Request("203.0.113.7; reboot", 9000,
                "/tmp/maximus-psiphond-${"a".repeat(32)}.bin", digest)
        }
        assertThrows(IllegalArgumentException::class.java) {
            PrivatePsiphonServerScripts.Request("203.0.113.7", 53,
                "/tmp/maximus-psiphond-${"a".repeat(32)}.bin", digest)
        }
    }
}
