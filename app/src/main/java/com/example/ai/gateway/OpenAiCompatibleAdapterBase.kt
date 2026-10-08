package com.example.ai.gateway

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * The OpenAI chat-completions wire format (`GET /models`, `POST /chat/completions`, SSE streaming), shared by
 * providers that document it: OpenAI, OpenRouter, NVIDIA NIM, 9Router and any compatible endpoint. Provider
 * differences (extra headers, model filters, richer model metadata, native routers) are overridable hooks.
 */
abstract class OpenAiCompatibleAdapterBase(
    final override val config: AiProviderConfig,
    private val credentials: CredentialReader,
    baseClient: OkHttpClient = ProviderHttp.sharedClient,
    protected val clock: () -> Long = System::currentTimeMillis
) : AiProviderAdapter {
    protected val client: OkHttpClient = ProviderHttp.client(baseClient, config.timeoutMs)

    protected val base: String get() = EndpointPolicy.normalize(config.endpoint)

    /** Extra headers the provider documents (never a credential). */
    protected open fun extraHeaders(): Map<String, String> = emptyMap()

    /** Whether a listed model id is a chat model worth offering. */
    protected open fun keepModel(modelId: String, json: JSONObject): Boolean = ModelHeuristics.isChatModel(modelId)

    /** A descriptor from one entry of the provider's model list. */
    protected open fun describe(json: JSONObject, now: Long): AiModelDescriptor {
        val id = json.getString("id")
        val ctx = json.optInt("context_length", json.optInt("context_window", 0)).takeIf { it > 0 }
        return ModelHeuristics.describe(config.id, id, json.optString("name").ifBlank { id }, now, ctx)
    }

    /** Models the provider routes itself (documented by the provider), offered ahead of discovered ones. */
    protected open fun nativeRouters(discovered: List<AiModelDescriptor>): List<AiModelDescriptor> = emptyList()

    override fun getCapabilityMetadata() = ProviderCapabilities(
        modelDiscovery = true, streaming = true, nativeRouting = false, requiresEndpoint = config.kind.requiresEndpoint
    )

    private fun headers(): Map<String, String> {
        EndpointPolicy.problem(config.endpoint)?.let { throw AiException(AiError(AiErrorKind.NOT_CONFIGURED, it)) }
        val key = credentials.read(config.id) ?: throw AiException(AiError(AiErrorKind.NOT_CONFIGURED, "No API key saved for ${config.kind.displayName}"))
        return extraHeaders() + mapOf("Authorization" to "Bearer $key", "Accept" to "application/json")
    }

    override suspend fun validateCredentials() {
        ProviderHttp.execute(client, ProviderHttp.get("$base/models", headers()), ::normalizeError)
    }

    override suspend fun discoverModels(): List<AiModelDescriptor> {
        val text = ProviderHttp.execute(client, ProviderHttp.get("$base/models", headers()), ::normalizeError)
        val now = clock()
        val data = runCatching { JSONObject(text).getJSONArray("data") }.getOrElse {
            throw AiException(AiError(AiErrorKind.MALFORMED_RESPONSE, "The model list was not readable"))
        }
        val found = (0 until data.length()).mapNotNull { i ->
            val o = data.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            if (!keepModel(id, o)) null else describe(o, now)
        }.distinctBy { it.modelId }.sortedBy { it.modelId }
        val routers = nativeRouters(found)
        return routers + found.filter { d -> routers.none { it.modelId == d.modelId } }
    }

    override suspend fun getProviderHealth(): ProviderHealth {
        val started = clock()
        return try {
            validateCredentials()
            ProviderHealth(config.id, ProviderHealthState.HEALTHY, recentLatencyMs = clock() - started, lastSuccessAt = clock())
        } catch (e: AiException) {
            ProviderHealth(config.id, ProviderCircuitBreaker.healthStateOf(e.error.kind), recentErrors = 1, failureStreak = 1, lastFailureAt = clock())
        }
    }

    protected fun body(modelId: String, request: AiChatRequest, stream: Boolean): String {
        val messages = JSONArray()
        request.system?.takeIf { it.isNotBlank() }?.let { messages.put(JSONObject().put("role", "system").put("content", it)) }
        request.messages.forEach { m ->
            val role = if (m.role == AiMessage.Role.USER) "user" else "assistant"
            val content: Any = if (m.image == null) m.text else JSONArray()
                .put(JSONObject().put("type", "text").put("text", m.text))
                .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:${m.image.mimeType};base64,${m.image.base64}")))
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        return JSONObject()
            .put("model", modelId)
            .put("messages", messages)
            .put("temperature", request.temperature)
            .put("max_tokens", request.maxOutputTokens)
            .put("stream", stream)
            .apply { if (request.jsonOutput) put("response_format", JSONObject().put("type", "json_object")) }
            .toString()
    }

    override suspend fun sendChat(modelId: String, request: AiChatRequest): AiChatResponse {
        val started = clock()
        val text = ProviderHttp.execute(client, ProviderHttp.post("$base/chat/completions", body(modelId, request, false), headers()), ::normalizeError)
        return parseChat(text, modelId, clock() - started)
    }

    fun parseChat(text: String, requestedModel: String, latencyMs: Long): AiChatResponse {
        val json = runCatching { JSONObject(text) }.getOrNull()
            ?: throw AiException(AiError(AiErrorKind.MALFORMED_RESPONSE, "The answer was not JSON"))
        json.optJSONObject("error")?.let { throw AiException(normalizeError(json.optInt("code", 0).takeIf { it > 0 }, text, null)) }
        val message = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw AiException(AiError(AiErrorKind.MALFORMED_RESPONSE, "The answer had no choices"))
        val content = when (val c = message.opt("content")) {
            is String -> c
            is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text").orEmpty() }
            else -> ""
        }
        if (content.isBlank()) throw AiException(AiError(AiErrorKind.MALFORMED_RESPONSE, "The answer was empty"))
        val usage = json.optJSONObject("usage")
        return AiChatResponse(
            text = content, providerId = config.id, modelId = json.optString("model").ifBlank { requestedModel },
            promptTokens = usage?.optInt("prompt_tokens") ?: 0, outputTokens = usage?.optInt("completion_tokens") ?: 0,
            latencyMs = latencyMs
        )
    }

    override fun streamChat(modelId: String, request: AiChatRequest): Flow<String> = flow {
        val call = client.newCall(ProviderHttp.post("$base/chat/completions", body(modelId, request, true), headers() + ("Accept" to "text/event-stream")))
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) ProviderHttp.bodyOrThrow(response, ::normalizeError)
                val source = response.body?.source() ?: throw AiException(AiError(AiErrorKind.MALFORMED_RESPONSE, "Empty stream"))
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val delta = runCatching {
                        JSONObject(data).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content")
                    }.getOrNull()
                    if (!delta.isNullOrEmpty()) emit(delta)
                }
            }
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: IOException) {
            throw AiException(normalizeError(null, null, e))
        }
    }.flowOn(Dispatchers.IO)

    override fun normalizeError(httpStatus: Int?, body: String?, cause: Throwable?, retryAfterHeader: String?): AiError {
        if (httpStatus == null) {
            val kind = ProviderHttp.transportKind(cause)
            return AiError(kind, ProviderHttp.safe(cause?.javaClass?.simpleName))
        }
        val obj = runCatching { JSONObject(body.orEmpty()).optJSONObject("error") }.getOrNull()
        val message = obj?.optString("message").orEmpty().ifBlank { body.orEmpty() }
        val code = (obj?.opt("code")?.toString().orEmpty() + " " + obj?.optString("type").orEmpty()).lowercase()
        val retry = ProviderHttp.retryAfterMs(retryAfterHeader)
        val kind = when {
            httpStatus == 401 || httpStatus == 403 -> AiErrorKind.AUTH_FAILED
            httpStatus == 402 -> AiErrorKind.QUOTA_EXHAUSTED
            httpStatus == 429 && ("quota" in code || "insufficient" in code || message.contains("quota", true)) -> AiErrorKind.QUOTA_EXHAUSTED
            httpStatus == 429 -> AiErrorKind.RATE_LIMITED
            httpStatus == 404 -> AiErrorKind.MODEL_NOT_FOUND
            httpStatus == 408 || httpStatus == 504 -> AiErrorKind.TIMEOUT
            httpStatus in 500..599 -> AiErrorKind.SERVER_ERROR
            httpStatus == 400 && ("model" in code || Regex("(?i)model .*(not|does not) exist|unknown model|invalid model").containsMatchIn(message)) -> AiErrorKind.MODEL_NOT_FOUND
            httpStatus in 400..499 -> AiErrorKind.BAD_REQUEST
            else -> AiErrorKind.UNKNOWN
        }
        return AiError(kind, ProviderHttp.safe(message), httpStatus, retry)
    }
}

/** OpenAI's own API. */
class OpenAiAdapter(config: AiProviderConfig, credentials: CredentialReader, client: OkHttpClient = ProviderHttp.sharedClient) :
    OpenAiCompatibleAdapterBase(config, credentials, client) {
    override fun keepModel(modelId: String, json: JSONObject): Boolean =
        ModelHeuristics.isChatModel(modelId) && !modelId.startsWith("babbage") && !modelId.startsWith("davinci") && !modelId.contains("instruct")
}

/**
 * OpenRouter. Its model list states context length, input modalities, supported parameters and prices, so
 * those fields are [MetadataSource.DECLARED]. "openrouter/auto" is OpenRouter's own documented router.
 */
class OpenRouterAdapter(config: AiProviderConfig, credentials: CredentialReader, client: OkHttpClient = ProviderHttp.sharedClient) :
    OpenAiCompatibleAdapterBase(config, credentials, client) {
    override fun extraHeaders() = mapOf("X-Title" to "Maximus VPN")

    override fun getCapabilityMetadata() = super.getCapabilityMetadata().copy(nativeRouting = true)

    override fun describe(json: JSONObject, now: Long): AiModelDescriptor {
        val id = json.getString("id")
        val inputs = json.optJSONObject("architecture")?.optJSONArray("input_modalities")?.let { a -> (0 until a.length()).map { a.optString(it) } }
        val params = json.optJSONArray("supported_parameters")?.let { a -> (0 until a.length()).map { a.optString(it) } }
        val prompt = json.optJSONObject("pricing")?.optString("prompt")?.toDoubleOrNull()
        val cost = when {
            prompt == null -> ModelHeuristics.cost(id)
            prompt == 0.0 -> RelativeLevel.LOW
            prompt < 0.000001 -> RelativeLevel.LOW
            prompt < 0.000005 -> RelativeLevel.MEDIUM
            else -> RelativeLevel.HIGH
        }
        return ModelHeuristics.describe(config.id, id, json.optString("name").ifBlank { id }, now,
            json.optInt("context_length", 0).takeIf { it > 0 }).copy(
            supportsVision = inputs?.contains("image") ?: ModelHeuristics.vision(id),
            supportsAudio = inputs?.contains("audio"),
            supportsTools = params?.contains("tools"),
            supportsReasoning = params?.contains("reasoning") ?: ModelHeuristics.reasoning(id),
            supportsStructuredOutput = params?.let { it.contains("structured_outputs") || it.contains("response_format") },
            relativeCost = cost,
            metadata = if (inputs != null || params != null) MetadataSource.DECLARED else MetadataSource.INFERRED
        )
    }

    override fun nativeRouters(discovered: List<AiModelDescriptor>): List<AiModelDescriptor> = listOf(
        (discovered.firstOrNull { it.modelId == AUTO_MODEL } ?: AiModelDescriptor(config.id, AUTO_MODEL, "OpenRouter Auto"))
            .copy(source = ModelSource.PROVIDER_ROUTER, displayName = "OpenRouter Auto")
    )

    companion object { const val AUTO_MODEL = "openrouter/auto" }
}

/** NVIDIA NIM hosted API (integrate.api.nvidia.com), OpenAI format. */
class NvidiaNimAdapter(config: AiProviderConfig, credentials: CredentialReader, client: OkHttpClient = ProviderHttp.sharedClient) :
    OpenAiCompatibleAdapterBase(config, credentials, client)

/**
 * 9Router, a self-hosted router with an OpenAI-compatible API (default http://localhost:20128/v1). Its
 * `/v1/models` lists real models as "provider/model" and the user's combos (named fallback groups) by bare
 * name; sending a combo's name as the model is 9Router's documented Smart Combo routing, so combos are marked
 * [ModelSource.PROVIDER_ROUTER] rather than faked here.
 */
class NineRouterAdapter(config: AiProviderConfig, credentials: CredentialReader, client: OkHttpClient = ProviderHttp.sharedClient) :
    OpenAiCompatibleAdapterBase(config, credentials, client) {
    override fun getCapabilityMetadata() = super.getCapabilityMetadata().copy(nativeRouting = true)

    override fun nativeRouters(discovered: List<AiModelDescriptor>): List<AiModelDescriptor> =
        discovered.filter { !it.modelId.contains('/') }.map { it.copy(source = ModelSource.PROVIDER_ROUTER, displayName = "Combo: ${it.modelId}") }

    companion object { const val LOCAL_ENDPOINT = "http://localhost:20128/v1" }
}

/** Any other endpoint that documents the OpenAI chat-completions format. */
class GenericOpenAiCompatibleAdapter(config: AiProviderConfig, credentials: CredentialReader, client: OkHttpClient = ProviderHttp.sharedClient) :
    OpenAiCompatibleAdapterBase(config, credentials, client)
