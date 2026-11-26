package com.example.vpn.connections

import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.AetherTlsProfiles
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * patterniha (2026-11): on the Irancell firewall MASQUE needs a non-Chrome fingerprint. Auto tries semi-python,
 * firefox, go, then chrome there, remembers the winner per network, and keeps chrome on Hamrah-e-Aval. A
 * fingerprint the user picked is never replaced.
 */
class AetherFingerprintBookTest {
    private val store = mutableMapOf<String, String>()
    private val irancell = "cell:43235"
    private val mci = "cell:43211"

    @Before fun setUp() {
        store.clear()
        AetherFingerprintBook.read = { store[it] }
        AetherFingerprintBook.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
    }

    @After fun tearDown() {
        AetherFingerprintBook.read = { null }
        AetherFingerprintBook.write = { _, _ -> }
    }

    @Test fun irancellTriesNonChromeProfilesFirstAndMciKeepsChrome() {
        assertEquals(listOf("semi-python", "firefox", "go", "chrome"), AetherFingerprintBook.order(irancell))
        assertEquals("semi-python", AetherFingerprintBook.pick(irancell, now = 1_000))
        assertEquals(listOf("chrome"), AetherFingerprintBook.order(mci))
        assertEquals("chrome", AetherFingerprintBook.pick(mci, now = 1_000))
        assertEquals("chrome", AetherFingerprintBook.pick("wifi:abc", now = 1_000))
        assertEquals("chrome", AetherFingerprintBook.pick(null, now = 1_000))
        // Every name in the order is a concrete profile Aether can send.
        assertTrue(AetherTlsProfiles.names.containsAll(AetherFingerprintBook.IRANCELL_ORDER))
    }

    @Test fun eachFailureMovesTheNextAttemptOnAndAllFailedStartsOverWithTheOldest() {
        var now = 10_000L
        val tried = mutableListOf<String>()
        repeat(4) {
            val fp = AetherFingerprintBook.pick(irancell, now)
            tried += fp
            AetherFingerprintBook.fail(irancell, fp, now)
            now += 1_000
        }
        assertEquals(listOf("semi-python", "firefox", "go", "chrome"), tried)
        // Every profile failed recently: the one that failed longest ago comes round again.
        assertEquals("semi-python", AetherFingerprintBook.pick(irancell, now))
        // A failure expires after FAIL_TTL_MS.
        assertEquals("semi-python", AetherFingerprintBook.pick(irancell, 10_000L + AetherFingerprintBook.FAIL_TTL_MS))
        // Nothing is stored where Auto has only one choice.
        AetherFingerprintBook.fail(mci, "chrome", now)
        assertTrue(store.keys.none { it.endsWith(mci) })
    }

    @Test fun theWinnerIsRememberedPerNetworkUntilItFails() {
        AetherFingerprintBook.fail(irancell, "semi-python", 1_000)
        AetherFingerprintBook.win(irancell, "firefox", 2_000)
        assertEquals("firefox", AetherFingerprintBook.pick(irancell, 3_000))
        // Kept under this network's key only.
        assertEquals(setOf("aetherFp|$irancell"), store.keys)
        // The winner failing drops it; semi-python is still in its fail window, so go is next.
        AetherFingerprintBook.fail(irancell, "firefox", 4_000)
        assertEquals("go", AetherFingerprintBook.pick(irancell, 5_000))
        // A remembered winner expires.
        AetherFingerprintBook.win(irancell, "go", 6_000)
        assertEquals("go", AetherFingerprintBook.pick(irancell, 6_000 + AetherFingerprintBook.WIN_TTL_MS - 1))
        assertNotEquals("go", AetherFingerprintBook.pick(irancell, 6_000 + AetherFingerprintBook.WIN_TTL_MS + AetherFingerprintBook.FAIL_TTL_MS))
        // Junk in storage is ignored.
        store["aetherFp|$irancell"] = "{not json"
        assertEquals("semi-python", AetherFingerprintBook.pick(irancell, 1))
    }

    @Test fun resolveOnlyReplacesAutoOnMasqueCarriers() {
        val auto = AetherSidecar.profile("h2")
        val resolved = AetherFingerprintBook.resolve(auto, irancell, now = 1_000)
        val e = ProfileExtras.read(resolved)
        assertEquals("semi-python", AetherSettings.read(e).fingerprint)
        assertTrue(e.optBoolean(AetherFingerprintBook.AUTO_PICKED))
        assertEquals("semi-python", AetherFingerprintBook.picked(resolved))
        val args = AetherSettings.read(e).arguments("h2", true)
        assertTrue("semi-python sends GREASE off", "--disable-grease" in args)
        // Resolving again keeps the pick (it is concrete now).
        assertEquals(resolved, AetherFingerprintBook.resolve(resolved, irancell, now = 2_000))
        // H3 is MASQUE TLS too; WireGuard sends no TLS and is left alone.
        assertEquals("semi-python", AetherFingerprintBook.picked(AetherFingerprintBook.resolve(AetherSidecar.profile("h3"), irancell, 1_000)))
        val wg = AetherSidecar.profile("wireguard")
        assertEquals(wg, AetherFingerprintBook.resolve(wg, irancell, 1_000))
        // On Hamrah-e-Aval Auto is chrome, and a Chrome pick is not read back as Auto.
        val onMci = AetherFingerprintBook.resolve(auto, mci, 1_000)
        assertEquals("chrome", AetherSettings.read(ProfileExtras.read(onMci)).fingerprint)
        assertEquals("chrome", AetherFingerprintBook.picked(onMci))
    }

    @Test fun aFingerprintTheUserChoseIsNeverReplacedOrRecorded() {
        for (own in AetherTlsProfiles.names) {
            val chosen = AetherSidecar.profile("h2", advanced = AetherSettings(fingerprint = own))
            assertEquals(chosen, AetherFingerprintBook.resolve(chosen, irancell, 1_000))
            assertNull(AetherFingerprintBook.picked(chosen))
            AetherFingerprintBook.record(irancell, chosen, verified = false, now = 1_000)
        }
        assertTrue("a user's own choice leaves no trace in the book", store.isEmpty())
    }

    @Test fun recordWinsAndFailuresOnlyForPicks() {
        val picked = AetherFingerprintBook.resolve(AetherSidecar.profile("h2"), irancell, now = 1_000)
        AetherFingerprintBook.record(irancell, picked, verified = false, now = 1_000)
        val next = AetherFingerprintBook.resolve(AetherSidecar.profile("h2"), irancell, now = 2_000)
        assertEquals("firefox", AetherFingerprintBook.picked(next))
        AetherFingerprintBook.record(irancell, next, verified = true, now = 2_000)
        assertEquals("firefox", AetherFingerprintBook.pick(irancell, 3_000))
    }

    @Test fun autoIsTheDefaultAndOldSavedChromeReadsAsAuto() {
        assertEquals(AetherTlsProfiles.AUTO, AetherSettings().fingerprint)
        assertEquals(AetherTlsProfiles.AUTO, AetherSettings.read(JSONObject()).fingerprint)
        // Saved before Auto existed: "chrome" was the untouched default (Irancell already swapped it for Firefox).
        assertEquals(AetherTlsProfiles.AUTO, AetherSettings.read(JSONObject().put("fingerprint", "chrome")).fingerprint)
        assertEquals("go", AetherSettings.read(JSONObject().put("fingerprint", "go")).fingerprint)
        // Chosen from now on: kept as Chrome.
        val chrome = AetherSettings(fingerprint = "chrome")
        assertEquals("chrome", AetherSettings.read(chrome.write(JSONObject())).fingerprint)
        val auto = AetherSettings()
        assertEquals(auto, AetherSettings.read(auto.write(JSONObject())))
        assertNull(auto.problem())
        assertNotNull(AetherSettings(fingerprint = "safari").problem())
        // Auto with no network decision sends exactly what Chrome sends (the pre-Auto default).
        assertEquals(AetherTlsProfiles.arguments("chrome"), AetherTlsProfiles.arguments(AetherTlsProfiles.AUTO))
        assertEquals(listOf(AetherTlsProfiles.AUTO) + AetherTlsProfiles.names, AetherTlsProfiles.choices)
    }

    @Test fun builtInAetherConnectionsStartOnAuto() {
        for (method in com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod.entries.filter { it.aetherTransport != null }) {
            val e = ProfileExtras.read(method.profile())
            assertEquals(method.name, AetherTlsProfiles.AUTO, AetherSettings.read(e).fingerprint)
        }
        val hop = ProfileExtras.read(com.example.vpn.sidecar.EngineChain.hopProfile(com.example.vpn.sidecar.EngineChain.Hop("aether")))
        assertEquals(AetherTlsProfiles.AUTO, AetherSettings.read(hop).fingerprint)
    }
}
