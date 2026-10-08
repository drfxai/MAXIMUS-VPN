package com.example.ai.gateway

import com.example.core.AiPrivacyFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentHashMap

/**
 * The one way any Maximus feature talks to an AI model.
 *
 * consumer → [MaximusAiGateway] → task class ([TaskClassifier]) → [SmartModelRouter] → [ProviderRegistry]
 * → provider adapter → provider API.
 *
 * - Advisory only: the gateway returns text. It holds no reference to the VPN, kill switch, recovery,
 *   evidence or security policy, and nothing it returns is executed (enforced by AiBoundaryTest).
 * - No raw key ever passes through here: adapters read their own key from the vault at request time.
 * - Every message is redacted ([AiPrivacyFilter]) before it leaves the phone.
 * - Failover: routes are tried in order, at most [maxAttempts]; a provider whose breaker is open is skipped,
 *   provider-wide errors move to the next provider, model errors move to the next model. Each attempt is
 *   recorded in the response (or the exception) for diagnostics, with no prompt text and no key.
 * - Without any provider set up, [isConfigured] is false and [chat] fails fast with NOT_CONFIGURED; nothing
 *   else in the app depends on the gateway.
 */
class MaximusAiGateway(
    private val settingsStore: AiGatewaySettingsStore,
    private val registry: ProviderRegistry,
    private val vault: AiCredentialVault,
    private val catalog: ModelCatalog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val redact: (String) -> String = AiPrivacyFilter::redact,
    val maxAttempts: Int = 4,
    private val log: (String) -> Unit = {}
) {
    private val breakers = ConcurrentHashMap<String, ProviderCircuitBreaker>()
    private val router = SmartModelRouter(catalog) { breaker(it) }
    @Volatile private var seenRevision = vault.revision.value

    val settings get() = settingsStore.settings

    fun breaker(providerId: String): ProviderCircuitBreaker = breakers.getOrPut(providerId) { ProviderCircuitBreaker(providerId, clock) }

    /** A key changed: providers paused for a refused key get another chance. */
    private fun syncKeys() {
        val rev = vault.revision.value
        if (rev != seenRevision) {
            seenRevision = rev
            breakers.values.forEach { if (it.openedBy == AiErrorKind.AUTH_FAILED || it.openedBy == AiErrorKind.NOT_CONFIGURED) it.reset() }
        }
    }

    /** True when at least one enabled provider in the chain has a saved key. */
    fun isConfigured(): Boolean = settingsStore.settings.value.chain().any { vault.has(it.providerId) }

    fun routes(task: AiTaskClass, hasImage: Boolean = false): List<Route> = router.routes(settingsStore.settings.value, task, hasImage)

    suspend fun chat(consumer: AiConsumer, request: AiChatRequest): AiChatResponse {
        syncKeys()
        val settings = settingsStore.settings.value
        val safe = request.copy(messages = request.messages.map { it.copy(text = redact(it.text)) })
        val routes = router.routes(settings, safe.task, safe.hasImage).filter { vault.has(it.providerId) }
        if (routes.isEmpty()) {
            val why = if (!isConfigured()) "No AI provider with a key is set up" else "No configured model can do ${safe.task.name.lowercase()} (refresh models or pick one)"
            throw AiException(AiError(AiErrorKind.NOT_CONFIGURED, why))
        }
        val attempts = mutableListOf<AiAttempt>()
        val skipProviders = mutableSetOf<String>()
        var last: AiError? = null
        for (route in routes) {
            if (attempts.count { it.outcome != "skipped" } >= maxAttempts) break
            if (route.providerId in skipProviders) continue
            val b = breaker(route.providerId)
            if (!b.allow()) {
                attempts += AiAttempt(route.providerId, route.modelId, "skipped", AiErrorKind.CIRCUIT_OPEN)
                // The reason the breaker opened (AUTH_FAILED, RATE_LIMITED, QUOTA_EXHAUSTED, TIMEOUT...) is a
                // category, never provider text, so it is safe to log.
                last = last ?: AiError(AiErrorKind.CIRCUIT_OPEN, "${route.providerId} is paused after ${b.openedBy ?: "repeated failures"}")
                continue
            }
            val config = settings.provider(route.providerId) ?: continue
            val started = clock()
            try {
                val adapter = registry.adapter(config)
                val response = withTimeout(config.timeoutMs + 2_000) { adapter.sendChat(route.modelId, safe) }
                val latency = clock() - started
                b.recordSuccess(latency)
                attempts += AiAttempt(route.providerId, route.modelId, "ok", latencyMs = latency)
                log("[AI] ${consumer.name} ${safe.task.name} answered by ${route.providerId}/${route.modelId} in $latency ms")
                return response.copy(attempts = attempts.toList(), latencyMs = latency)
            } catch (e: CancellationException) {
                if (e is kotlinx.coroutines.TimeoutCancellationException) {
                    val err = AiError(AiErrorKind.TIMEOUT, "No answer in ${config.timeoutMs} ms")
                    b.recordFailure(err)
                    attempts += AiAttempt(route.providerId, route.modelId, "failed", err.kind, clock() - started)
                    last = err
                    skipProviders += route.providerId
                    continue
                }
                b.recordFailure(AiError(AiErrorKind.CANCELLED, "cancelled"))
                throw e
            } catch (e: AiException) {
                val err = e.error
                b.recordFailure(err)
                attempts += AiAttempt(route.providerId, route.modelId, "failed", err.kind, clock() - started)
                log("[AI] ${route.providerId}/${route.modelId} failed: ${err.kind}")
                last = err
                when {
                    err.kind == AiErrorKind.MODEL_NOT_FOUND -> catalog.markUnavailable(route.providerId, route.modelId)
                    err.kind.providerWide -> skipProviders += route.providerId
                }
            } catch (e: Exception) {
                val err = AiError(AiErrorKind.UNKNOWN, ProviderHttp.safe(e.javaClass.simpleName))
                b.recordFailure(err)
                attempts += AiAttempt(route.providerId, route.modelId, "failed", err.kind, clock() - started)
                last = err
            }
        }
        throw AiException(last ?: AiError(AiErrorKind.UNKNOWN, "No route answered"), attempts)
    }

    /**
     * Streams from the first route that is not paused. Failover happens only before the first chunk; once text
     * has arrived, an error ends the stream (half an answer from one model is not glued to another's).
     */
    fun stream(consumer: AiConsumer, request: AiChatRequest): Flow<String> = flow {
        syncKeys()
        val settings = settingsStore.settings.value
        val safe = request.copy(messages = request.messages.map { it.copy(text = redact(it.text)) })
        val routes = router.routes(settings, safe.task, safe.hasImage).filter { vault.has(it.providerId) }
        if (routes.isEmpty()) throw AiException(AiError(AiErrorKind.NOT_CONFIGURED, "No AI provider with a key is set up"))
        var last: AiError? = null
        for (route in routes.take(maxAttempts)) {
            val b = breaker(route.providerId)
            if (!b.allow()) continue
            val config = settings.provider(route.providerId) ?: continue
            val started = clock()
            var emitted = false
            try {
                registry.adapter(config).streamChat(route.modelId, safe).collect { emitted = true; emit(it) }
                b.recordSuccess(clock() - started)
                log("[AI] ${consumer.name} streamed from ${route.providerId}/${route.modelId}")
                return@flow
            } catch (e: AiException) {
                b.recordFailure(e.error)
                last = e.error
                if (emitted) throw e
                if (e.error.kind == AiErrorKind.MODEL_NOT_FOUND) catalog.markUnavailable(route.providerId, route.modelId)
            }
        }
        throw AiException(last ?: AiError(AiErrorKind.CIRCUIT_OPEN, "Every provider is paused"))
    }

    /** Asks the provider for its current models and stores them; the custom model id is kept separately. */
    suspend fun refreshModels(providerId: String): List<AiModelDescriptor> {
        syncKeys()
        val config = settingsStore.settings.value.provider(providerId) ?: throw AiException(AiError(AiErrorKind.NOT_CONFIGURED, "Unknown provider"))
        val b = breaker(providerId)
        val started = clock()
        return try {
            val models = registry.adapter(config).discoverModels()
            b.recordSuccess(clock() - started)
            catalog.replace(providerId, models)
            models
        } catch (e: AiException) {
            b.recordFailure(e.error)
            throw e
        }
    }

    data class TestResult(val ok: Boolean, val message: String, val latencyMs: Long, val models: Int?)

    /** "Test Connection": checks the key (models list); a refused key pauses the provider until it changes. */
    suspend fun testConnection(providerId: String): TestResult {
        syncKeys()
        val config = settingsStore.settings.value.provider(providerId) ?: return TestResult(false, "Unknown provider", 0, null)
        if (!vault.has(providerId)) return TestResult(false, AiError(AiErrorKind.NOT_CONFIGURED, "").userMessage(), 0, null)
        val b = breaker(providerId)
        b.reset()
        val started = clock()
        return try {
            val models = runCatching { registry.adapter(config).discoverModels() }.getOrElse { e ->
                if (e is AiException && e.error.kind != AiErrorKind.MALFORMED_RESPONSE) throw e
                registry.adapter(config).validateCredentials(); null
            }
            val latency = clock() - started
            b.recordSuccess(latency)
            models?.let { catalog.replace(providerId, it) }
            TestResult(true, "Connected" + (models?.let { " · ${it.size} models" } ?: ""), latency, models?.size)
        } catch (e: AiException) {
            b.recordFailure(e.error)
            TestResult(false, e.error.userMessage(), clock() - started, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TestResult(false, "Test failed: ${ProviderHttp.safe(e.javaClass.simpleName)}", clock() - started, null)
        }
    }

    fun health(): List<ProviderHealth> {
        syncKeys()
        return settingsStore.settings.value.providers.map { p -> breaker(p.id).health(p.enabled, vault.has(p.id)) }
    }

    // ---- Settings-screen operations. Keys go in; only masked text comes out. ----

    fun saveKey(providerId: String, key: String): AiCredentialVault.SaveResult =
        vault.save(providerId, key).also { if (it is AiCredentialVault.SaveResult.Saved) breaker(providerId).reset() }

    fun removeKey(providerId: String) { vault.remove(providerId); breaker(providerId).reset(); catalog.forget(providerId) }

    fun revokeKey(providerId: String) { vault.revokeLocally(providerId); breaker(providerId).reset(); catalog.forget(providerId) }

    fun maskedKey(providerId: String): String? = vault.masked(providerId)

    fun hasKey(providerId: String): Boolean = vault.has(providerId)

    fun models(providerId: String): List<AiModelDescriptor> = catalog.models(providerId)

    fun updateSettings(change: (AiGatewaySettings) -> AiGatewaySettings): AiGatewaySettings {
        val next = settingsStore.update(change)
        next.providers.forEach { p -> EndpointPolicy.problem(p.endpoint)?.let { log("[AI] ${p.id}: $it") } }
        return next
    }
}
