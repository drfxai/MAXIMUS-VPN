package com.example.ai.gateway

import org.json.JSONArray
import org.json.JSONObject

/** One link of the routing chain: a provider and a model, or null for "Auto" (the router picks). */
data class RouteChoice(val providerId: String, val modelId: String? = null) {
    val isAuto: Boolean get() = modelId.isNullOrBlank()
}

/**
 * The user's AI setup, shared by every AI feature: providers (without keys), routing mode, the primary
 * choice and the fallback chain. Persisted as JSON through [AiGatewaySettingsStore].
 */
data class AiGatewaySettings(
    val mode: AiRoutingMode = AiRoutingMode.SMART,
    val providers: List<AiProviderConfig> = emptyList(),
    val primary: RouteChoice? = null,
    val fallbacks: List<RouteChoice> = emptyList()
) {
    /** Primary first, then fallbacks, each provider once per model, only providers that exist and are enabled. */
    fun chain(): List<RouteChoice> {
        val enabled = providers.filter { it.enabled }.map { it.id }.toSet()
        return (listOfNotNull(primary) + fallbacks).filter { it.providerId in enabled }.distinct().take(MAX_CHAIN)
    }

    fun provider(id: String): AiProviderConfig? = providers.firstOrNull { it.id == id }

    fun withProvider(config: AiProviderConfig): AiGatewaySettings {
        val list = providers.filter { it.id != config.id } + config
        val p = primary ?: RouteChoice(config.id)
        return copy(providers = list, primary = p)
    }

    fun withoutProvider(id: String): AiGatewaySettings = copy(
        providers = providers.filter { it.id != id },
        primary = primary?.takeIf { it.providerId != id } ?: fallbacks.firstOrNull { it.providerId != id },
        fallbacks = fallbacks.filter { it.providerId != id }.let { rest -> if (primary?.providerId == id) rest.drop(1) else rest }
    )

    fun toJson(): String = JSONObject()
        .put("v", 1)
        .put("mode", mode.name)
        .put("providers", JSONArray().apply {
            providers.forEach { p ->
                put(JSONObject().put("id", p.id).put("kind", p.kind.name).put("endpoint", p.endpoint).put("enabled", p.enabled)
                    .put("timeoutMs", p.timeoutMs).put("customModelId", p.customModelId ?: JSONObject.NULL))
            }
        })
        .put("primary", primary?.let(::choiceJson) ?: JSONObject.NULL)
        .put("fallbacks", JSONArray().apply { fallbacks.forEach { put(choiceJson(it)) } })
        .toString()

    companion object {
        const val MAX_CHAIN = 5

        private fun choiceJson(c: RouteChoice) = JSONObject().put("provider", c.providerId).put("model", c.modelId ?: JSONObject.NULL)

        private fun choice(o: JSONObject?): RouteChoice? {
            o ?: return null
            val p = o.optString("provider").ifBlank { return null }
            return RouteChoice(p, if (o.isNull("model")) null else o.optString("model").ifBlank { null })
        }

        /** Unreadable or partial settings fall back to defaults field by field; never throws. */
        fun fromJson(text: String?): AiGatewaySettings {
            val o = runCatching { JSONObject(text ?: return AiGatewaySettings()) }.getOrNull() ?: return AiGatewaySettings()
            val providers = o.optJSONArray("providers")?.let { a ->
                (0 until a.length()).mapNotNull { i ->
                    val p = a.optJSONObject(i) ?: return@mapNotNull null
                    val kind = runCatching { AiProviderKind.valueOf(p.optString("kind")) }.getOrNull() ?: return@mapNotNull null
                    runCatching {
                        AiProviderConfig(
                            id = p.optString("id").ifBlank { kind.id }, kind = kind,
                            endpoint = p.optString("endpoint").ifBlank { kind.defaultEndpoint.orEmpty() },
                            enabled = p.optBoolean("enabled", true),
                            timeoutMs = p.optLong("timeoutMs", AiProviderConfig.DEFAULT_TIMEOUT_MS)
                                .coerceIn(AiProviderConfig.MIN_TIMEOUT_MS, AiProviderConfig.MAX_TIMEOUT_MS),
                            customModelId = if (p.isNull("customModelId")) null else p.optString("customModelId").ifBlank { null }
                        )
                    }.getOrNull()
                }
            }.orEmpty().distinctBy { it.id }
            return AiGatewaySettings(
                mode = runCatching { AiRoutingMode.valueOf(o.optString("mode")) }.getOrDefault(AiRoutingMode.SMART),
                providers = providers,
                primary = choice(o.optJSONObject("primary")),
                fallbacks = o.optJSONArray("fallbacks")?.let { a -> (0 until a.length()).mapNotNull { choice(a.optJSONObject(it)) } }.orEmpty()
            )
        }
    }
}

/** Load/save of [AiGatewaySettings]; SharedPreferences on the phone, memory in tests. Holds no keys. */
class AiGatewaySettingsStore(private val load: () -> String?, private val save: (String) -> Unit) {
    private val flow = kotlinx.coroutines.flow.MutableStateFlow(AiGatewaySettings.fromJson(load()))
    val settings: kotlinx.coroutines.flow.StateFlow<AiGatewaySettings> = flow

    @Synchronized
    fun update(change: (AiGatewaySettings) -> AiGatewaySettings): AiGatewaySettings {
        val next = change(flow.value)
        save(next.toJson())
        flow.value = next
        return next
    }
}

/**
 * Discovered models per provider, with when they were listed, and models that recently answered "not found"
 * (skipped for a while instead of retried on every request). Persisted as JSON; holds no keys.
 */
class ModelCatalog(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val models = java.util.concurrent.ConcurrentHashMap<String, List<AiModelDescriptor>>()
    private val unavailable = java.util.concurrent.ConcurrentHashMap<String, Long>()

    init { parse(load()) }

    fun models(providerId: String): List<AiModelDescriptor> = models[providerId].orEmpty()

    fun all(): List<AiModelDescriptor> = models.values.flatten()

    fun discoveredAt(providerId: String): Long? = models[providerId]?.maxOfOrNull { it.discoveredAt }

    @Synchronized
    fun replace(providerId: String, list: List<AiModelDescriptor>) {
        models[providerId] = list.take(MAX_MODELS_PER_PROVIDER)
        unavailable.keys.removeAll { it.startsWith("$providerId/") }
        persist()
    }

    @Synchronized
    fun forget(providerId: String) {
        models.remove(providerId)
        persist()
    }

    fun markUnavailable(providerId: String, modelId: String) { unavailable["$providerId/$modelId"] = clock() }

    fun isUnavailable(providerId: String, modelId: String): Boolean =
        unavailable["$providerId/$modelId"]?.let { clock() - it < UNAVAILABLE_MS } ?: false

    private fun persist() {
        val root = JSONObject()
        models.forEach { (p, list) ->
            root.put(p, JSONArray().apply {
                list.forEach { m ->
                    put(JSONObject().put("id", m.modelId).put("name", m.displayName).put("ctx", m.contextWindow ?: JSONObject.NULL)
                        .put("stream", m.supportsStreaming).put("reason", m.supportsReasoning ?: JSONObject.NULL)
                        .put("vision", m.supportsVision ?: JSONObject.NULL).put("audio", m.supportsAudio ?: JSONObject.NULL)
                        .put("tools", m.supportsTools ?: JSONObject.NULL).put("json", m.supportsStructuredOutput ?: JSONObject.NULL)
                        .put("speed", m.relativeSpeed.name).put("cost", m.relativeCost.name).put("at", m.discoveredAt)
                        .put("src", m.source.name).put("meta", m.metadata.name))
                }
            })
        }
        save(root.toString())
    }

    private fun parse(text: String?) {
        val root = runCatching { JSONObject(text ?: return) }.getOrNull() ?: return
        root.keys().forEach { p ->
            val a = root.optJSONArray(p) ?: return@forEach
            models[p] = (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                fun b(k: String): Boolean? = if (o.isNull(k)) null else o.optBoolean(k)
                AiModelDescriptor(
                    providerId = p, modelId = o.optString("id").ifBlank { return@mapNotNull null }, displayName = o.optString("name"),
                    contextWindow = if (o.isNull("ctx")) null else o.optInt("ctx"), supportsStreaming = o.optBoolean("stream", true),
                    supportsReasoning = b("reason"), supportsVision = b("vision"), supportsAudio = b("audio"), supportsTools = b("tools"),
                    supportsStructuredOutput = b("json"),
                    relativeSpeed = runCatching { RelativeLevel.valueOf(o.optString("speed")) }.getOrDefault(RelativeLevel.UNKNOWN),
                    relativeCost = runCatching { RelativeLevel.valueOf(o.optString("cost")) }.getOrDefault(RelativeLevel.UNKNOWN),
                    discoveredAt = o.optLong("at"),
                    source = runCatching { ModelSource.valueOf(o.optString("src")) }.getOrDefault(ModelSource.DISCOVERED),
                    metadata = runCatching { MetadataSource.valueOf(o.optString("meta")) }.getOrDefault(MetadataSource.INFERRED)
                )
            }
        }
    }

    companion object {
        const val MAX_MODELS_PER_PROVIDER = 400
        const val UNAVAILABLE_MS = 6 * 60 * 60_000L
    }
}
