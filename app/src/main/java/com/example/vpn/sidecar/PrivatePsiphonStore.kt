package com.example.vpn.sidecar

import android.content.Context
import com.example.data.security.SecureStorage
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/**
 * Private Psiphon bootstrap material is never stored in VlessProfile.extraSettings or in
 * diagnostics. A profile only contains an opaque reference; the actual upstream server-entry.dat
 * is kept by Android Keystore-backed SecureStorage.
 *
 * This is a client-side capability, NOT proof that a private psiphond installation works.
 */
class PrivatePsiphonStore(context: Context) {
    private val secure = SecureStorage(context.applicationContext)

    @Synchronized
    fun save(serverEntry: String): String {
        val cleaned = serverEntry.trim()
        require(validEntry(cleaned)) { "Invalid psiphond server-entry.dat; expected bounded hexadecimal bootstrap entry." }
        val id = UUID.randomUUID().toString()
        val value = JSONObject()
            .put("TargetServerEntry", cleaned)
            .put("SponsorId", randomHex(8))
            .put("PropagationChannelId", randomHex(8))
            .toString()
        secure.encryptAndSave(PREFIX + id, value)
        return id
    }

    /** Returns private client bootstrap JSON, or null for a missing/invalid record. */
    fun load(id: String): String? {
        if (!validId(id)) return null
        val raw = secure.getAndDecrypt(PREFIX + id)?.takeIf { it.isNotBlank() } ?: return null
        val config = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        if (!validEntry(config.optString("TargetServerEntry")) ||
            !validIdentifier(config.optString("SponsorId")) ||
            !validIdentifier(config.optString("PropagationChannelId"))) return null
        return config.toString()
    }

    companion object {
        private const val PREFIX = "private_psiphon_bootstrap_v1_"
        private val random = SecureRandom()
        private val ID = Regex("^[0-9a-fA-F-]{36}$")
        private val IDENTIFIER = Regex("^[0-9a-fA-F]{16}$")
        private val ENTRY = Regex("^[0-9a-fA-F]+$")

        fun validId(id: String): Boolean = ID.matches(id) &&
            runCatching { UUID.fromString(id).toString().equals(id, ignoreCase = true) }.getOrDefault(false)
        fun validIdentifier(value: String): Boolean = IDENTIFIER.matches(value)
        fun validEntry(entry: String): Boolean =
            entry.length in 64..262_144 && entry.length % 2 == 0 && ENTRY.matches(entry)

        private fun randomHex(bytes: Int): String {
            val payload = ByteArray(bytes).also(random::nextBytes)
            return payload.joinToString("") { "%02X".format(it.toInt() and 255) }
        }
    }
}
