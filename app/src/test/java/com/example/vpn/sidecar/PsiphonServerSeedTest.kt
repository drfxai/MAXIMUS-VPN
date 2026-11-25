package com.example.vpn.sidecar

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Build 175 report: Shir o Khorshid listed no egress region and dialed nothing for 120 s (its own data store was empty
 * and the server list could not be fetched on Irancell), while Psiphon over Lantern had a tunnel in ~2 s. It now
 * starts from the server entries Psiphon's data store already holds.
 */
class PsiphonServerSeedTest {
    private lateinit var engines: File

    @Before fun setUp() { engines = Files.createTempDirectory("engines").toFile() }
    @After fun tearDown() { engines.deleteRecursively() }

    private fun entry(i: Int) = """{"capabilities":["OSSH"],"ipAddress":"192.0.2.$i","region":"DE"}"""

    private fun store(root: File, entries: Int, padding: Int = 0): File {
        val db = PsiphonServerSeed.datastore(root)
        db.parentFile.mkdirs()
        db.writeBytes(ByteArray(padding) + (0 until entries).joinToString("") { entry(it) }.toByteArray())
        return db
    }

    @Test fun countsEntriesAcrossReadBoundaries() {
        val root = File(engines, "x")
        // The first entry straddles the 64 KB read boundary.
        val db = store(root, 3, padding = 64 * 1024 - 20)
        assertEquals(3, PsiphonServerSeed.countEntries(db))
        assertEquals(2, PsiphonServerSeed.countEntries(db, limit = 2))
        assertEquals(0, PsiphonServerSeed.countEntries(File(root, "missing")))
    }

    @Test fun anEmptyShirOKhorshidStoreStartsFromPsiphonsServers() {
        val sok = File(engines, "shirokhorshid")
        val sokData = File(sok, PsiphonSidecar.DATA_DIR)
        store(sokData, 0, padding = 4096)                                   // opened before, no server entry
        store(File(engines, "psiphon/${PsiphonSidecar.DATA_DIR}"), 2)
        val chain = store(File(engines, "chain/psiphon/${PsiphonSidecar.DATA_DIR}"), 5) // fetched through Lantern
        val result = PsiphonServerSeed.seed(sokData, PsiphonServerSeed.psiphonRoots(sok))
        assertTrue("$result", result is PsiphonServerSeed.Result.Seeded)
        assertEquals(chain.absolutePath, (result as PsiphonServerSeed.Result.Seeded).from.absolutePath)
        assertEquals(5, PsiphonServerSeed.countEntries(PsiphonServerSeed.datastore(sokData)))
        assertTrue(File(PsiphonServerSeed.datastore(sokData).parentFile, "psiphon.boltdb.seed").exists().not())
    }

    @Test fun aStoreThatHasServersIsNeverReplaced() {
        val sok = File(engines, "shirokhorshid")
        val sokData = File(sok, PsiphonSidecar.DATA_DIR)
        val own = store(sokData, 1)
        val before = own.readBytes()
        store(File(engines, "psiphon/${PsiphonSidecar.DATA_DIR}"), 9)
        assertEquals(PsiphonServerSeed.Result.HasServers, PsiphonServerSeed.seed(sokData, PsiphonServerSeed.psiphonRoots(sok)))
        assertTrue(before.contentEquals(own.readBytes()))
    }

    @Test fun nothingToCopyLeavesTheStoreAlone() {
        val sok = File(engines, "shirokhorshid")
        assertEquals(PsiphonServerSeed.Result.NoSource,
            PsiphonServerSeed.seed(File(sok, PsiphonSidecar.DATA_DIR), PsiphonServerSeed.psiphonRoots(sok)))
    }

    @Test fun aChainHopLooksInTheSameEnginesFolder() {
        val roots = PsiphonServerSeed.psiphonRoots(File(engines, "chain/shirokhorshid")).map { it.absolutePath }
        assertEquals(listOf(File(engines, "psiphon/data").absolutePath, File(engines, "chain/psiphon/data").absolutePath), roots)
    }
}
