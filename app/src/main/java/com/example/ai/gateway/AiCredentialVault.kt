package com.example.ai.gateway

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's own AI provider keys (BYOK), one per provider, set once and shared by every AI feature.
 *
 * - Keys are stored only through [SecretStore]; on the phone that is AES-256-GCM with an Android Keystore key
 *   ([KeystoreSecretStore]). Nothing is kept in plain SharedPreferences.
 * - The UI only ever gets [masked] text. The raw key leaves the vault through [reader], which only
 *   [ProviderRegistry] calls, to hand it to provider adapters at request time. The boundary test checks this.
 * - Pasted keys are cleaned of the invisible direction marks phone keyboards add, and refused when they still
 *   contain spaces or control characters.
 */
class AiCredentialVault(private val store: SecretStore, private val clock: () -> Long = System::currentTimeMillis) {
    /** Encrypted storage of named secrets. */
    interface SecretStore {
        fun put(name: String, value: String): Boolean
        fun get(name: String): String?
        fun remove(name: String)
    }

    /** Bumped on every change, so paused providers are retried after a new key (see [ProviderCircuitBreaker]). */
    private val _revision = MutableStateFlow(0L)
    val revision: StateFlow<Long> = _revision.asStateFlow()

    sealed class SaveResult {
        data object Saved : SaveResult()
        data class Refused(val reason: String) : SaveResult()
    }

    @Synchronized
    fun save(providerId: String, rawKey: String): SaveResult {
        val key = clean(rawKey)
        formatProblem(key)?.let { return SaveResult.Refused(it) }
        if (!store.put(name(providerId), key)) return SaveResult.Refused("The phone's secure storage refused the key")
        _revision.value = _revision.value + 1
        return SaveResult.Saved
    }

    fun has(providerId: String): Boolean = !store.get(name(providerId)).isNullOrEmpty()

    /** "sk-••••••••7D3A": a short prefix only when the key has a known prefix form, and the last four. */
    fun masked(providerId: String): String? = store.get(name(providerId))?.takeIf { it.isNotEmpty() }?.let(::mask)

    /** Forgets the key on this phone (the provider-side key stays valid until revoked there). */
    @Synchronized
    fun remove(providerId: String) {
        store.remove(name(providerId))
        _revision.value = _revision.value + 1
    }

    /** Local revocation: the key is erased and the time noted, so the screen can say when it was revoked. */
    @Synchronized
    fun revokeLocally(providerId: String): Long {
        remove(providerId)
        val now = clock()
        store.put(name(providerId) + REVOKED_SUFFIX, now.toString())
        return now
    }

    fun revokedAt(providerId: String): Long? = store.get(name(providerId) + REVOKED_SUFFIX)?.toLongOrNull()

    /** For [ProviderRegistry] only. */
    internal fun reader(): CredentialReader = CredentialReader { id -> store.get(name(id))?.takeIf { it.isNotEmpty() } }

    companion object {
        private const val PREFIX = "ai_key_"
        private const val REVOKED_SUFFIX = "_revoked_at"
        const val MIN_LENGTH = 8
        const val MAX_LENGTH = 512

        private fun name(providerId: String): String {
            require(providerId.isNotBlank() && providerId.all { it.isLetterOrDigit() || it in "-_." }) { "Bad provider id" }
            return PREFIX + providerId
        }

        private val INVISIBLE = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2069\\uFEFF\\u00AD]")

        fun clean(raw: String): String = INVISIBLE.replace(raw, "").trim().removePrefix("Bearer ").trim()

        fun formatProblem(key: String): String? = when {
            key.length < MIN_LENGTH -> "The key is too short"
            key.length > MAX_LENGTH -> "The key is too long"
            key.any { it.isWhitespace() || it.code < 0x21 || it.code > 0x7E } -> "The key has spaces or characters keys never contain"
            else -> null
        }

        fun mask(key: String): String {
            val tail = if (key.length >= 16) key.takeLast(4) else key.takeLast(2)
            val prefix = Regex("^(sk-or-|sk-|nvapi-|AIza)").find(key)?.value?.take(3).orEmpty()
            return prefix + "•".repeat(12) + tail
        }
    }
}

/** Keeps secrets in memory; for tests and previews. */
class InMemorySecretStore : AiCredentialVault.SecretStore {
    private val map = java.util.concurrent.ConcurrentHashMap<String, String>()
    override fun put(name: String, value: String): Boolean { map[name] = value; return true }
    override fun get(name: String): String? = map[name]
    override fun remove(name: String) { map.remove(name) }
    /** Exposed for tests that check nothing else was written. */
    fun names(): Set<String> = map.keys.toSet()
}
