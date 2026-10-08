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
 * Google Gemini (Generative Language API v1beta). Its own format: `models` list with token limits and
 * generation methods, `:generateContent` and `:streamGenerateContent?alt=sse`, key in the `x-goog-api-key`
 * header (never in the URL, which could end up in logs).
 */
class GeminiAdapter(
    override val config: AiProviderConfig,
    private val credentials: CredentialReader,
    baseClient: OkHttpClient = ProviderHttp.sharedClient,
    private val clock: () -> Long = System::currentTimeMillis
) : AiProviderAdapter {
    private val client = ProviderHttp.client(baseClient, config.timeoutMs)
    private val base get() = EndpointPolicy.normalize(config.endpoint)

    override fun getCapabilityMetadata() = ProviderCapabilities(modelDiscovery = true, streaming = true, nativeRouting = false, requiresEndpoint = false)

    private fun headers(): Map<String, String> {
        EndpointPolicy.problem(config.endpoint)?.let { throw AiException(AiError(AiErrorKind.NOT_CONFIGURED, it)) }
        val key = credentials.read(config.id) ?: throw AiException(AiError(AiErrorKind.NOT_CONFIGURED, "No Gemini API key saved"))
        return mapOf("x-goog-api-key" to key, "Accept" to "application/json")
    }

    override suspend fun validateCredentials() {
        ProviderHttp.execute(client, ProviderHttp.get("$base/models?pageSize=1", headers()), ::normalizeError)
    }

    override suspend fun discoverModels(): List<AiModelDescriptor> {
        val now = clock()
        val out = mutableListOf<AiModelDescriptor>()
        var pageToken: String? = null
        var pages = 0
        do {
            val url = "$base/models?pageSize=1000" + (pageToken?.let { "&pageToken=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
            val text = ProviderHttp.execute(client, ProviderHttp.get(url, headers()), ::normalizeError)
            val json = runCatching { JSONObject(text) }.getOrNull() ?: throw AiException(AiError(AiErrorKind.MALFORMED_RESPONSE, "The model list was not readable"))
            val models = json.optJSONArray("models") ?: JSONArray()
            for (i in 0 until models.length()) {
                val m = models.optJSONObject(i) ?: continue
                val methods = m.optJSONArray("supportedGenerationMethods")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                if ("generateContent" !in methods) continue
                val id = m.optString("name").removePrefix("models/")
                if (id.isBlank() || !ModelHeuristics.isChatModel(id)) continue
                out += ModelHeuristics.describe(config.id, id, m.optString("displayName").ifBlank { id }, now,
                    m.optInt("inputTokenLimit", 0).takeIf { it > 0 }).copy(
                    supportsStreaming = "streamGenerateContent" in methods,
                    supportsReasoning = if (m.optBoolean("thinking", false)) true else ModelHeuristics.reasoning(id),
                    // Gemini chat models take images; Gemma models listed here do not.
                    supportsVision = if (id.startsWith("gemini")) true else null,
                    supportsStructuredOutput = if (id.startsWith("gemini")) true else null,
                    metadata = MetadataSource.DECLARED
                )
            }
            pageToken = json.optString("nextPageToken").ifBlank { null }
        } while (pageToken != null && ++pages < 5)
        return out.distinctBy { it.modelId }.sortedBy { it.modelId }
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

    fun body(request: AiChatRequest): String {
        val contents = JSONArray()
        request.messages.forEach { m ->
            val parts = JSONArray()
            if (m.text.isNotBlank()) parts.put(JSONObject().put("text", m.text))
            m.image?.let { parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", it.mimeType).put("data", it.base64))) }
            if (parts.length() > 0) contents.put(JSONObject().put("role", if (m.role == AiMessage.Role.USER) "user" else "model").put("parts", parts))
        }
        return JSONObject()
            .put("contents", contents)
            .apply {
                request.system?.takeIf { it.isNotBlank() }?.let { put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", it)))) }
            }
            .put("generationConfig", JSONObject()
                .put("temperature", request.temperature)
                .put("maxOutputTokens", request.maxOutputTokens)
                .apply { if (request.jsonOutput) put("responseMimeType", "application/json") })
            .toString()
    }

    private fun path(modelId: String): String {
        require(modelId.isNotBlank() && modelId.all { it.isLetterOrDigit() || it in "-._" }) { "Bad model id" }
        return "$base/models/$modelId"
    }

    override suspend fun sendChat(modelId: String, request: AiChatRequest): AiChatResponse {
        val started = clock()
        val text = ProviderHttp.execute(client, ProviderHttp.post("${path(modelId)}:generateContent", body(request), headers()), ::normalizeError)
        return parse(text, modelId, clock() - started)
    }

    fun parse(text: String, modelId: String, latencyMs: Long): AiChatResponse {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: throw AiException(AiError(AiErrorKind.MALFORMED_RESPONSE, "The answer was not JSON"))
        json.optJSONObject("error")?.let { throw AiException(normalizeError(it.optInt("code", 400), text, null)) }
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
        val parts = candidate?.optJSONObject("content")?.optJSONArray("parts")
        val answer = parts?.let { p -> (0 until p.length()).mapNotNull { p.optJSONObject(it)?.optString("text")?.takeIf { t -> t.isNotEmpty() } }.joinToString("") }.orEmpty()
        if (answer.isBlank()) {
            val reason = candidate?.optString("finishReason").orEmpty().ifBlank { json.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty() }
            throw AiException(AiError(AiErrorKind.MALFORMED_RESPONSE, "Gemini returned no text" + if (reason.isNotBlank()) " ($reason)" else ""))
        }
        val usage = json.optJSONObject("usageMetadata")
        return AiChatResponse(answer, config.id, modelId, usage?.optInt("promptTokenCount") ?: 0, usage?.optInt("candidatesTokenCount") ?: 0, latencyMs)
    }

    override fun streamChat(modelId: String, request: AiChatRequest): Flow<String> = flow {
        val call = client.newCall(ProviderHttp.post("${path(modelId)}:streamGenerateContent?alt=sse", body(request), headers()))
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) ProviderHttp.bodyOrThrow(response, ::normalizeError)
                val source = response.body?.source() ?: throw AiException(AiError(AiErrorKind.MALFORMED_RESPONSE, "Empty stream"))
                while (true) {
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue
                    val chunk = runCatching {
                        val p = JSONObject(line.removePrefix("data:").trim()).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                        p?.let { (0 until it.length()).joinToString("") { i -> it.optJSONObject(i)?.optString("text").orEmpty() } }
                    }.getOrNull()
                    if (!chunk.isNullOrEmpty()) emit(chunk)
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
        if (httpStatus == null) return AiError(ProviderHttp.transportKind(cause), ProviderHttp.safe(cause?.javaClass?.simpleName))
        val err = runCatching { JSONObject(body.orEmpty()).optJSONObject("error") }.getOrNull()
        val message = err?.optString("message").orEmpty().ifBlank { body.orEmpty() }
        val status = err?.optString("status").orEmpty()
        val retry = ProviderHttp.retryAfterMs(retryAfterHeader) ?: retryDelay(err)
        val kind = when {
            message.contains("API_KEY_INVALID", true) || message.contains("API key not valid", true) || message.contains("API key expired", true) ||
                httpStatus == 401 || httpStatus == 403 || status == "PERMISSION_DENIED" || status == "UNAUTHENTICATED" -> AiErrorKind.AUTH_FAILED
            httpStatus == 429 || status == "RESOURCE_EXHAUSTED" ->
                if (Regex("(?i)quota|billing|exceeded your current").containsMatchIn(message) && retry == null) AiErrorKind.QUOTA_EXHAUSTED else AiErrorKind.RATE_LIMITED
            httpStatus == 404 || status == "NOT_FOUND" -> AiErrorKind.MODEL_NOT_FOUND
            httpStatus == 504 || status == "DEADLINE_EXCEEDED" -> AiErrorKind.TIMEOUT
            httpStatus in 500..599 -> AiErrorKind.SERVER_ERROR
            httpStatus in 400..499 -> AiErrorKind.BAD_REQUEST
            else -> AiErrorKind.UNKNOWN
        }
        return AiError(kind, ProviderHttp.safe(message), httpStatus, retry)
    }

    /** Gemini states its wait in error.details[].retryDelay ("30s"). */
    private fun retryDelay(err: JSONObject?): Long? {
        val details = err?.optJSONArray("details") ?: return null
        for (i in 0 until details.length()) {
            val d = details.optJSONObject(i)?.optString("retryDelay").orEmpty()
            d.removeSuffix("s").toDoubleOrNull()?.let { return (it * 1000).toLong() }
        }
        return null
    }
}
