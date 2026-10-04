package com.example

import com.example.data.model.ServerTestStatus
import com.example.data.model.VlessProfile
import com.example.panels.BpbFix
import com.example.vpn.ServerTester
import com.example.xray.RealDelayProbe
import com.example.xray.XrayConfigBuilder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealDelayProbeTest {
    private val host = "maximus-bpb-abc.acme.workers.dev"
    private val bpb = VlessProfile(
        name = "BPB", address = host, port = 443, uuid = "8b0e2c4a-9f6d-4c1e-a7b3-2d5f8e1c0a9b",
        transport = "ws", security = "tls", sni = host, host = host, path = "/vl/abc?ed=2560", fingerprint = "chrome"
    )
    private val originalInvoker = RealDelayProbe.invoker

    @After
    fun restore() {
        RealDelayProbe.invoker = originalInvoker
    }

    private fun results(vararg items: JSONObject) =
        JSONObject().put("success", true).put("data", JSONObject().put("results", JSONArray(items.toList()))).toString()

    @Test
    fun requestsAPingBatchThroughTheProxyOutbound() {
        val request = RealDelayProbe.buildRequest(listOf("{}", "{}"), 6)
        assertEquals(3, request.getInt("apiVersion"))
        assertEquals("pingBatch", request.getString("method"))
        val payload = request.getJSONObject("payload")
        assertEquals(6, payload.getInt("timeout"))
        assertEquals(RealDelayProbe.PROBE_URL, payload.getString("url"))
        assertEquals("proxy", payload.getJSONArray("configs").getJSONObject(1).getString("outboundTag"))
    }

    @Test
    fun separatesFailedRequestsFromProbesThatDidNotRun() {
        val outcomes = RealDelayProbe.parseResponse(
            results(JSONObject().put("success", true).put("delay", 842), JSONObject().put("success", false).put("error", "EOF")), 2
        )
        assertEquals(RealDelayProbe.Outcome.Delay(842), outcomes[0])
        assertEquals(RealDelayProbe.Outcome.Failed("EOF"), outcomes[1])

        val busy = RealDelayProbe.parseResponse("""{"success":false,"error":"the VPN's Xray core is running"}""", 2)
        assertTrue(busy.all { it is RealDelayProbe.Outcome.NotRun })
    }

    @Test
    fun batchesFiveConfigsPerCall() {
        val sizes = mutableListOf<Int>()
        RealDelayProbe.invoker = { request ->
            val n = JSONObject(request).getJSONObject("payload").getJSONArray("configs").length()
            sizes += n
            results(*Array(n) { JSONObject().put("success", true).put("delay", 100) })
        }
        val outcomes = RealDelayProbe.measure(List(7) { bpb.copy(name = "n$it") }, 5)
        assertEquals(listOf(5, 2), sizes)
        assertEquals(7, outcomes.count { it is RealDelayProbe.Outcome.Delay })
    }

    @Test
    fun aConfigThatAnswersButCarriesNoTrafficIsUnavailable() = runBlocking {
        RealDelayProbe.invoker = { results(JSONObject().put("success", false).put("error", "websocket: close 1006")) }
        val status = ServerTester.testServer(bpb).status
        assertTrue(status is ServerTestStatus.Unavailable)
        assertTrue((status as ServerTestStatus.Unavailable).reason.startsWith("No traffic through the proxy"))
    }

    @Test
    fun missingNativeLibraryFallsBackToTheReachabilityProbe() {
        // On the JVM the libXray classes are on the classpath but their native library is not.
        RealDelayProbe.invoker = { throw UnsatisfiedLinkError("no gojni in java.library.path") }
        assertTrue(RealDelayProbe.measure(bpb, 5) is RealDelayProbe.Outcome.NotRun)
    }

    @Test
    fun realDelayIsRatedOnItsOwnScale() = runBlocking {
        RealDelayProbe.invoker = { results(JSONObject().put("success", true).put("delay", 640)) }
        assertEquals(ServerTestStatus.Available(640), ServerTester.testServer(bpb).status)
        RealDelayProbe.invoker = { results(JSONObject().put("success", true).put("delay", 1400)) }
        assertEquals(ServerTestStatus.Slow(1400), ServerTester.testServer(bpb).status)
    }

    @Test
    fun fixBpbKeepsTheFirstMaskThatCarriesTraffic() {
        val choice = BpbFix.choose(bpb) { candidates, _ ->
            assertEquals(RealDelayProbe.MAX_BATCH, candidates.size)
            assertEquals(bpb, candidates.last())
            listOf(
                RealDelayProbe.Outcome.Failed("tls: handshake failure"),
                RealDelayProbe.Outcome.Failed("EOF"),
                RealDelayProbe.Outcome.Delay(900),
                RealDelayProbe.Outcome.Delay(700),
                RealDelayProbe.Outcome.Failed("EOF")
            )
        }
        assertEquals(BpbFix.Choice.Verified(BpbFix.FINAL_MASK_TLSHELLO, 900), choice)
    }

    @Test
    fun fixBpbReportsAWorkerThatCarriesNothing() {
        val failed = BpbFix.choose(bpb) { c, _ -> List(c.size) { RealDelayProbe.Outcome.Failed("EOF") } }
        assertEquals(BpbFix.Choice.NothingWorks("EOF"), failed)
        val plainOnly = BpbFix.choose(bpb) { c, _ -> List(c.size - 1) { RealDelayProbe.Outcome.Failed("EOF") } + RealDelayProbe.Outcome.Delay(500) }
        assertEquals(BpbFix.Choice.NotNeeded(500), plainOnly)
        val busy = BpbFix.choose(bpb) { c, _ -> List(c.size) { RealDelayProbe.Outcome.NotRun("busy") } }
        assertEquals(BpbFix.Choice.Untested("busy"), busy)
    }

    @Test
    fun fixBpbTriesTheOriginalRecipeFirstAndBuildsItUnchanged() {
        assertEquals(BpbFix.FINAL_MASK_ORIGINAL, BpbFix.MASKS.first())
        val fixed = bpb.copy(fingerprint = BpbFix.FINGERPRINT, alpn = BpbFix.ALPN,
            cipherSuites = BpbFix.CIPHER_SUITES, finalMask = BpbFix.FINAL_MASK_ORIGINAL)
        assertTrue(BpbFix.wasApplied(fixed))
        val proxy = JSONObject(XrayConfigBuilder.buildJson(fixed, com.example.data.model.AppSettings()))
            .getJSONArray("outbounds").getJSONObject(0)
        val lengths = proxy.getJSONObject("streamSettings").getJSONObject("finalmask").getJSONArray("tcp")
            .getJSONObject(0).getJSONObject("settings").getJSONArray("lengths")
        assertEquals(listOf("0", "104", "1"), (0 until lengths.length()).map { lengths.getString(it) })
    }

    @Test
    fun plainVlessIsNotSentToXraysRealDelayProbe() = runBlocking {
        // Xray refuses plain VLESS to a public address, so such a server must not be marked dead.
        var called = false
        RealDelayProbe.invoker = { called = true; results(JSONObject().put("success", false).put("error", "prohibited")) }
        val plain = VlessProfile(name = "plain", address = "203.0.113.7", port = 443,
            uuid = "8b0e2c4a-9f6d-4c1e-a7b3-2d5f8e1c0a9b", transport = "tcp", security = "none")
        ServerTester.testServers(listOf(plain), timeoutMs = 500)
        assertTrue(!called)
    }
}
