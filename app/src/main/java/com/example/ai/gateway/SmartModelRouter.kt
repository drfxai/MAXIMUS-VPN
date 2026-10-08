package com.example.ai.gateway

/** One provider/model to try, with why it was chosen. */
data class Route(val providerId: String, val modelId: String, val reason: String, val score: Double = 0.0)

/**
 * Turns the user's routing chain into an ordered list of provider/model routes for one task.
 *
 * - MANUAL: exactly the user's picks, in chain order (an "Auto" pick behaves as in AUTO for that provider).
 * - AUTO: for each provider in chain order, its own documented router if it has one (OpenRouter Auto, a 9Router
 *   combo the user picked), otherwise its best capable model for the task.
 * - SMART: up to two capable models per provider, ranked across the whole chain by task fit, provider health,
 *   recent reliability, latency and cost, with the user's chain order as a tie-breaking preference.
 *
 * Models are picked by required capability (vision, context, speed, reasoning), never by brand. A model that
 * cannot meet a hard requirement (an image with a model not known to see) is never routed. Paused providers
 * are kept at the end so the attempt log shows them as skipped.
 */
class SmartModelRouter(
    private val catalog: ModelCatalog,
    private val breaker: (String) -> ProviderCircuitBreaker
) {
    fun routes(settings: AiGatewaySettings, task: AiTaskClass, hasImage: Boolean = false): List<Route> {
        val chain = settings.chain()
        val out = mutableListOf<Route>()
        chain.forEachIndexed { position, choice ->
            val provider = settings.provider(choice.providerId) ?: return@forEachIndexed
            val preference = 1.0 - position * 0.15
            if (!choice.isAuto) {
                val modelId = choice.modelId!!
                if (catalog.isUnavailable(provider.id, modelId)) return@forEachIndexed
                val known = catalog.models(provider.id).firstOrNull { it.modelId == modelId }
                    ?: AiModelDescriptor(provider.id, modelId, source = ModelSource.CUSTOM, metadata = MetadataSource.UNKNOWN)
                if (settings.mode != AiRoutingMode.MANUAL && !capable(known, task, hasImage)) return@forEachIndexed
                if (settings.mode == AiRoutingMode.MANUAL && hasImage && known.supportsVision == false) return@forEachIndexed
                out += Route(provider.id, modelId, "chosen by you", score(known, task, provider.id, preference, settings.mode))
                return@forEachIndexed
            }
            val candidates = candidates(provider, task, hasImage)
            if (candidates.isEmpty()) return@forEachIndexed
            val take = if (settings.mode == AiRoutingMode.SMART) 2 else 1
            val ranked = if (settings.mode == AiRoutingMode.SMART) {
                candidates.sortedByDescending { score(it, task, provider.id, preference, settings.mode) }
            } else {
                candidates.sortedWith(compareByDescending<AiModelDescriptor> { it.source == ModelSource.PROVIDER_ROUTER }
                    .thenByDescending { fit(it, task) }.thenBy { it.modelId })
            }
            ranked.take(take).forEach { m ->
                val why = when (m.source) {
                    ModelSource.PROVIDER_ROUTER -> "${provider.kind.displayName}'s own routing"
                    ModelSource.CUSTOM -> "your custom model"
                    else -> "best fit for ${task.name.lowercase().replace('_', ' ')}"
                }
                out += Route(provider.id, m.modelId, why, score(m, task, provider.id, preference, settings.mode))
            }
        }
        val routes = if (settings.mode == AiRoutingMode.SMART) out.sortedByDescending { it.score } else out
        val (open, closed) = routes.partition { breaker(it.providerId).state == ProviderCircuitBreaker.State.OPEN }
        return (closed + open).distinctBy { it.providerId to it.modelId }
    }

    private fun candidates(provider: AiProviderConfig, task: AiTaskClass, hasImage: Boolean): List<AiModelDescriptor> {
        val discovered = catalog.models(provider.id).filter { !catalog.isUnavailable(provider.id, it.modelId) }
        val custom = provider.customModelId?.let {
            discovered.firstOrNull { d -> d.modelId == it } ?: AiModelDescriptor(provider.id, it, source = ModelSource.CUSTOM, metadata = MetadataSource.UNKNOWN)
        }
        // Before the first refresh, OpenRouter's documented router still works.
        val routers = if (discovered.isEmpty() && provider.kind == AiProviderKind.OPENROUTER)
            listOf(AiModelDescriptor(provider.id, OpenRouterAdapter.AUTO_MODEL, "OpenRouter Auto", source = ModelSource.PROVIDER_ROUTER)) else emptyList()
        return (listOfNotNull(custom) + routers + discovered).distinctBy { it.modelId }.filter { capable(it, task, hasImage) }
    }

    /** Hard requirements only. Unknown vision counts as "cannot see"; a provider's own router decides for itself. */
    fun capable(m: AiModelDescriptor, task: AiTaskClass, hasImage: Boolean): Boolean {
        if ((hasImage || task.needsVision) && m.supportsVision != true && m.source != ModelSource.PROVIDER_ROUTER) return false
        val ctx = m.contextWindow
        if (ctx != null && task.minContext > 0 && ctx < task.minContext) return false
        return true
    }

    /** 0..1: how well a capable model suits the task. */
    fun fit(m: AiModelDescriptor, task: AiTaskClass): Double {
        var s = 0.5
        if (task.prefersSpeed) s += when (m.relativeSpeed) { RelativeLevel.HIGH -> 0.3; RelativeLevel.LOW -> -0.2; else -> 0.0 }
        if (task.prefersReasoning) {
            if (m.supportsReasoning == true) s += 0.25
            if (m.relativeSpeed == RelativeLevel.HIGH) s -= 0.05
        }
        if (!task.prefersSpeed && !task.prefersReasoning && m.relativeSpeed == RelativeLevel.HIGH) s += 0.1
        s += when (m.relativeCost) { RelativeLevel.LOW -> 0.05; RelativeLevel.HIGH -> -0.05; else -> 0.0 }
        if (m.metadata == MetadataSource.DECLARED) s += 0.03
        if (m.source == ModelSource.PROVIDER_ROUTER) s += 0.1
        return s.coerceIn(0.0, 1.0)
    }

    private fun score(m: AiModelDescriptor, task: AiTaskClass, providerId: String, preference: Double, mode: AiRoutingMode): Double {
        if (mode != AiRoutingMode.SMART) return preference
        val b = breaker(providerId)
        val health = b.health()
        val healthFactor = when (health.state) {
            ProviderHealthState.HEALTHY -> 1.0
            ProviderHealthState.DEGRADED -> 0.6
            else -> 0.0
        }
        val latency = health.recentLatencyMs?.let { (1.0 - it / 30_000.0).coerceIn(0.0, 1.0) } ?: 0.5
        return 0.40 * fit(m, task) + 0.20 * healthFactor + 0.15 * b.reliability() + 0.10 * latency + 0.15 * preference
    }
}

/** Picks a task class when a consumer does not name one. Deterministic: no model is asked. */
object TaskClassifier {
    private val CODE = Regex("(?i)(stack ?trace|exception|kotlin|java|python|json|yaml|config(uration)? file|```)")
    private val NETWORK = Regex("(?i)(dns|tls|handshake|latency|packet|ipv6|ipv4|fragment|ech|sni|timeout|vless|xray|tunnel|route)")

    fun classify(text: String, hasImage: Boolean): AiTaskClass = when {
        hasImage -> AiTaskClass.MULTIMODAL_ANALYSIS
        CODE.containsMatchIn(text) -> AiTaskClass.CODE_REASONING
        NETWORK.containsMatchIn(text) -> AiTaskClass.NETWORK_ANALYSIS
        else -> AiTaskClass.GENERAL_CHAT
    }
}
