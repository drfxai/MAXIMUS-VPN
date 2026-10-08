package com.example.ui.ai

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ai.gateway.AiCredentialVault
import com.example.ai.gateway.AiException
import com.example.ai.gateway.AiGatewayHolder
import com.example.ai.gateway.AiProviderConfig
import com.example.ai.gateway.AiProviderKind
import com.example.ai.gateway.AiRoutingMode
import com.example.ai.gateway.EndpointPolicy
import com.example.ai.gateway.MaximusAiGateway
import com.example.ai.gateway.RouteChoice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The AI settings screens: keys go straight into the vault; only masked keys come back out. */
class AiSettingsViewModel(private val gateway: MaximusAiGateway = AiGatewayHolder.get()) : ViewModel() {
    private val _state = MutableStateFlow(AiSettingsUiState())
    val state: StateFlow<AiSettingsUiState> = _state.asStateFlow()

    init { refresh() }

    fun refresh() {
        val settings = gateway.settings.value
        val health = gateway.health().associateBy { it.providerId }
        _state.value = _state.value.copy(settings = settings, providers = settings.providers.map { p ->
            val models = gateway.models(p.id)
            AiProviderUi(p, gateway.maskedKey(p.id), health[p.id], models, models.maxOfOrNull { it.discoveredAt }?.takeIf { it > 0 })
        })
    }

    private fun message(m: String?) { _state.value = _state.value.copy(message = m) }

    fun manage(show: Boolean) { _state.value = _state.value.copy(manage = show); refresh() }
    fun open(id: String?) { _state.value = _state.value.copy(open = id) }
    fun dismissMessage() = message(null)

    fun setMode(mode: AiRoutingMode) { gateway.updateSettings { it.copy(mode = mode) }; refresh() }

    fun addProvider(kind: AiProviderKind) {
        val taken = gateway.settings.value.providers.map { it.id }.toSet()
        val id = generateSequence(1) { it + 1 }.map { if (it == 1) kind.id else "${kind.id}-$it" }.first { it !in taken }
        val endpoint = if (kind == AiProviderKind.NINE_ROUTER) "http://localhost:20128/v1" else kind.defaultEndpoint.orEmpty()
        gateway.updateSettings { it.withProvider(AiProviderConfig(id, kind, endpoint)) }
        _state.value = _state.value.copy(open = id)
        refresh()
    }

    fun removeProvider(id: String) {
        gateway.removeKey(id)
        gateway.updateSettings { it.withoutProvider(id) }
        _state.value = _state.value.copy(open = null)
        refresh()
    }

    fun saveKey(id: String, key: String) {
        when (val r = gateway.saveKey(id, key)) {
            is AiCredentialVault.SaveResult.Refused -> message(r.reason)
            else -> { message("Key saved. Testing it now…"); test(id) }
        }
        refresh()
    }

    fun removeKey(id: String) { gateway.removeKey(id); message("Key removed from this phone."); refresh() }

    fun test(id: String) = busy("Testing") {
        val r = gateway.testConnection(id)
        message(if (r.ok) "${r.message} (${r.latencyMs} ms)" else r.message)
    }

    fun refreshModels(id: String) = busy("Refreshing") {
        val n = try { gateway.refreshModels(id).size } catch (e: AiException) { message(e.error.userMessage()); return@busy }
        message("$n models found.")
    }

    private fun busy(what: String, block: suspend () -> Unit) {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = what)
        viewModelScope.launch {
            try { block() } finally { _state.value = _state.value.copy(busy = null); refresh() }
        }
    }

    fun setPrimary(choice: RouteChoice) {
        gateway.updateSettings { s -> s.copy(primary = choice, fallbacks = s.fallbacks.filter { it != choice }) }
        refresh()
    }

    fun addFallback(choice: RouteChoice) {
        gateway.updateSettings { s -> if (choice == s.primary || choice in s.fallbacks) s else s.copy(fallbacks = (s.fallbacks + choice).take(AiGatewayLimits.FALLBACKS)) }
        refresh()
    }

    fun removeFallback(i: Int) { gateway.updateSettings { s -> s.copy(fallbacks = s.fallbacks.filterIndexed { j, _ -> j != i }) }; refresh() }

    fun moveFallbackUp(i: Int) {
        gateway.updateSettings { s ->
            if (i !in 1 until s.fallbacks.size) s else s.copy(fallbacks = s.fallbacks.toMutableList().also { l -> l.add(i - 1, l.removeAt(i)) })
        }
        refresh()
    }

    private fun updateProvider(id: String, change: (AiProviderConfig) -> AiProviderConfig) {
        gateway.updateSettings { s -> s.provider(id)?.let { s.withProvider(change(it)).copy(primary = s.primary) } ?: s }
        refresh()
    }

    fun setEndpoint(id: String, endpoint: String) {
        EndpointPolicy.problem(endpoint)?.let { message(it); return }
        updateProvider(id) { it.copy(endpoint = endpoint) }
        message("Endpoint saved. Tap Test to check it.")
    }

    fun setTimeout(id: String, ms: Long) = updateProvider(id) { it.copy(timeoutMs = ms.coerceIn(AiProviderConfig.MIN_TIMEOUT_MS, AiProviderConfig.MAX_TIMEOUT_MS)) }

    fun setCustomModel(id: String, model: String) {
        updateProvider(id) { it.copy(customModelId = model) }
        setPrimary(RouteChoice(id, model))
        message("Using the model ID $model. If the provider does not know it, Maximus falls back.")
    }
}

private object AiGatewayLimits { const val FALLBACKS = 4 }

@Composable
fun AiSettingsScreen(onNavigateBack: () -> Unit, viewModel: AiSettingsViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()
    BackHandler { if (state.manage) viewModel.manage(false) else onNavigateBack() }
    LaunchedEffect(Unit) { viewModel.refresh() }
    AiSettingsContent(
        state = state,
        actions = remember(viewModel) {
            AiSettingsActions(
                onBack = onNavigateBack, onManage = viewModel::manage, onOpen = viewModel::open, onMode = viewModel::setMode,
                onAddProvider = viewModel::addProvider, onRemoveProvider = viewModel::removeProvider, onSaveKey = viewModel::saveKey,
                onRemoveKey = viewModel::removeKey, onTest = { viewModel.test(it) }, onRefreshModels = { viewModel.refreshModels(it) },
                onPrimary = viewModel::setPrimary, onAddFallback = viewModel::addFallback, onRemoveFallback = viewModel::removeFallback,
                onMoveFallbackUp = viewModel::moveFallbackUp, onEndpoint = viewModel::setEndpoint, onTimeout = viewModel::setTimeout,
                onCustomModel = viewModel::setCustomModel, onDismissMessage = viewModel::dismissMessage
            )
        },
        relativeTime = ::relative
    )
}

internal fun relative(time: Long): String =
    if (System.currentTimeMillis() - time < DateUtils.MINUTE_IN_MILLIS) "just now"
    else DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
