package com.example.vpn.lab

import com.example.data.model.VlessProfile
import com.example.vpn.connectivity.RecoveryProfiles
import com.example.vpn.lab.research.GitHubReleaseSource
import com.example.vpn.lab.research.ResearchCandidate
import com.example.vpn.lab.research.ResearchPipeline
import com.example.vpn.lab.research.ResearchStore
import com.example.vpn.lab.research.SourceRelease
import com.example.vpn.smart.NetworkCapabilityProfile
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabResearchTest {
    private val now = RecoveryProfiles.REVIEWED_AT + 1_000
    private val parent = VlessProfile(
        id = "p1", name = "My secret server", address = "worker.example.workers.dev", port = 443,
        uuid = "11111111-1111-1111-1111-111111111111", transport = "ws", security = "tls",
        sni = "worker.example.workers.dev", host = "worker.example.workers.dev", path = "/vl", fingerprint = "chrome", canonicalFingerprint = "fp"
    )

    @Test fun theBriefHoldsMeasurementsButNoIdentifiers() {
        val ctx = NetworkContext("NS", "cell:43235", "IPv4", now, "Irancell")
        val built = CandidateGenerator(clock = { now }).generate("EXP-001", parent, LabFailureCategory.TLS_HANDSHAKE_FAILED, null, endpoints = listOf("104.16.1.1"))
        val e = LabExperiment("EXP-001", "NS", ctx.contextKey, "Irancell", "p1", "fp", LabFailureCategory.TLS_HANDSHAKE_FAILED, ExperimentState.FAILED, now,
            candidates = built.map { it.candidate.copy(stats = CandidateStats().record(false, null, LabFailureCategory.TIMEOUT, now)) })
        val brief = LabBrief.of(LabSnapshot(network = ctx, capability = NetworkCapabilityProfile("cellular", true, false, measuredAt = now), experiments = listOf(e)), now)
        assertTrue(brief.contains("mobile data"))
        assertTrue(brief.contains("TLS_HANDSHAKE_FAILED"))
        for (secret in listOf("43235", "Irancell", "104.16.1.1", "worker.example", parent.uuid, "My secret server")) assertFalse(secret, brief.contains(secret))
    }

    @Test fun aSuggestionIsRebuiltOnlyThroughThePolicy() {
        val g = CandidateGenerator(clock = { now })
        assertEquals("firefox", g.rebuild(parent, CandidateGenerator.suggestionId("fingerprint", "firefox"), null)!!.fingerprint)
        assertNull(g.rebuild(parent, CandidateGenerator.suggestionId("uuid", "22222222-2222-2222-2222-222222222222"), null))
        assertNull(g.rebuild(parent, CandidateGenerator.suggestionId("fingerprint", "evil"), null))
        assertNull(g.rebuild(parent, "suggested:broken", null))
    }

    @Test fun releasesBecomeResearchCandidatesCheckedAgainstTheCore() {
        val p = ResearchPipeline("Xray-core v26.9.9")
        val cs = p.candidates("XTLS/Xray-core", listOf(
            SourceRelease("v26.10.1", "v26.10.1", "- XHTTP: new padding\n- ECH: retry configs", "https://github.com/XTLS/Xray-core/releases/tag/v26.10.1", now),
            SourceRelease("v26.11.0", "", "- Add MASQUE client", "u", now),
            SourceRelease("v26.9.9", "", "- fixes", "u", now)
        ), now)
        assertEquals(listOf("ech", "xhttp"), cs[0].capabilities)
        assertEquals(ResearchCandidate.State.DISCOVERED, cs[0].state)
        assertEquals(ResearchCandidate.State.COMPATIBLE, p.review(cs[0], now).state)
        assertEquals(ResearchCandidate.State.REJECTED, p.review(cs[1], now).state)
        assertEquals(ResearchCandidate.State.REVIEWED, cs[2].state)
        assertEquals(ResearchCandidate.State.EXPIRED, p.review(cs[0], cs[0].expiresAt).state)
        assertTrue(ResearchCandidate.State.entries.none { s -> cs.map { p.review(it, now) }.any { it.state == ResearchCandidate.State.VERIFIED } && s == ResearchCandidate.State.VERIFIED })

        var saved: String? = null
        val store = ResearchStore({ saved }, { saved = it })
        assertEquals(3, store.merge(cs, now).size)
        store.update(p.review(cs[0], now))
        assertEquals(0, ResearchStore({ saved }, { saved = it }).merge(cs, now).size)
        assertEquals(ResearchCandidate.State.COMPATIBLE, ResearchStore({ saved }, {}).all().first { it.id == cs[0].id }.state)
    }

    @Test fun onlyAllowlistedRepositoriesAreReadAndNothingIsDownloaded() {
        assertNotNull(runCatching { GitHubReleaseSource("evil/repo") }.exceptionOrNull())
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"tag_name":"v26.10.1","name":"v26.10.1","body":"ECH","html_url":"https://github.com/XTLS/Xray-core/releases/tag/v26.10.1","published_at":"2026-10-01T10:00:00Z","draft":false},
                {"tag_name":"v9","draft":true},{"tag_name":"v26.10.0","html_url":"https://evil.example/x","body":""}]"""))
            val r = GitHubReleaseSource("XTLS/Xray-core", base = server.url("/").toString().trimEnd('/')).releases()
            assertEquals(listOf("v26.10.1", "v26.10.0"), r.map { it.tag })
            assertEquals("", r[1].url)
            assertTrue(r[0].publishedAt > 0)
            assertEquals("/repos/XTLS/Xray-core/releases?per_page=5", server.takeRequest().path)
        }
    }
}
