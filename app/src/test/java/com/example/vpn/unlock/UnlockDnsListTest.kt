package com.example.vpn.unlock

import com.example.update.ResolverPackUpdater
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Unlock server list: bundled asset, resolver-pack copy, fallback and custom entries. */
class UnlockDnsListTest {
    private val bundledText = "# Unlock servers\r\n143.20.64.55\r\n193.233.112.67\r\n45.13.237.213\r\n5.175.161.3\r\n"

    @After fun reset() {
        UnlockDnsList.updated = null
        UnlockDnsList.bundled = null
    }

    @Test fun theBundledAssetMatchesTheFallbackAndParsesWithCrlf() {
        val asset = File("src/main/assets/${UnlockDnsList.ASSET}").takeIf { it.isFile }
            ?: File("app/src/main/assets/${UnlockDnsList.ASSET}")
        val parsed = UnlockDnsList.parse(asset.readText())
        assertEquals(UnlockDnsList.FALLBACK, parsed)
        assertEquals(UnlockDnsList.FALLBACK, UnlockDnsList.parse(bundledText))
    }

    @Test fun parsingDropsCommentsRestrictedAndDuplicateEntries() {
        val text = "1.2.3.4 # relay\n\n10.10.34.35\n127.0.0.1\n1.2.3.4\nnot-an-ip\n300.1.1.1\n192.0.2.1\n5.6.7.8\n"
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), UnlockDnsList.parse(text))
    }

    @Test fun aPackListMustBeEntirelyValid() {
        assertEquals(listOf("1.2.3.4", "5.6.7.8"), UnlockDnsList.validatePack("# c\n1.2.3.4\n5.6.7.8\n1.2.3.4\n"))
        assertNull(UnlockDnsList.validatePack("1.2.3.4\n10.10.34.35\n"))
        assertNull(UnlockDnsList.validatePack("# nothing\n\n"))
        assertNull(UnlockDnsList.validatePack((1..UnlockDnsList.MAX_SERVERS + 1).joinToString("\n") { "1.2.3.$it" }))
        assertNull(UnlockDnsList.validatePack("1.2.3.4\n" + "#".repeat(UnlockDnsList.MAX_BYTES)))
    }

    @Test fun thePackCopyWinsThenTheBundledListThenTheFallback() {
        UnlockDnsList.bundled = { bundledText.byteInputStream() }
        UnlockDnsList.updated = { "8.8.4.4\n".byteInputStream() }
        assertEquals(UnlockDnsList.Loaded(listOf("8.8.4.4"), fromPack = true), UnlockDnsList.load())

        // An older pack without unlock_dns.txt (or an unreadable one) leaves the bundled list in use.
        UnlockDnsList.updated = { null }
        assertEquals(UnlockDnsList.Loaded(UnlockDnsList.FALLBACK, fromPack = false), UnlockDnsList.load())
        UnlockDnsList.updated = { throw java.io.IOException("gone") }
        assertFalse(UnlockDnsList.load().fromPack)
        UnlockDnsList.updated = { "10.0.0.1\n".byteInputStream() }
        assertFalse(UnlockDnsList.load().fromPack)

        UnlockDnsList.bundled = null
        UnlockDnsList.updated = null
        assertEquals(UnlockDnsList.FALLBACK, UnlockDnsList.load().servers)
    }

    @Test fun customServersAreValidatedLikeBundledResolvers() {
        val listed = UnlockDnsList.FALLBACK
        assertEquals(UnlockDnsList.CustomResult.Added("9.9.9.9"), UnlockDnsList.validateCustom(" 9.9.9.9 ", listed, emptyList()))
        assertEquals(UnlockDnsList.CustomResult.Added("9.9.9.9"), UnlockDnsList.validateCustom("9.9.9.9:53", listed, emptyList()))
        assertEquals(UnlockDnsList.CustomResult.Added("9.9.9.9"), UnlockDnsList.validateCustom("009.9.9.9", listed, emptyList()))
        for (bad in listOf("", "dns.google", "1.2.3", "256.1.1.1", "10.0.0.1", "192.168.1.1", "127.0.0.1", "0.1.2.3",
                "100.64.0.1", "224.0.0.1", "192.0.2.5", "::1", "1.2.3.4/24")) {
            assertEquals(bad, UnlockDnsList.CustomResult.Invalid, UnlockDnsList.validateCustom(bad, listed, emptyList()))
        }
        assertEquals(UnlockDnsList.CustomResult.Duplicate, UnlockDnsList.validateCustom("143.20.64.55", listed, emptyList()))
        assertEquals(UnlockDnsList.CustomResult.Duplicate, UnlockDnsList.validateCustom("9.9.9.9", listed, listOf("9.9.9.9")))
        val full = (1..UnlockDnsList.MAX_CUSTOM).map { "9.9.9.$it" }
        assertEquals(UnlockDnsList.CustomResult.Full, UnlockDnsList.validateCustom("8.8.8.8", listed, full))
    }

    @Test fun resolverPacksMayCarryTheUnlockListButNeverRequireIt() {
        val six = (1..6).map { "part_$it.txt" }.toSet()
        assertTrue(ResolverPackUpdater.complete(six))
        assertTrue(ResolverPackUpdater.complete(six + UnlockDnsList.PACK_ENTRY))
        assertFalse(ResolverPackUpdater.complete(six - "part_3.txt" + UnlockDnsList.PACK_ENTRY))
        assertFalse(ResolverPackUpdater.complete(six + "evil.sh"))
        assertTrue(ResolverPackUpdater.allowedEntry(UnlockDnsList.PACK_ENTRY))
        assertFalse(ResolverPackUpdater.allowedEntry("unlock/dns.txt"))
        assertFalse(ResolverPackUpdater.allowedEntry("../part_1.txt"))

        assertEquals("1.2.3.4\n5.6.7.8\n", ResolverPackUpdater.validatedEntry(UnlockDnsList.PACK_ENTRY, "# c\r\n1.2.3.4\r\n5.6.7.8\r\n"))
        val refused = runCatching { ResolverPackUpdater.validatedEntry(UnlockDnsList.PACK_ENTRY, "1.2.3.4\n10.10.34.35\n") }
        assertTrue(refused.isFailure)
        // The resolver parts keep their own rule (50..10000 public IPv4 lines).
        assertTrue(runCatching { ResolverPackUpdater.validatedEntry("part_1.txt", "1.2.3.4\n") }.isFailure)
    }
}
