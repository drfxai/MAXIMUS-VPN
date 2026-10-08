package com.example.ai.gateway

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** AI Gateway: providers, BYOK vault, discovery, routing modes, failover, circuit breaker, error normalization. */
class AiGatewayTest {
    private lateinit var server: MockWebServer
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val routes = mutableMapOf<String, () -> MockResponse>()
    private var now = 1_000_000L

    private val openAiModels = """{"data":[{"id":"gpt-fast-mini"},{"id":"gpt-deep-pro"},{"id":"text-embedding-3"},{"id":"vision-omni"}]}"""
    private fun chat(text: String) = MockResponse().setBody("""{"model":"m","choices":[{"message":{"content":"$text"}}],"usage":{"prompt_tokens":3,"completion_tokens":4}}""")

    @Before fun start() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path.orEmpty()
                return routes.entries.firstOrNull { path.startsWith(it.key) }?.value?.invoke() ?: MockResponse().setResponseCode(404).setBody("""{"error":{"message":"no route"}}""")
            }
        }
        server.start()
    }

    @After fun stop() { server.shutdown() }

    private fun url(prefix: String) = server.url(prefix).toString().trimEnd('/')

    private data class Env(val gateway: MaximusAiGateway, val vault: AiCredentialVault, val store: InMemorySecretStore, val catalog: ModelCatalog)

    private fun env(settings: AiGatewaySettings): Env {
        val store = InMemorySecretStore()
        val vault = AiCredentialVault(store) { now }
        val catalog = ModelCatalog({ null }, {}, { now })
        var saved: String? = settings.toJson()
        val gateway = MaximusAiGateway(AiGatewaySettingsStore({ saved }, { saved = it }), ProviderRegistry(vault), vault, catalog, { now })
        return Env(gateway, vault, store, catalog)
    }

    private fun provider(id: String, kind: AiProviderKind, prefix: String, timeout: Long = 5_000) =
        AiProviderConfig(id, kind, url(prefix), timeoutMs = timeout)

    private fun req(text: String = "hello", task: AiTaskClass = AiTaskClass.GENERAL_CHAT) =
        AiChatRequest(task, listOf(AiMessage(AiMessage.Role.USER, text)))

    // ---- Registry and vault ----

    @Test fun everyBuiltInProviderIsRegisteredAndNewKindsCanBeAdded() {
        val registry = ProviderRegistry(AiCredentialVault(InMemorySecretStore()))
        AiProviderKind.entries.forEach { assertTrue(it.name, registry.isRegistered(it)) }
        val a = registry.adapter(AiProviderConfig("gemini", AiProviderKind.GEMINI))
        assertTrue(a is GeminiAdapter)
        assertTrue(registry.adapter(AiProviderConfig("9router", AiProviderKind.NINE_ROUTER, "https://router.example/v1")) is NineRouterAdapter)
        var built = 0
        registry.register(AiProviderKind.OPENAI_COMPATIBLE) { c, k, h -> built++; GenericOpenAiCompatibleAdapter(c, k, h) }
        registry.adapter(AiProviderConfig("custom", AiProviderKind.OPENAI_COMPATIBLE, "https://x.example/v1"))
        assertEquals(1, built)
    }

    @Test fun vaultCleansMasksAndRefusesBadKeys() {
        val store = InMemorySecretStore()
        val vault = AiCredentialVault(store)
        assertEquals(AiCredentialVault.SaveResult.Saved, vault.save("openai", "‏ sk-abcdefghijklmnop7D3A‬ "))
        assertEquals("sk-••••••••••••7D3A", vault.masked("openai"))
        assertTrue(vault.save("openai", "has space inside") is AiCredentialVault.SaveResult.Refused)
        assertTrue(vault.save("openai", "short") is AiCredentialVault.SaveResult.Refused)
        assertEquals("sk-••••••••••••7D3A", vault.masked("openai"))
        vault.revokeLocally("openai")
        assertFalse(vault.has("openai"))
        assertNotNull(vault.revokedAt("openai"))
        assertTrue(store.names().all { it.startsWith("ai_key_") })
    }

    @Test fun endpointPolicyKeepsKeysOnHttps() {
        assertNull(EndpointPolicy.problem("https://openrouter.ai/api/v1"))
        assertNull(EndpointPolicy.problem("http://localhost:20128/v1"))
        assertNull(EndpointPolicy.problem("http://127.0.0.1:20128/v1"))
        assertNotNull(EndpointPolicy.problem("http://192.168.1.5:20128/v1"))
        assertNotNull(EndpointPolicy.problem("https://user:pass@host.example/v1"))
        assertNotNull(EndpointPolicy.problem("ftp://host.example"))
        assertNotNull(EndpointPolicy.problem(""))
    }

    // ---- Credential validation and discovery ----

    @Test fun validationPassesWithAGoodKeyAndNormalizesARefusedOne() = runBlocking {
        routes["/ok/models"] = { MockResponse().setBody(openAiModels) }
        routes["/bad/models"] = { MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Incorrect API key provided: sk-abc***"}}""") }
        val e = env(AiGatewaySettings(providers = listOf(provider("openai", AiProviderKind.OPENAI, "/ok"), provider("custom", AiProviderKind.OPENAI_COMPATIBLE, "/bad"))))
        e.gateway.saveKey("openai", "sk-goodgoodgood1234")
        e.gateway.saveKey("custom", "sk-badbadbadbad1234")
        val ok = e.gateway.testConnection("openai")
        assertTrue(ok.message, ok.ok)
        assertEquals(3, ok.models) // the embedding model is not a chat model
        val bad = e.gateway.testConnection("custom")
        assertFalse(bad.ok)
        assertEquals(ProviderHealthState.AUTH_FAILED, e.gateway.health().first { it.providerId == "custom" }.state)
        val auth = requests.first { it.path == "/ok/models" }.getHeader("Authorization")
        assertEquals("Bearer sk-goodgoodgood1234", auth)
    }

    @Test fun openRouterMetadataIsDeclaredAndItsAutoRouterIsOffered() = runBlocking {
        routes["/or/models"] = { MockResponse().setBody("""{"data":[
            {"id":"vendor/see","name":"See","context_length":128000,"architecture":{"input_modalities":["text","image"]},"supported_parameters":["tools","reasoning"],"pricing":{"prompt":"0"}},
            {"id":"vendor/text","context_length":8000,"architecture":{"input_modalities":["text"]},"supported_parameters":[],"pricing":{"prompt":"0.00001"}}]}""") }
        val e = env(AiGatewaySettings(providers = listOf(provider("openrouter", AiProviderKind.OPENROUTER, "/or"))))
        e.gateway.saveKey("openrouter", "sk-or-abcdefghijkl")
        val models = e.gateway.refreshModels("openrouter")
        assertEquals(OpenRouterAdapter.AUTO_MODEL, models.first().modelId)
        assertEquals(ModelSource.PROVIDER_ROUTER, models.first().source)
        val see = models.first { it.modelId == "vendor/see" }
        assertEquals(true, see.supportsVision)
        assertEquals(true, see.supportsTools)
        assertEquals(128000, see.contextWindow)
        assertEquals(RelativeLevel.LOW, see.relativeCost)
        assertEquals(MetadataSource.DECLARED, see.metadata)
        assertEquals(false, models.first { it.modelId == "vendor/text" }.supportsVision)
        assertEquals("Maximus VPN", requests.last().getHeader("X-Title"))
    }

    @Test fun nineRouterCombosAreItsOwnRouting() = runBlocking {
        routes["/9r/models"] = { MockResponse().setBody("""{"data":[{"id":"premium-coding"},{"id":"cx/gpt-5.5"},{"id":"glm/glm-5.1"}]}""") }
        val e = env(AiGatewaySettings(providers = listOf(provider("9router", AiProviderKind.NINE_ROUTER, "/9r"))))
        e.gateway.saveKey("9router", "9r-key-abcdefgh")
        val models = e.gateway.refreshModels("9router")
        val combo = models.first { it.modelId == "premium-coding" }
        assertEquals(ModelSource.PROVIDER_ROUTER, combo.source)
        assertEquals(ModelSource.DISCOVERED, models.first { it.modelId == "cx/gpt-5.5" }.source)
    }

    @Test fun geminiDiscoveryKeepsGenerateContentModelsAndSendsTheKeyInAHeader() = runBlocking {
        routes["/g/models"] = { MockResponse().setBody("""{"models":[
            {"name":"models/gemini-9-flash","displayName":"Gemini 9 Flash","inputTokenLimit":1048576,"supportedGenerationMethods":["generateContent","streamGenerateContent"]},
            {"name":"models/text-embedding-9","supportedGenerationMethods":["embedContent"]}]}""") }
        val e = env(AiGatewaySettings(providers = listOf(provider("gemini", AiProviderKind.GEMINI, "/g"))))
        e.gateway.saveKey("gemini", "AIzaSyTESTKEYVALUE0000")
        val models = e.gateway.refreshModels("gemini")
        assertEquals(listOf("gemini-9-flash"), models.map { it.modelId })
        assertEquals(true, models[0].supportsVision)
        assertEquals(RelativeLevel.HIGH, models[0].relativeSpeed)
        val r = requests.last()
        assertEquals("AIzaSyTESTKEYVALUE0000", r.getHeader("x-goog-api-key"))
        assertFalse(r.path.orEmpty().contains("AIza"))
    }

    @Test fun refreshingModelsReplacesTheList() = runBlocking {
        var list = """{"data":[{"id":"model-a"}]}"""
        routes["/o/models"] = { MockResponse().setBody(list) }
        val e = env(AiGatewaySettings(providers = listOf(provider("nvidia", AiProviderKind.NVIDIA_NIM, "/o"))))
        e.gateway.saveKey("nvidia", "nvapi-abcdefghij")
        assertEquals(listOf("model-a"), e.gateway.refreshModels("nvidia").map { it.modelId })
        list = """{"data":[{"id":"model-b"},{"id":"model-c"}]}"""
        e.gateway.refreshModels("nvidia")
        assertEquals(listOf("model-b", "model-c"), e.catalog.models("nvidia").map { it.modelId })
    }

    // ---- Routing modes ----

    private fun catalogWith(vararg models: AiModelDescriptor): ModelCatalog =
        ModelCatalog({ null }, {}, { now }).apply { models.groupBy { it.providerId }.forEach { (p, l) -> replace(p, l) } }

    @Test fun manualFollowsTheUsersPicksExactly() {
        val s = AiGatewaySettings(AiRoutingMode.MANUAL, listOf(AiProviderConfig("a", AiProviderKind.OPENAI), AiProviderConfig("b", AiProviderKind.OPENROUTER)),
            RouteChoice("a", "picked-model"), listOf(RouteChoice("b", "other")))
        val r = SmartModelRouter(catalogWith()) { ProviderCircuitBreaker(it) }.routes(s, AiTaskClass.GENERAL_CHAT)
        assertEquals(listOf("a/picked-model", "b/other"), r.map { "${it.providerId}/${it.modelId}" })
    }

    @Test fun autoPrefersTheProvidersOwnRouterAndOtherwiseTheBestFit() {
        val s = AiGatewaySettings(AiRoutingMode.AUTO, listOf(AiProviderConfig("or", AiProviderKind.OPENROUTER), AiProviderConfig("oa", AiProviderKind.OPENAI)),
            RouteChoice("or"), listOf(RouteChoice("oa")))
        val cat = catalogWith(
            AiModelDescriptor("or", OpenRouterAdapter.AUTO_MODEL, source = ModelSource.PROVIDER_ROUTER), AiModelDescriptor("or", "x/slow-pro", relativeSpeed = RelativeLevel.LOW),
            AiModelDescriptor("oa", "deep-pro", relativeSpeed = RelativeLevel.LOW, supportsReasoning = true), AiModelDescriptor("oa", "quick-mini", relativeSpeed = RelativeLevel.HIGH)
        )
        val router = SmartModelRouter(cat) { ProviderCircuitBreaker(it) }
        assertEquals(listOf("or/openrouter/auto", "oa/quick-mini"), router.routes(s, AiTaskClass.FAST_CLASSIFICATION).map { "${it.providerId}/${it.modelId}" })
        assertEquals("oa/deep-pro", router.routes(s, AiTaskClass.NETWORK_ANALYSIS).last().let { "${it.providerId}/${it.modelId}" })
    }

    @Test fun smartRanksByCapabilityAndHealthAndNeverSendsImagesToBlindModels() {
        val s = AiGatewaySettings(AiRoutingMode.SMART, listOf(AiProviderConfig("a", AiProviderKind.OPENAI), AiProviderConfig("b", AiProviderKind.NVIDIA_NIM)),
            RouteChoice("a"), listOf(RouteChoice("b")))
        val cat = catalogWith(
            AiModelDescriptor("a", "text-only", supportsVision = false),
            AiModelDescriptor("b", "sees-things", supportsVision = true)
        )
        val breakers = mutableMapOf<String, ProviderCircuitBreaker>()
        val router = SmartModelRouter(cat) { breakers.getOrPut(it) { ProviderCircuitBreaker(it, { now }) } }
        assertEquals(listOf("b/sees-things"), router.routes(s, AiTaskClass.MULTIMODAL_ANALYSIS, hasImage = true).map { "${it.providerId}/${it.modelId}" })
        // A paused provider goes last in SMART, whatever its preference.
        breakers.getOrPut("a") { ProviderCircuitBreaker("a", { now }) }.recordFailure(AiError(AiErrorKind.AUTH_FAILED, "x"))
        assertEquals("b", router.routes(s, AiTaskClass.GENERAL_CHAT).first().providerId)
    }

    @Test fun researchNeedsAContextWindowBigEnough() {
        val s = AiGatewaySettings(AiRoutingMode.AUTO, listOf(AiProviderConfig("a", AiProviderKind.OPENAI)), RouteChoice("a"))
        val cat = catalogWith(AiModelDescriptor("a", "tiny", contextWindow = 4000), AiModelDescriptor("a", "big", contextWindow = 200_000))
        assertEquals("big", SmartModelRouter(cat) { ProviderCircuitBreaker(it) }.routes(s, AiTaskClass.RESEARCH).single().modelId)
    }

    // ---- Failover, breaker, errors ----

    @Test fun failsOverToTheNextProviderAndRecordsEveryAttempt() = runBlocking {
        routes["/p1/chat/completions"] = { MockResponse().setResponseCode(503).setBody("""{"error":{"message":"overloaded"}}""") }
        routes["/p2/chat/completions"] = { chat("from fallback") }
        val e = env(AiGatewaySettings(AiRoutingMode.MANUAL, listOf(provider("p1", AiProviderKind.OPENAI_COMPATIBLE, "/p1"), provider("p2", AiProviderKind.OPENAI_COMPATIBLE, "/p2")),
            RouteChoice("p1", "m1"), listOf(RouteChoice("p2", "m2"))))
        e.gateway.saveKey("p1", "key-one-abcdefgh")
        e.gateway.saveKey("p2", "key-two-abcdefgh")
        val r = e.gateway.chat(AiConsumer.MAIN_AGENT, req())
        assertEquals("from fallback", r.text)
        assertEquals(listOf("failed", "ok"), r.attempts.map { it.outcome })
        assertEquals(AiErrorKind.SERVER_ERROR, r.attempts[0].error)
        // Failover never carries one provider's key to another.
        assertEquals("Bearer key-one-abcdefgh", requests.first { it.path!!.startsWith("/p1") }.getHeader("Authorization"))
        assertEquals("Bearer key-two-abcdefgh", requests.first { it.path!!.startsWith("/p2") }.getHeader("Authorization"))
    }

    @Test fun anUnavailableModelIsSkippedAndTheNextModelAnswers() = runBlocking {
        routes["/p/chat/completions"] = {
            val body = requests.last().body.clone().readUtf8()
            if ("gone-model" in body) MockResponse().setResponseCode(404).setBody("""{"error":{"message":"The model gone-model does not exist"}}""") else chat("ok")
        }
        val e = env(AiGatewaySettings(AiRoutingMode.MANUAL, listOf(provider("p", AiProviderKind.OPENAI_COMPATIBLE, "/p")), RouteChoice("p", "gone-model"), listOf(RouteChoice("p", "live-model"))))
        e.gateway.saveKey("p", "key-abcdefghijk")
        assertEquals("ok", e.gateway.chat(AiConsumer.LAB_AGENT, req()).text)
        assertTrue(e.catalog.isUnavailable("p", "gone-model"))
        assertEquals(ProviderHealthState.HEALTHY, e.gateway.health().single().state)
    }

    @Test fun aTimeoutMovesOn() = runBlocking {
        routes["/slow/chat/completions"] = { chat("late").setBodyDelay(8, TimeUnit.SECONDS) }
        routes["/fast/chat/completions"] = { chat("fast") }
        val e = env(AiGatewaySettings(AiRoutingMode.MANUAL, listOf(provider("slow", AiProviderKind.OPENAI_COMPATIBLE, "/slow", timeout = 5_000), provider("fast", AiProviderKind.OPENAI_COMPATIBLE, "/fast")),
            RouteChoice("slow", "m"), listOf(RouteChoice("fast", "m"))))
        e.gateway.saveKey("slow", "key-abcdefghijk")
        e.gateway.saveKey("fast", "key-abcdefghijk")
        val r = e.gateway.chat(AiConsumer.MAIN_AGENT, req())
        assertEquals("fast", r.text)
        assertEquals(AiErrorKind.TIMEOUT, r.attempts.first().error)
    }

    @Test fun authFailurePausesTheProviderUntilTheKeyChanges() = runBlocking {
        var authorized = false
        routes["/a/chat/completions"] = { if (authorized) chat("hi") else MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}""") }
        val e = env(AiGatewaySettings(AiRoutingMode.MANUAL, listOf(provider("a", AiProviderKind.OPENAI_COMPATIBLE, "/a")), RouteChoice("a", "m")))
        e.gateway.saveKey("a", "key-wrong-abcdef")
        try { e.gateway.chat(AiConsumer.MAIN_AGENT, req()); fail() } catch (x: AiException) { assertEquals(AiErrorKind.AUTH_FAILED, x.error.kind) }
        val sent = requests.size
        now += 24 * 3600_000L
        try { e.gateway.chat(AiConsumer.MAIN_AGENT, req()); fail() } catch (x: AiException) { assertEquals(AiErrorKind.CIRCUIT_OPEN, x.error.kind) }
        assertEquals("no request while paused", sent, requests.size)
        authorized = true
        e.gateway.saveKey("a", "key-right-abcdef")
        assertEquals("hi", e.gateway.chat(AiConsumer.MAIN_AGENT, req()).text)
    }

    @Test fun breakerStatesFollowTheErrorKind() {
        val b = ProviderCircuitBreaker("p", { now })
        b.recordFailure(AiError(AiErrorKind.RATE_LIMITED, "slow down", 429, retryAfterMs = 20_000))
        assertEquals(ProviderCircuitBreaker.State.OPEN, b.state)
        assertEquals(ProviderHealthState.RATE_LIMITED, b.health().state)
        assertFalse(b.allow())
        now += 20_000
        assertTrue("half-open probe after the cooldown", b.allow())
        assertEquals(ProviderCircuitBreaker.State.HALF_OPEN, b.state)
        assertFalse("only one probe at a time", b.allow())
        b.recordFailure(AiError(AiErrorKind.SERVER_ERROR, "boom", 500))
        assertEquals(ProviderCircuitBreaker.State.OPEN, b.state)
        val firstWait = b.retryAt!! - now
        now += firstWait
        assertTrue(b.allow())
        b.recordFailure(AiError(AiErrorKind.SERVER_ERROR, "boom", 500))
        assertTrue("backoff grows", b.retryAt!! - now > firstWait)
        now = b.retryAt!!
        assertTrue(b.allow())
        b.recordSuccess(300)
        assertEquals(ProviderCircuitBreaker.State.CLOSED, b.state)

        val t = ProviderCircuitBreaker("t", { now })
        repeat(2) { t.recordFailure(AiError(AiErrorKind.TIMEOUT, "t")) }
        assertEquals("two timeouts do not open it", ProviderCircuitBreaker.State.CLOSED, t.state)
        assertEquals(ProviderHealthState.DEGRADED, t.health().state)
        t.recordFailure(AiError(AiErrorKind.TIMEOUT, "t"))
        assertEquals(ProviderCircuitBreaker.State.OPEN, t.state)
        assertEquals(ProviderHealthState.UNREACHABLE, t.health().state)

        val q = ProviderCircuitBreaker("q", { now })
        q.recordFailure(AiError(AiErrorKind.QUOTA_EXHAUSTED, "quota"))
        assertEquals(ProviderHealthState.QUOTA_EXHAUSTED, q.health().state)
        assertEquals(now + q.quotaCooldownMs, q.retryAt)

        val m = ProviderCircuitBreaker("m", { now })
        m.recordFailure(AiError(AiErrorKind.MODEL_NOT_FOUND, "x"))
        assertEquals("model errors never trip the provider", ProviderCircuitBreaker.State.CLOSED, m.state)
    }

    @Test fun errorsAreNormalized() {
        val o = GenericOpenAiCompatibleAdapter(AiProviderConfig("c", AiProviderKind.OPENAI_COMPATIBLE, "https://x.example/v1"), { null })
        assertEquals(AiErrorKind.AUTH_FAILED, o.normalizeError(401, "{}", null).kind)
        assertEquals(AiErrorKind.QUOTA_EXHAUSTED, o.normalizeError(429, """{"error":{"type":"insufficient_quota","message":"You exceeded your current quota"}}""", null).kind)
        val rl = o.normalizeError(429, """{"error":{"message":"Rate limit"}}""", null, "12")
        assertEquals(AiErrorKind.RATE_LIMITED, rl.kind)
        assertEquals(12_000L, rl.retryAfterMs)
        assertEquals(AiErrorKind.QUOTA_EXHAUSTED, o.normalizeError(402, "{}", null).kind)
        assertEquals(AiErrorKind.MODEL_NOT_FOUND, o.normalizeError(400, """{"error":{"message":"The model foo does not exist"}}""", null).kind)
        assertEquals(AiErrorKind.BAD_REQUEST, o.normalizeError(400, """{"error":{"message":"bad"}}""", null).kind)
        assertEquals(AiErrorKind.SERVER_ERROR, o.normalizeError(502, "", null).kind)
        assertEquals(AiErrorKind.TIMEOUT, o.normalizeError(null, null, java.net.SocketTimeoutException()).kind)
        assertEquals(AiErrorKind.UNREACHABLE, o.normalizeError(null, null, java.net.UnknownHostException("h")).kind)

        val g = GeminiAdapter(AiProviderConfig("gemini", AiProviderKind.GEMINI), { null })
        assertEquals(AiErrorKind.AUTH_FAILED, g.normalizeError(400, """{"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT"}}""", null).kind)
        val r = g.normalizeError(429, """{"error":{"status":"RESOURCE_EXHAUSTED","message":"quota exceeded","details":[{"retryDelay":"31s"}]}}""", null)
        assertEquals(AiErrorKind.RATE_LIMITED, r.kind)
        assertEquals(31_000L, r.retryAfterMs)
        assertEquals(AiErrorKind.QUOTA_EXHAUSTED, g.normalizeError(429, """{"error":{"status":"RESOURCE_EXHAUSTED","message":"You exceeded your current quota"}}""", null).kind)
        assertEquals(AiErrorKind.MODEL_NOT_FOUND, g.normalizeError(404, """{"error":{"status":"NOT_FOUND","message":"models/x is not found"}}""", null).kind)
    }

    @Test fun malformedAnswersDoNotCrash() = runBlocking {
        routes["/m/chat/completions"] = { MockResponse().setBody("<html>proxy page</html>") }
        val e = env(AiGatewaySettings(AiRoutingMode.MANUAL, listOf(provider("m", AiProviderKind.OPENAI_COMPATIBLE, "/m")), RouteChoice("m", "x")))
        e.gateway.saveKey("m", "key-abcdefghijk")
        try { e.gateway.chat(AiConsumer.MAIN_AGENT, req()); fail() } catch (x: AiException) { assertEquals(AiErrorKind.MALFORMED_RESPONSE, x.error.kind) }
    }

    @Test fun noProviderMeansNotConfiguredAndNoNetwork() = runBlocking {
        val e = env(AiGatewaySettings())
        assertFalse(e.gateway.isConfigured())
        try { e.gateway.chat(AiConsumer.LAB_AGENT, req()); fail() } catch (x: AiException) { assertEquals(AiErrorKind.NOT_CONFIGURED, x.error.kind) }
        assertTrue(requests.isEmpty())
    }

    @Test fun streamingYieldsDeltas() = runBlocking {
        routes["/s/chat/completions"] = { MockResponse().setBody("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\ndata: [DONE]\n\n") }
        val e = env(AiGatewaySettings(AiRoutingMode.MANUAL, listOf(provider("s", AiProviderKind.OPENAI_COMPATIBLE, "/s")), RouteChoice("s", "x")))
        e.gateway.saveKey("s", "key-abcdefghijk")
        assertEquals(listOf("Hel", "lo"), e.gateway.stream(AiConsumer.MAIN_AGENT, req()).toList())
    }

    // ---- Security ----

    @Test fun keysNeverLeaveInBodiesAndPromptsAreRedacted() = runBlocking {
        routes["/k/chat/completions"] = { chat("fine") }
        val e = env(AiGatewaySettings(AiRoutingMode.MANUAL, listOf(provider("k", AiProviderKind.OPENAI_COMPATIBLE, "/k")), RouteChoice("k", "x")))
        val key = "sk-secretsecretsecret9999"
        e.gateway.saveKey("k", key)
        val r = e.gateway.chat(AiConsumer.MAIN_AGENT, req("my server 203.0.113.9 at vless://abc@host.example:443 api_key=sk-zzzzzzzzzzzz"))
        val body = requests.last().body.readUtf8()
        assertFalse(body.contains(key))
        assertFalse(body.contains("203.0.113.9"))
        assertFalse(body.contains("vless://"))
        assertFalse(body.contains("sk-zzzzzzzzzzzz"))
        assertFalse(r.toString().contains(key))
        assertFalse(e.gateway.maskedKey("k")!!.contains("secret"))
        // Nothing the gateway hands to a consumer can carry the key: no such field exists.
        val fields = listOf(AiChatRequest::class, AiChatResponse::class, AiAttempt::class, AiError::class, AiModelDescriptor::class, AiProviderConfig::class, AiGatewaySettings::class)
            .flatMap { k -> k.java.declaredFields.map { "${k.simpleName}.${it.name}".lowercase() } }
        assertTrue(fields.toString(), fields.none { f -> listOf("apikey", "credential", "secret", "token").any { it == f.substringAfter('.') } })
    }

    @Test fun settingsSurviveARoundTripAndBadJson() {
        val s = AiGatewaySettings(AiRoutingMode.AUTO, listOf(AiProviderConfig("openrouter", AiProviderKind.OPENROUTER, customModelId = "vendor/new-model")),
            RouteChoice("openrouter"), listOf(RouteChoice("openrouter", "vendor/other")))
        assertEquals(s, AiGatewaySettings.fromJson(s.toJson()))
        assertEquals(AiGatewaySettings(), AiGatewaySettings.fromJson("{not json"))
        assertFalse(s.toJson().contains("sk-"))
    }
}
