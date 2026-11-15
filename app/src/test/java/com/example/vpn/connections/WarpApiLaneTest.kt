package com.example.vpn.connections

import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.AetherApiBridge
import com.example.vpn.sidecar.AetherSettings
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.SidecarProcess
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Irancell 2026-11-15: "WARP API registration failed (account bootstrap or refresh timed out after 14s)" on every
 * start, with the ClientHello fragmented and the compatible TLS retry. The engine's account API lanes (OOB ClientHello,
 * the user's Worker relay) are switched on for Iranian mobile networks; the app only decides and logs.
 */
class WarpApiLaneTest {
    private val relay = "https://warp-api.me.workers.dev/0123456789abcdefKEY"

    @Test fun onlyIranianMobileNetworksWithDirectAccountCalls() {
        val plain = AetherSettings()
        assertTrue(WarpApiLane.applies(irancell = true, mci = false, options = plain))
        assertTrue(WarpApiLane.applies(irancell = false, mci = true, options = plain))
        assertFalse("other networks keep Aether's own call", WarpApiLane.applies(irancell = false, mci = false, options = plain))
        assertFalse("the forced Direct OOB bridge hands calls to a proxy",
            WarpApiLane.applies(true, false, AetherSettings(apiRoute = "oob")))
        assertFalse(WarpApiLane.applies(true, false, AetherSettings(tor = "chain")))
        assertFalse(WarpApiLane.applies(true, false, AetherSettings(psiphon = "chain")))
        assertFalse(WarpApiLane.applies(true, false, AetherSettings(exitNode = "vless://x")))
        assertTrue("the native route is where the lanes run", WarpApiLane.applies(true, false, AetherSettings(apiRoute = "native")))
    }

    @Test fun markingOnlyAddsTheFlagAndKeepsTheMethod() {
        val h2 = AetherSidecar.profile("h2")
        val marked = WarpApiLane.mark(h2)
        val before = ProfileExtras.read(h2)
        val after = ProfileExtras.read(marked)
        assertTrue(WarpApiLane.enabled(after))
        assertFalse(WarpApiLane.enabled(before))
        assertEquals(before.optString("carrier"), after.optString("carrier"))
        assertEquals(before.optString("apiRoute"), after.optString("apiRoute"))
        assertSame("marking twice changes nothing", marked, WarpApiLane.mark(marked))
        // The Irancell attempt still skips the Direct OOB bridge and keeps the lane flag.
        val irancell = ProfileExtras.read(WarpApiLane.mark(IrancellWarp.withNativeApi(h2)))
        assertEquals("native", irancell.optString("apiRoute"))
        assertTrue(WarpApiLane.enabled(irancell))
    }

    @Test fun environmentCarriesTheLaneAndAValidRelayOnly() {
        val marked = JSONObject().put(WarpApiLane.EXTRA, true)
        assertEquals(mapOf(WarpApiLane.OOB_ENV to "1"), WarpApiLane.environment(marked, AetherSettings()))
        assertEquals(mapOf(WarpApiLane.OOB_ENV to "1", WarpApiLane.RELAY_ENV to relay),
            WarpApiLane.environment(marked, AetherSettings(apiRelay = relay)))
        assertEquals("a relay alone also races", mapOf(WarpApiLane.RELAY_ENV to relay),
            WarpApiLane.environment(JSONObject(), AetherSettings(apiRelay = relay)))
        assertTrue(WarpApiLane.environment(JSONObject(), AetherSettings()).isEmpty())
        assertTrue("an invalid relay is never passed",
            WarpApiLane.environment(JSONObject(), AetherSettings(apiRelay = "http://x.workers.dev/")).isEmpty())
        assertTrue("a proxied account call has no relay lane",
            WarpApiLane.environment(JSONObject(), AetherSettings(apiRelay = relay, tor = "chain")).isEmpty())
    }

    @Test fun relayAddressesMatchTheEngineRules() {
        for (good in listOf("", relay, "https://Relay.Example.com:443", "https://a.workers.dev"))
            assertTrue(good, AetherSettings.validRelay(good))
        for (bad in listOf("http://a.workers.dev/", "https://a.workers.dev:8443/", "https://user@a.workers.dev/",
                "https://104.21.1.2/", "https://localhost/", "https://a.workers.dev/x?y=1", "https://a.workers.dev/../x",
                "https://-a.workers.dev/", "https://a.workers.dev/x y"))
            assertFalse(bad, AetherSettings.validRelay(bad))
        assertNotNull(AetherSettings(apiRelay = "http://nope").problem())
        assertNull(AetherSettings(apiRelay = relay).problem())
    }

    @Test fun relaySurvivesSaveAndLoad() {
        val saved = AetherSettings(apiRelay = relay).write(JSONObject())
        assertEquals(relay, AetherSettings.read(saved).apiRelay)
        assertEquals("", AetherSettings.read(JSONObject()).apiRelay)
    }

    @Test fun theEngineLaneLineIsReadStrictly() {
        assertEquals("oob:disoob-byte0-ttl1/tls12@v4", WarpApiLane.lane("MAXIMUS_AETHER_API_LANE:oob:disoob-byte0-ttl1/tls12@v4"))
        assertEquals("relay@v6", WarpApiLane.lane("  MAXIMUS_AETHER_API_LANE:relay@v6 "))
        assertEquals("none", WarpApiLane.lane("MAXIMUS_AETHER_API_LANE:none"))
        assertNull(WarpApiLane.lane("MAXIMUS_AETHER_API_LANE:relay 104.21.1.2"))
        assertNull(WarpApiLane.lane("MAXIMUS_AETHER_API_LANE:"))
        assertNull(WarpApiLane.lane("[+] something else"))
        assertTrue(WarpApiLane.describe("oob:oob-insni+5-ttl1/tls12@v4").contains("OOB"))
        assertTrue(WarpApiLane.describe("relay@v4").contains("relay"))
        assertTrue(WarpApiLane.describe("none").contains("no account lane"))
        // The log hook accepts every line without throwing.
        SidecarProcess.reportApiTlsEvent("MAXIMUS_AETHER_API_LANE:direct")
        SidecarProcess.reportApiTlsEvent("unrelated")
    }

    @Test fun directOobFailureNoLongerStopsAnAttemptThatHasLanes() {
        assertTrue(AetherApiBridge.failFastWithoutIdentity("auto", apiReachable = false, hasIdentity = false))
        assertFalse(AetherApiBridge.failFastWithoutIdentity("auto", apiReachable = false, hasIdentity = false, laneFallback = true))
    }

    @Test fun summaryNamesTheRelayOnlyWhenSet() {
        assertFalse(WarpApiLane.summary(AetherSettings()).contains("relay"))
        assertTrue(WarpApiLane.summary(AetherSettings(apiRelay = relay)).contains("Worker relay"))
        assertTrue(WarpApiLane.summary(AetherSettings()).endsWith("only the first finished handshake sends the request"))
    }

    @Test fun theRelayWorkerRefusesCallsWithoutItsKeyAndForwardsOnlyTheWarpApi() {
        val worker = File("src/main/assets/warp-relay/worker.js").readText()
        assertTrue(worker.contains("RELAY_KEY"))
        assertTrue(worker.contains("key.length < 16"))
        assertTrue(worker.contains("\"api.cloudflareclient.com\"") && worker.contains("\"api.devices.cloudflare.com\""))
        assertTrue("the envelope the engine reads", worker.contains("ok: true, status: answer.status, body: text"))
    }
}
