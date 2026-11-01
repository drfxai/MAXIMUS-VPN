package com.example.panels.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** A failed/unframed SSH command cannot refresh stored server facts. */
class ServerInspectionIntegrityTest {
    private val server = ManagedServer(
        name = "Fixture", host = "192.0.2.15", location = ServerLocation.ABROAD,
        checkedAt = 123L, facts = ServerFacts(root = true)
    )

    private fun manager(exitCode: Int, output: String): ServerManager =
        ServerManager(connect = {
            object : RemoteShell {
                override fun run(script: String, timeoutMs: Long, onLine: (String) -> Unit): ShellResult {
                    assertEquals(ServerFacts.SCRIPT, script)
                    return ShellResult(exitCode, output)
                }
                override fun close() {}
            }
        })

    @Test fun rejectsNonzeroExitEvenWhenFieldsAreParsable() {
        val error = assertThrows(IllegalStateException::class.java) {
            manager(1, "---MX-FACTS---\nUID=0\nSOCKETS=1\nARCH=x86_64\n").check(server)
        }
        assertTrue(error.message.orEmpty().contains("did not complete"))
    }

    @Test fun rejectsMissingFramingMarker() {
        assertThrows(IllegalStateException::class.java) {
            manager(0, "UID=0\nSOCKETS=1\nARCH=x86_64\n").check(server)
        }
    }

    @Test fun initialInstallInventoryFailureIsClassifiedAsNoMutation() {
        val noProgress = object : ActionProgress {
            override fun step(index: Int) {}
            override fun log(line: String) {}
        }
        assertThrows(InstallPreflightException::class.java) {
            manager(1, "---MX-FACTS---\\nUID=0\\n")
                .install(server, ServerToolCatalog.DNSTT,
                    InstallOptions(baseDomain = "example.com", tunnelLabel = "t"), noProgress)
        }
    }

    @Test fun acceptsSuccessfulFramedInspection() {
        val checked = manager(0,
            "---MX-FACTS---\nUID=0\nSYSTEMD=1\nSOCKETS=1\nARCH=x86_64\n")
            .check(server)
        assertTrue(checked.checkedAt > server.checkedAt)
        assertEquals(true, checked.facts?.root)
        assertEquals(true, checked.facts?.socketInspectionAvailable)
    }
}
