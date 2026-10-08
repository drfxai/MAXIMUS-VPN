package com.example.ai.gateway

import android.content.Context
import com.example.data.security.SecureStorage

/**
 * The vault's storage on the phone: each value AES-256-GCM encrypted with the app's Android Keystore key
 * ([SecureStorage]) before it reaches SharedPreferences. A value that fails to decrypt reads as absent.
 */
class KeystoreSecretStore(context: Context) : AiCredentialVault.SecretStore {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("maximus_ai_vault", Context.MODE_PRIVATE)

    override fun put(name: String, value: String): Boolean {
        val encrypted = SecureStorage.encrypt(value, appContext)
        if (encrypted.isEmpty()) return false
        return prefs.edit().putString(name, encrypted).commit()
    }

    override fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return SecureStorage.decrypt(stored, appContext).ifEmpty { null }
    }

    override fun remove(name: String) {
        prefs.edit().remove(name).apply()
    }

    companion object {
        /**
         * Moves the Gemini key the AI Agent saved before the gateway existed (encrypted, in
         * maximus_ai_agent_prefs) into the vault once, then deletes the old copy.
         */
        fun migrateLegacyGeminiKey(context: Context, vault: AiCredentialVault) {
            val old = context.getSharedPreferences("maximus_ai_agent_prefs", Context.MODE_PRIVATE)
            val stored = old.getString("gemini_api_key", null) ?: return
            val key = if (stored.isBlank()) "" else SecureStorage.decryptOrPlaintext(stored, context)
            if (key.isNotBlank() && !vault.has(AiProviderKind.GEMINI.id)) vault.save(AiProviderKind.GEMINI.id, key)
            old.edit().remove("gemini_api_key").apply()
        }
    }
}
