package com.example

import com.example.vpn.engine.UniversalImportEngine
import com.example.vpn.subscription.SubscriptionFetcher
import com.example.vpn.subscription.SubscriptionSnapshots
import com.example.vpn.subscription.SubscriptionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.Collections

/**
 * War plan Phase 3: a subscription survives a blocked domain, a block page, an emptied file and a
 * total outage of its sources.
 */
class SubscriptionResilienceTest {
    @get:Rule val tmp = TemporaryFolder()

    private val payload = "vless://11111111-1111-1111-1111-111111111111@203.0.113.7:443?security=tls&sni=a.example.org&type=ws&path=%2F#one\n" +
        "trojan://secret@203.0.113.8:443?security=tls&sni=b.example.org#two"
    private val blockPage = "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html\"></head><body><iframe src=\"http://10.10.34.34?type=Invalid Site\"></iframe></body></html>"
    private fun count(body: String) = UniversalImportEngine.importText(body).validProfiles.size

    private val raw = "https://raw.githubusercontent.com/someone/free-subs/main/sub/mix.txt"

    @Test
    fun `GitHub files get CDN mirrors on other networks`() {
        val mirrors = SubscriptionSources.derivedMirrors(raw)
        assertTrue(mirrors.contains("https://cdn.jsdelivr.net/gh/someone/free-subs@main/sub/mix.txt"))
        assertTrue(mirrors.contains("https://fastly.jsdelivr.net/gh/someone/free-subs@main/sub/mix.txt"))
        assertTrue(mirrors.contains("https://cdn.statically.io/gh/someone/free-subs/main/sub/mix.txt"))
        assertTrue(mirrors.none { it == raw })
        // The same file named in other ways maps to the same mirrors.
        assertTrue(SubscriptionSources.derivedMirrors("https://github.com/someone/free-subs/blob/main/sub/mix.txt").contains(raw))
        assertTrue(SubscriptionSources.derivedMirrors("https://raw.githubusercontent.com/someone/free-subs/refs/heads/main/sub/mix.txt").contains(raw))
        assertTrue(SubscriptionSources.derivedMirrors("https://gcore.jsdelivr.net/gh/someone/free-subs@main/sub/mix.txt").contains(raw))
        // Nothing is invented for other hosts, or for addresses with a query (tokens).
        assertEquals(emptyList<String>(), SubscriptionSources.derivedMirrors("https://panel.example.org/sub/abc"))
        assertEquals(emptyList<String>(), SubscriptionSources.derivedMirrors("$raw?token=1"))
    }

    @Test
    fun `pasted mirrors are kept in order after the address`() {
        val (primary, mirrors) = SubscriptionSources.parseInput("https://a.example.org/sub\nhttps://b.example.net/sub , https://a.example.org/sub")
        assertEquals("https://a.example.org/sub", primary)
        assertEquals(listOf("https://b.example.net/sub"), mirrors)
        assertEquals(listOf("https://a.example.org/sub", "https://b.example.net/sub"), SubscriptionSources.candidates(primary, mirrors))
    }

    @Test
    fun `a working address answers with one request`() {
        val asked = Collections.synchronizedList(mutableListOf<String>())
        val outcome = SubscriptionFetcher({ asked += it; payload }, ::count, staggerMs = 200).fetch(SubscriptionSources.candidates(raw))
        outcome as SubscriptionFetcher.Outcome.Fetched
        assertEquals(raw, outcome.url)
        assertEquals(2, outcome.configs)
        assertEquals(listOf(raw), asked.toList())
    }

    @Test
    fun `a blocked domain, a block page and a silent address each move on to a mirror`() {
        val silent = Any()
        val outcome = SubscriptionFetcher(
            download = { url ->
                when {
                    url.startsWith("https://raw.githubusercontent.com") -> throw IOException("Connection reset")
                    url.startsWith("https://cdn.jsdelivr.net") -> blockPage
                    url.startsWith("https://fastly.jsdelivr.net") -> synchronized(silent) { Thread.sleep(5_000); payload }
                    else -> payload
                }
            },
            count = ::count,
            staggerMs = 300
        ).fetch(SubscriptionSources.candidates(raw))
        outcome as SubscriptionFetcher.Outcome.Fetched
        assertEquals("https://gcore.jsdelivr.net/gh/someone/free-subs@main/sub/mix.txt", outcome.url)
        assertTrue(outcome.failures.any { it.second.contains("blocked?") })
        assertTrue(outcome.failures.any { it.second.contains("reset") })
    }

    @Test
    fun `an emptied file is not taken as an empty list`() {
        val outcome = SubscriptionFetcher({ "" }, ::count, staggerMs = 50).fetch(listOf("https://a.example.org/sub"))
        outcome as SubscriptionFetcher.Outcome.AllFailed
        assertEquals("empty file", outcome.failures.single().second)
    }

    @Test
    fun `the offline copy survives and is sealed`() {
        val snapshots = SubscriptionSnapshots(tmp.root, encrypt = { it.reversed() }, decrypt = { it.reversed() })
        assertNull(snapshots.load("sub-1"))
        snapshots.save("sub-1", raw, payload, now = 1_000L)
        val copy = snapshots.load("sub-1")!!
        assertEquals(1_000L, copy.savedAt)
        assertEquals(raw, copy.sourceUrl)
        assertEquals(payload, copy.payload)
        assertTrue(tmp.root.listFiles()!!.none { it.readText().contains("vless://") })
        snapshots.delete("sub-1")
        assertNull(snapshots.load("sub-1"))
    }
}
