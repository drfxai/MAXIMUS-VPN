package com.example

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The engine ABI set and the sing-box pin table are read by shell scripts, by Gradle and by hand,
 * so drift between them is invisible to the compiler and only shows up as an APK that silently
 * ships without an engine. These checks fail the build instead.
 */
class EngineAbiPinTest {
    private val repo: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile && File(it, "scripts/engine-abis.txt").isFile }
        ?: error("repository root not found above ${System.getProperty("user.dir")}")

    private fun read(path: String) = File(repo, path).readText()

    /** ABIs from the one list the Gradle guard and build-engines.sh both read. */
    private fun abiList(): List<String> = read("scripts/engine-abis.txt").lines()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }

    private val archRows = Regex("""^[ \t]+([A-Za-z0-9_-]+)\)[ \t]+arch=(arm64|arm|amd64)[ \t]*;;""", RegexOption.MULTILINE)
    private val shaRows = Regex("""^[ \t]+(\d+\.\d+\.\d+):([A-Za-z0-9_-]+)\)[ \t]+sha=([0-9a-f]{64})[ \t]*;;""", RegexOption.MULTILINE)
    private val defaultVersion = Regex("version=\"\\$\\{SINGBOX_VERSION:-(\\d+\\.\\d+\\.\\d+)\\}")

    private fun singboxAbis(script: String): List<String> = archRows.findAll(script).map { it.groupValues[1] }.toList()

    /** version -> abi -> sha256 from the script's pin table. */
    private fun singboxPins(script: String): Map<String, Map<String, String>> =
        shaRows.findAll(script).groupBy({ it.groupValues[1] }, { it.groupValues[2] to it.groupValues[3] })
            .mapValues { (_, rows) -> rows.toMap() }

    @Test fun engineAbiListMatchesTheSingboxPinTable() {
        val listed = abiList()
        assertEquals("duplicate ABI in scripts/engine-abis.txt: $listed", listed.distinct(), listed)
        val pinned = singboxAbis(read("scripts/engines/singbox.sh"))
        assertEquals(
            "scripts/engines/singbox.sh builds a different ABI set than scripts/engine-abis.txt " +
                "(Gradle's -PtargetAbi check reads that file)",
            listed.sorted(), pinned.sorted()
        )
    }

    @Test fun everyPinnedSingboxVersionCoversEveryEngineAbi() {
        val abis = abiList()
        val pins = singboxPins(read("scripts/engines/singbox.sh"))
        assertTrue("scripts/engines/singbox.sh has no pin table", pins.isNotEmpty())
        for ((version, byAbi) in pins) {
            assertEquals("sing-box $version is missing an ABI (pinned: ${byAbi.keys.sorted()})", abis.sorted(), byAbi.keys.sorted())
            assertEquals("sing-box $version reuses a hash across ABIs", abis.size, byAbi.values.toSet().size)
        }
    }

    @Test fun pinsDocumentMatchesTheScript() {
        val script = read("scripts/engines/singbox.sh")
        val pins = singboxPins(script)
        val current = defaultVersion.find(script)?.groupValues?.get(1)
            ?: error("scripts/engines/singbox.sh has no version=\"\${SINGBOX_VERSION:-x.y.z}\" default")
        assertTrue("the default sing-box pin $current is not in the pin table", current in pins)
        assertEquals("scripts/engines/singbox.sh must keep exactly two releases pinned (current + previous); found ${pins.keys}",
            2, pins.size)

        val documented = mutableMapOf<String, MutableMap<String, String>>()
        val roles = mutableMapOf<String, String>()
        for (line in read("docs/PINS.md").lines().filter { it.trimStart().startsWith('|') }) {
            val cells = line.split('|').map { it.trim().trim('`') }
            if (cells.size < 6 || cells[1] !in pins) continue
            roles[cells[1]] = cells[2]
            documented.getOrPut(cells[1]) { mutableMapOf() }[cells[3]] = cells[5]
        }
        assertEquals("docs/PINS.md lists a different set of sing-box releases than the script", pins.keys.sorted(), documented.keys.sorted())
        for ((version, byAbi) in pins) {
            assertEquals("docs/PINS.md hashes for sing-box $version differ from scripts/engines/singbox.sh",
                byAbi, documented[version])
        }
        assertEquals("docs/PINS.md must mark $current as the current pin", "current", roles[current])
        assertEquals("docs/PINS.md must mark the other release as previous (rollback target)",
            listOf("previous"), roles.filterKeys { it != current }.values.toList())
        assertTrue("docs/PINS.md must keep the rollback command that the script supports",
            read("docs/PINS.md").contains("SINGBOX_VERSION="))
    }

    @Test fun buildEnginesTakesItsDefaultAbisFromTheList() {
        val script = read("scripts/build-engines.sh")
        assertTrue("scripts/build-engines.sh no longer reads scripts/engine-abis.txt",
            script.contains("engine-abis.txt"))
        assertFalse("scripts/build-engines.sh hardcodes a second ABI default list",
            Regex("""^[ \t]*ABIS=\([^)]*arm64-v8a""", RegexOption.MULTILINE).containsMatchIn(script))
    }

    @Test fun gradleRejectsAbisWithoutAnEngineBuild() {
        val gradle = File(repo, "app/build.gradle.kts").readText()
        assertTrue("app/build.gradle.kts must validate -PtargetAbi against scripts/engine-abis.txt",
            gradle.contains("scripts/engine-abis.txt"))
        assertFalse("app/build.gradle.kts hardcodes a second -PtargetAbi set that can drift from the engines",
            Regex("setOf\\(\\s*\"arm64-v8a").containsMatchIn(gradle))
    }
}
