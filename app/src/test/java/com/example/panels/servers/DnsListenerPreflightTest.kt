package com.example.panels.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsListenerPreflightTest {
    private fun check(address: String, protocol: String = "udp", failure: Boolean = false): Pair<Int, String> {
        val mock = if (failure) "ss() { return 1; }" else
            "ss() { printf '%s\\n' 'UNCONN 0 0 $address 0.0.0.0:*'; }"
        val script = mock + "\n" + DnsServerScripts.LISTENER_PREFLIGHT.replace('§', '$') +
            "\nrequire_free_listener $protocol 53\nprintf passed"
        val process = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        return process.waitFor() to output
    }

    @Test fun wildcardBindRejectsLoopbackAndPublicListeners() {
        for (address in listOf("127.0.0.53:53", "[::1]:53", "0.0.0.0:53", "*:53", "[::]:53", "192.0.2.1:53")) {
            for (protocol in listOf("udp", "tcp")) {
                val (code, output) = check(address, protocol)
                assertEquals(output, 23, code)
                assertTrue(output.contains(address))
            }
        }
    }

    @Test fun unrelatedPortsAreAllowed() {
        assertEquals(0, check("127.0.0.53:5353").first)
    }

    @Test fun failedInspectionDoesNotMeanFreePort() {
        assertEquals(23, check("", failure = true).first)
    }

    @Test fun bothInstallersCheckBeforeDownloadsAndBeforeMutation() {
        for (script in listOf(DnsServerScripts.soar("t.example.com").text,
                DnsServerScripts.cotten("t.example.com", "amd64").text)) {
            assertTrue(script.indexOf("require_free_listener udp 53") < script.indexOf("ensure_user"))
            assertTrue(script.substringBefore("mutated=1").trimEnd().endsWith("require_free_listener udp 53") ||
                script.substringBefore("mutated=1").trimEnd().endsWith("require_free_listener tcp 9092"))
        }
    }
}
