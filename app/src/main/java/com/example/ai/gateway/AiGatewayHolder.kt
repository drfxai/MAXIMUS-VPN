package com.example.ai.gateway

import android.content.Context
import com.example.RayApplication
import com.example.xray.XrayLogManager

/**
 * The app's single [MaximusAiGateway]: one vault, one settings store and one model catalog for every AI
 * feature (AI Agent, LAB agent, Research agent). Built on first use; the VPN never needs it.
 */
object AiGatewayHolder {
    @Volatile private var instance: MaximusAiGateway? = null

    fun get(context: Context = RayApplication.instance): MaximusAiGateway =
        instance ?: synchronized(this) { instance ?: build(context.applicationContext).also { instance = it } }

    private fun build(context: Context): MaximusAiGateway {
        val vault = AiCredentialVault(KeystoreSecretStore(context))
        val prefs = context.getSharedPreferences("maximus_ai_gateway", Context.MODE_PRIVATE)
        val store = AiGatewaySettingsStore({ prefs.getString("settings_v1", null) }, { prefs.edit().putString("settings_v1", it).apply() })
        val catalog = ModelCatalog({ prefs.getString("models_v1", null) }, { prefs.edit().putString("models_v1", it).apply() })
        migrateFromAiAgent(context, vault, store)
        return MaximusAiGateway(store, ProviderRegistry(vault), vault, catalog, log = { XrayLogManager.i("AI_GATEWAY", it) })
    }

    /**
     * Before the gateway, the AI Agent kept one Gemini key and model. Both move here once: the key into the
     * vault, the model as the primary choice, with Gemini Auto as its fallback.
     */
    private fun migrateFromAiAgent(context: Context, vault: AiCredentialVault, store: AiGatewaySettingsStore) {
        KeystoreSecretStore.migrateLegacyGeminiKey(context, vault)
        if (store.settings.value.providers.isNotEmpty() || !vault.has(AiProviderKind.GEMINI.id)) return
        val oldModel = context.getSharedPreferences("maximus_ai_agent_prefs", Context.MODE_PRIVATE).getString("gemini_model", null)?.ifBlank { null }
        store.update {
            AiGatewaySettings(
                mode = AiRoutingMode.SMART,
                providers = listOf(AiProviderConfig(AiProviderKind.GEMINI.id, AiProviderKind.GEMINI)),
                primary = RouteChoice(AiProviderKind.GEMINI.id, oldModel),
                fallbacks = if (oldModel != null) listOf(RouteChoice(AiProviderKind.GEMINI.id)) else emptyList()
            )
        }
    }
}
