package com.example.panels.cloudflare.relay

import com.example.panels.cloudflare.relay.CloudflareRelay.Checklist
import com.example.panels.cloudflare.relay.CloudflareRelay.Event
import com.example.panels.cloudflare.relay.CloudflareRelay.Phase
import com.example.panels.cloudflare.relay.CloudflareRelay.State
import com.example.panels.cloudflare.relay.CloudflareRelay.TokenKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLDecoder
import kotlin.random.Random

/** Build 181 Phase 7: Connect Cloudflare account (relay Worker). */
class CloudflareRelayTest {
    private val relay = CloudflareRelay.Relay(
        accountId = "0123456789abcdef0123456789abcdef", accountName = "Me", scriptName = "quiet-river-4821",
        subdomain = "maple-1234.workers.dev", uuid = "3f1e2d3c-4b5a-4978-8796-a5b4c3d2e1f0",
        path = "AbCdEfGhIjKlMnOpQrStUvWx", createdAt = 1L
    )

    private fun query(url: String): Map<String, String> = url.substringAfter('?').split('&').associate {
        val (k, v) = it.split('=', limit = 2)
        k to URLDecoder.decode(v, "UTF-8")
    }

    // ---- token validation ----

    @Test fun acceptsUserTokensInBothFormats() {
        val scannable = "cfut_" + "A1b2C3d4E5".repeat(4) + "0a1b2c3d"
        assertEquals(TokenKind.USER, CloudflareRelay.tokenKind(scannable))
        assertEquals(TokenKind.USER, CloudflareRelay.tokenKind("  $scannable\n"))
        assertEquals(TokenKind.LEGACY, CloudflareRelay.tokenKind("aB3_-" + "x".repeat(35)))
        assertTrue(CloudflareRelay.usableToken(scannable))
        assertTrue(com.example.panels.cloudflare.TokenTemplate.looksLikeToken(scannable))
    }

    @Test fun refusesGlobalKeyAccountTokensAndJunk() {
        assertEquals(TokenKind.GLOBAL_KEY, CloudflareRelay.tokenKind("cfk_" + "a".repeat(40) + "0123abcd"))
        assertEquals(TokenKind.GLOBAL_KEY, CloudflareRelay.tokenKind("0123456789abcdef0123456789abcdef01234"))
        assertEquals(TokenKind.ACCOUNT, CloudflareRelay.tokenKind("cfat_" + "a".repeat(40) + "0123abcd"))
        assertFalse(CloudflareRelay.usableToken("cfat_" + "a".repeat(40) + "0123abcd"))
        assertEquals(TokenKind.NONE, CloudflareRelay.tokenKind("cfut_" + "a".repeat(40) + "0123abcg")) // checksum is hex
        assertEquals(TokenKind.NONE, CloudflareRelay.tokenKind("cfut_" + "a".repeat(39) + "0123abcd"))
        assertEquals(TokenKind.NONE, CloudflareRelay.tokenKind("vless://" + "a".repeat(40)))
        assertEquals(TokenKind.NONE, CloudflareRelay.tokenKind(null))
        assertEquals(TokenKind.NONE, CloudflareRelay.tokenKind(""))
    }

    @Test fun clipboardIsReadOnlyOnReturnToStep3() {
        assertTrue(CloudflareRelay.mayReadClipboard(Phase.KEY_PAGE, resumedFromBackground = true))
        assertTrue(CloudflareRelay.mayReadClipboard(Phase.FAILED, resumedFromBackground = true))
        assertFalse(CloudflareRelay.mayReadClipboard(Phase.KEY_PAGE, resumedFromBackground = false))
        for (p in listOf(Phase.START, Phase.OPENED, Phase.VERIFYING, Phase.DEPLOYING, Phase.ACTIVE, Phase.ROTATING))
            assertFalse(p.name, CloudflareRelay.mayReadClipboard(p, resumedFromBackground = true))
    }

    // ---- URL building ----

    @Test fun keyLinkUsesDocumentedShortKeysOnly() {
        val q = query(CloudflareRelay.tokenUrl(withDomain = false))
        assertTrue(CloudflareRelay.tokenUrl(false).startsWith("https://dash.cloudflare.com/profile/api-tokens?permissionGroupKeys="))
        assertEquals("""[{"key":"workers_scripts","type":"edit"},{"key":"account_settings","type":"read"},{"key":"account_analytics","type":"read"}]""",
            q["permissionGroupKeys"])
        assertEquals("*", q["accountId"]); assertEquals("all", q["zoneId"]); assertEquals("Maximus Relay", q["name"])
        assertTrue(CloudflareRelay.tokenUrl(false).endsWith("&name=Maximus%20Relay"))
        // Least privilege: no DNS, zone, KV or billing without the domain option; keys that Cloudflare silently drops
        // (cloudflare-docs #29825: workers_kv, zone_dns) are never used.
        val keys = q.getValue("permissionGroupKeys")
        for (k in listOf("\"dns\"", "\"zone\"", "workers_kv", "zone_dns", "billing", "workers_routes")) assertFalse(k, keys.contains(k))
        val d = query(CloudflareRelay.tokenUrl(withDomain = true)).getValue("permissionGroupKeys")
        assertTrue(d.contains("""{"key":"zone","type":"read"}""")); assertTrue(d.contains("""{"key":"workers_routes","type":"edit"}"""))
        assertFalse(d.contains("\"dns\"")); assertFalse(d.contains("zone_dns"))
    }

    @Test fun apiUrlsAreFixedAndValidated() {
        val a = relay.accountId
        assertEquals("https://api.cloudflare.com/client/v4/accounts/$a/workers/scripts/quiet-river-4821", CloudflareRelay.scriptUrl(a, relay.scriptName))
        assertEquals("https://api.cloudflare.com/client/v4/accounts/$a/workers/scripts/quiet-river-4821/subdomain",
            CloudflareRelay.scriptSubdomainUrl(a, relay.scriptName))
        assertTrue(CloudflareRelay.deleteUrl(a, relay.scriptName).endsWith("/quiet-river-4821?force=true"))
        assertEquals("https://api.cloudflare.com/client/v4/user/tokens/verify", CloudflareRelay.verifyUrl())
        assertTrue(runCatching { CloudflareRelay.scriptUrl("../x", "a") }.isFailure)
        assertTrue(runCatching { CloudflareRelay.scriptUrl(a, "Bad/Name") }.isFailure)
        val body = JSONObject(CloudflareRelay.analyticsQuery(a, "2026-11-27")).getString("query")
        assertTrue(body.contains("accountTag: \"$a\"")); assertTrue(body.contains("date: \"2026-11-27\""))
        assertTrue(runCatching { CloudflareRelay.analyticsQuery(a, "2026-11-27\"}") }.isFailure)
    }

    @Test fun analyticsAnswerIsSummedOrNull() {
        val ok = """{"data":{"viewer":{"accounts":[{"workersInvocationsAdaptive":[{"sum":{"requests":12000}},{"sum":{"requests":400}}]}]}},"errors":null}"""
        assertEquals(12400L, CloudflareRelay.requestsFrom(ok))
        assertNull(CloudflareRelay.requestsFrom("""{"data":null,"errors":[{"message":"not authorized"}]}"""))
        assertNull(CloudflareRelay.requestsFrom("nope"))
    }

    @Test fun profileIsVerifiedTlsWebSocketThroughCleanEdge() {
        val p = CloudflareRelay.profile(relay, "104.18.32.7")
        assertEquals("104.18.32.7", p.address)
        assertEquals("quiet-river-4821.maple-1234.workers.dev", p.sni)
        assertEquals(p.sni, p.host)
        assertEquals("ws", p.transport); assertEquals("tls", p.security); assertEquals(443, p.port)
        assertFalse(p.allowInsecure); assertTrue(p.pinnedPeerCertSha256.isBlank())
        assertEquals("/${relay.path}?ed=2048", p.path)
        assertEquals(relay.host, CloudflareRelay.profile(relay, null).address)
        val custom = relay.copy(customHost = "quiet-river-4821.example.org", zoneId = "z")
        assertEquals("quiet-river-4821.example.org", CloudflareRelay.profile(custom, null).sni)
        assertFalse(relay.toString().contains(relay.uuid)); assertFalse(relay.toString().contains(relay.path))
    }

    @Test fun recordRoundTripsAndRejectsDamage() {
        assertEquals(relay, CloudflareRelay.fromJson(CloudflareRelay.toJson(relay)))
        assertNull(CloudflareRelay.fromJson(CloudflareRelay.toJson(relay.copy(path = "short"))))
        assertNull(CloudflareRelay.fromJson(CloudflareRelay.toJson(relay.copy(scriptName = "Bad Name"))))
        assertNull(CloudflareRelay.fromJson("{"))
        assertNull(CloudflareRelay.fromJson(null))
    }

    // ---- names and secrets ----

    @Test fun generatedNamesAreValidWorkerNames() {
        val r = Random(7)
        val names = (1..500).map { CloudflareRelay.randomName(r) }
        names.forEach {
            assertTrue(it, CloudflareRelay.isGeneratedName(it))
            assertTrue(it, CloudflareRelay.validScriptName(it))
            assertFalse(it, Regex("vpn|proxy|relay|tunnel|maximus").containsMatchIn(it))
        }
        assertTrue(names.toSet().size > 400)
        assertFalse(CloudflareRelay.validScriptName("-x")); assertFalse(CloudflareRelay.validScriptName("x".repeat(64)))
    }

    @Test fun secretsAreRandomUuidV4AndLongPath() {
        val r = Random(11)
        val a = CloudflareRelay.newSecrets(r); val b = CloudflareRelay.newSecrets(r)
        assertNotEquals(a, b)
        assertEquals(4, java.util.UUID.fromString(a.uuid).version())
        assertEquals(CloudflareRelay.PATH_LENGTH, a.path.length)
        assertTrue(a.path.all { it.isLetterOrDigit() })
        val meta = JSONObject(CloudflareRelay.metadataJson(a))
        assertEquals("worker.js", meta.getString("main_module"))
        assertEquals(CloudflareRelay.COMPATIBILITY_DATE, meta.getString("compatibility_date"))
        val bindings = meta.getJSONArray("bindings")
        assertEquals(setOf("RELAY_UUID", "RELAY_PATH"), (0 until bindings.length()).map { bindings.getJSONObject(it).getString("name") }.toSet())
        assertTrue((0 until bindings.length()).all { bindings.getJSONObject(it).getString("type") == "secret_text" })
    }

    // ---- rotation ----

    @Test fun rotationMakesANewAddressAndRemovesTheOldOne() {
        val rot = CloudflareRelay.rotate(relay, Random(3), now = 99L)
        assertEquals(relay.scriptName, rot.removeAfter)
        assertNotEquals(relay.scriptName, rot.next.scriptName)
        assertNotEquals(relay.uuid, rot.next.uuid); assertNotEquals(relay.path, rot.next.path)
        assertEquals(relay.accountId, rot.next.accountId); assertEquals(relay.subdomain, rot.next.subdomain)
        assertEquals(99L, rot.next.createdAt)
        assertNull(rot.next.customHost)
        val custom = relay.copy(customHost = "quiet-river-4821.example.org", zoneId = "zone1")
        val r2 = CloudflareRelay.rotate(custom, Random(4), 5L).next
        assertEquals("${r2.scriptName}.example.org", r2.customHost)
        assertEquals("zone1", r2.zoneId)
    }

    // ---- quota ----

    @Test fun quotaWarnsNearTheLimitAndResetsByUtcDay() {
        val now = 1_795_000_000_000L
        val day = CloudflareRelay.utcDay(now)
        val low = CloudflareRelay.Quota(12_400, day, estimate = false)
        assertEquals(12, low.percent)
        assertFalse(CloudflareRelay.warn(low, now)); assertTrue(CloudflareRelay.smartAllowed(low, now))
        val near = low.copy(used = 85_000)
        assertTrue(CloudflareRelay.warn(near, now)); assertTrue(CloudflareRelay.smartAllowed(near, now))
        val full = low.copy(used = 96_000)
        assertFalse(CloudflareRelay.smartAllowed(full, now))
        assertTrue(CloudflareRelay.smartAllowed(full, now + 86_400_000L)) // next UTC day
        assertNull(CloudflareRelay.current(full, now + 86_400_000L))
        assertTrue(CloudflareRelay.smartAllowed(null, now))
    }

    // ---- state machine ----

    @Test fun happyPathThroughThreeSteps() {
        var s = State()
        assertEquals(1, CloudflareRelay.step(s.phase))
        s = CloudflareRelay.reduce(s, Event.OpenedCloudflare); assertEquals(Phase.OPENED, s.phase); assertEquals(2, CloudflareRelay.step(s.phase))
        s = CloudflareRelay.reduce(s, Event.OpenedKeyPage); assertEquals(Phase.KEY_PAGE, s.phase); assertEquals(3, CloudflareRelay.step(s.phase))
        s = CloudflareRelay.reduce(s, Event.KeyEntered); assertEquals(Phase.VERIFYING, s.phase)
        s = CloudflareRelay.reduce(s, Event.Checked(Checklist(true, true, true))); assertEquals(Phase.DEPLOYING, s.phase)
        s = CloudflareRelay.reduce(s, Event.Deployed); assertEquals(Phase.ACTIVE, s.phase)
        // A key entered while active is ignored.
        assertEquals(Phase.ACTIVE, CloudflareRelay.reduce(s, Event.KeyEntered).phase)
    }

    @Test fun missingPermissionShowsChecklistAndRetries() {
        var s = CloudflareRelay.reduce(State(Phase.KEY_PAGE), Event.KeyEntered)
        val c = Checklist(valid = true, account = true, workers = false)
        s = CloudflareRelay.reduce(s, Event.Checked(c))
        assertEquals(Phase.FAILED, s.phase); assertEquals(c, s.checklist); assertTrue(s.checklist.failed)
        assertEquals(Phase.VERIFYING, CloudflareRelay.reduce(s, Event.Retry).phase)
        assertEquals(Checklist(), CloudflareRelay.reduce(s, Event.Retry).checklist)
        assertEquals(Phase.KEY_PAGE, CloudflareRelay.reduce(s, Event.OpenedKeyPage).phase)
        val f = CloudflareRelay.reduce(State(Phase.DEPLOYING), Event.Failed(Checklist(true, true, false), "x"))
        assertEquals(Phase.FAILED, f.phase); assertEquals("x", f.message)
    }

    @Test fun rotateAndDisconnect() {
        val active = CloudflareRelay.reduce(State(), Event.Restore)
        assertEquals(Phase.ACTIVE, active.phase)
        val rotating = CloudflareRelay.reduce(active, Event.RotateStarted)
        assertEquals(Phase.ROTATING, rotating.phase)
        assertEquals(Phase.ACTIVE, CloudflareRelay.reduce(rotating, Event.RotateDone).phase)
        val failed = CloudflareRelay.reduce(rotating, Event.ActionFailed("no network"))
        assertEquals(Phase.ACTIVE, failed.phase); assertEquals("no network", failed.message)
        val removing = CloudflareRelay.reduce(active, Event.RemoveStarted)
        assertEquals(Phase.START, CloudflareRelay.reduce(removing, Event.Removed).phase)
        // Removing is not possible from the steps.
        assertEquals(Phase.KEY_PAGE, CloudflareRelay.reduce(State(Phase.KEY_PAGE), Event.RemoveStarted).phase)
    }

    @Test fun promptOnlyOnceForUsersWithoutAServerOrRelay() {
        assertTrue(CloudflareRelay.shouldPrompt(alreadyShown = false, connectedVerified = true, hasOwnServer = false, hasRelay = false))
        assertFalse(CloudflareRelay.shouldPrompt(true, true, false, false))
        assertFalse(CloudflareRelay.shouldPrompt(false, false, false, false))
        assertFalse(CloudflareRelay.shouldPrompt(false, true, true, false))
        assertFalse(CloudflareRelay.shouldPrompt(false, true, false, true))
    }

    // ---- the vendored Worker ----

    @Test fun workerScriptMatchesItsPinAndStaysMinimal() {
        val bytes = File("src/main/assets/${CloudflareRelay.WORKER_ASSET}").readBytes()
        assertEquals(CloudflareRelay.WORKER_SHA256, CloudflareRelay.sha256Hex(bytes))
        assertNotNull(CloudflareRelay.verifiedScript(bytes))
        assertTrue(runCatching { CloudflareRelay.verifiedScript(bytes + 0x20) }.isFailure)
        val js = String(bytes)
        assertTrue(js.contains("import { connect } from \"cloudflare:sockets\";"))
        assertEquals(1, Regex("^import ", RegexOption.MULTILINE).findAll(js).count())
        // No outside services, no storage, no logging, no hard-coded secrets.
        for (bad in listOf("await fetch(", "= fetch(", "fetch(\"", "console.", "env.KV", "caches.", "eval(", "new Function", "http://", "https://"))
            assertFalse(bad, js.contains(bad))
        assertFalse(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").containsMatchIn(js))
        assertTrue(js.contains("env.RELAY_UUID")); assertTrue(js.contains("env.RELAY_PATH"))
        assertTrue(js.contains("if (command !== 1) return null;"))
        assertTrue(js.contains("port === 25"))
    }

    @Test fun relayConnectionIsItsOwnLabelledPick() {
        val m = com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod.CLOUDFLARE_RELAY
        assertEquals("Cloudflare relay", m.title)
        assertNull(m.aetherTransport)
        com.example.vpn.connections.CloudflareRelayRuntime.relay = null
        assertNotNull(m.missingDependency())
        com.example.vpn.connections.CloudflareRelayRuntime.relay = relay
        try {
            assertNull(m.missingDependency())
            assertTrue(com.example.vpn.connections.CloudflareRelayRuntime.smartReady())
            com.example.vpn.connections.CloudflareRelayRuntime.relay = relay.copy(smartUse = false)
            assertFalse(com.example.vpn.connections.CloudflareRelayRuntime.smartReady())
        } finally {
            com.example.vpn.connections.CloudflareRelayRuntime.relay = null
        }
    }
}
