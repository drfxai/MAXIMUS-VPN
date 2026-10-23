package com.example.panels.servers.tunnel

import org.junit.Assert.*
import org.junit.Test

class SpoofCarrierTest {
    private val s = SpoofCarrierSpec(listOf("1.1.1.1"), listOf("8.8.8.8"))
    @Test fun settingsRoundTripAndLegacySettings() {
        assertEquals(s, SpoofCarrierSpec.fromSettings(s.settings()))
        assertNull(SpoofCarrierSpec.fromSettings(emptyMap()))
        assertThrows(IllegalArgumentException::class.java) { SpoofCarrierSpec.fromSettings(s.settings() + ("spoofUpload" to "invalid")) }
    }
    @Test fun inputIsBoundedAndCannotBecomeShellCode() {
        for (ip in listOf("127.0.0.1", "10.0.0.1", "1.1.1.1;id", "example.com", "::1", "0.0.0.0")) {
            assertThrows(IllegalArgumentException::class.java) { s.copy(iranSources = listOf(ip)).validate() }
        }
        assertThrows(IllegalArgumentException::class.java) { s.copy(iranSources = List(17) { "1.1.1.${it+1}" }).validate() }
        assertThrows(IllegalArgumentException::class.java) { s.copy(port = s.localPort).validate() }
        assertThrows(IllegalArgumentException::class.java) { s.copy(upload = "tcp;reboot").validate() }
    }
    @Test fun eachDirectionUsesItsSelectedCarrierAndOnlyLoopbackApplicationPorts() {
        val spec = s.copy(upload = "icmp", download = "udp")
        val local = SpoofCarrierScripts.install(spec, true, "9.9.9.9", 45000).text
        val remote = SpoofCarrierScripts.install(spec, false, "4.2.2.2", 45000).text
        assertTrue(local.contains("--send-transport icmp --recv-transport udp"))
        assertTrue(remote.contains("--send-transport udp --recv-transport icmp"))
        assertTrue(local.contains("--listen 127.0.0.1:18500"))
        assertTrue(remote.contains("--forward 127.0.0.1:45000"))
        assertTrue(local.contains("CapabilityBoundingSet=CAP_NET_RAW"))
        assertTrue(local.contains("[Unit]"))
        assertFalse(local.contains("+            [Unit]"))
    }
    @Test fun receiverRejectsForeignInsufficientAndMalformedEvidence() {
        val lines = "1.1.1.1 4/4 0.0%\n8.8.8.8 4/4 0.0%\n1.1.1.2 2/4 50.0%\n1.1.1.3 3/4 25.0%\n1.1.1.4 9/4 0.0%"
        assertEquals(listOf("1.1.1.1", "1.1.1.3"), SpoofCarrierScripts.received(lines, listOf("1.1.1.1", "1.1.1.2", "1.1.1.3", "1.1.1.4")))
    }
    @Test fun failedCleanupDoesNotSkipOtherServer() {
        val failure = IllegalStateException("connection failed")
        val visited = mutableListOf<Int>()
        rollbackAll(failure, listOf(
            { visited += 1; error("carrier unavailable") },
            { visited += 2; error("Iran unavailable") },
            { visited += 3 }
        ))
        assertEquals(listOf(1, 2, 3), visited)
        assertEquals(2, failure.suppressed.size)
        assertEquals("connection failed", failure.message)
    }

    @Test fun everyCarrierUsesNonceVerifiedDataProbe() {
        for (protocol in SpoofCarrierSpec.TRANSPORTS) {
            val script = SpoofCarrierScripts.probe(s.iranSources, "9.9.9.9", 18443, true, protocol, "b".repeat(64)).text
            assertTrue(script.contains("18443 $protocol"))
            assertTrue(script.contains("payload == (nonce"))
            assertTrue(script.contains("seen.add(i)"))
            assertTrue(script.contains("process.terminate()"))
        }
    }

    @Test fun generatedShellPassesSyntaxAndUsesPinnedDownloads() {
        val scripts = listOf(SpoofCarrierScripts.prepare("amd64"), SpoofCarrierScripts.prepare("arm64"),
            SpoofCarrierScripts.probe(s.iranSources,"9.9.9.9",18443,true,"tcp", "a".repeat(64)),
            SpoofCarrierScripts.install(s,true,"9.9.9.9",45000), SpoofCarrierScripts.install(s,false,"9.9.9.9",45000),
            SpoofCarrierScripts.finish(true), SpoofCarrierScripts.finish(false), SpoofCarrierScripts.remove())
        for (script in scripts) {
            assertFalse(script.text.contains("§"))
            assertFalse(script.text.contains("@@"))
            val process = ProcessBuilder("sh", "-n").start()
            process.outputStream.use { it.write(script.text.toByteArray()) }
            val error = process.errorStream.bufferedReader().readText()
            assertEquals(error,0,process.waitFor())
        }
        assertTrue(scripts[0].text.contains(SpoofCarrierScripts.hashes.getValue("amd64")))
        assertFalse(scripts[0].text.contains("/releases/latest/"))
    }
}
