package com.example.data.security

import android.content.Context
import java.io.ByteArrayOutputStream
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class SecureStorage(private val context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("maximus_secure_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_ALIAS = "MaximusMasterKey_v3"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_LENGTH = 128
        private const val GCM_IV_LENGTH = 12
        private const val BASE64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

        // Keep encryption usable in both Android (minSdk 24) and plain JVM unit tests.
        // java.util.Base64 is unavailable below API 26 and android.util.Base64 is a
        // framework stub in local JVM tests, so use a tiny dependency-free codec.
        private fun encodeBase64(bytes: ByteArray): String {
            val out = StringBuilder((bytes.size + 2) / 3 * 4)
            var index = 0
            while (index < bytes.size) {
                val first = bytes[index++].toInt() and 0xff
                val second = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
                val third = if (index < bytes.size) bytes[index++].toInt() and 0xff else -1
                out.append(BASE64_ALPHABET[first ushr 2])
                out.append(BASE64_ALPHABET[((first and 0x03) shl 4) or if (second >= 0) (second ushr 4) else 0])
                out.append(if (second >= 0) BASE64_ALPHABET[((second and 0x0f) shl 2) or if (third >= 0) (third ushr 6) else 0] else '=')
                out.append(if (third >= 0) BASE64_ALPHABET[third and 0x3f] else '=')
            }
            return out.toString()
        }

        private fun decodeBase64(value: String): ByteArray {
            val clean = value.filterNot { it.isWhitespace() }
            require(clean.length % 4 == 0) { "Invalid Base64 length" }
            val out = ByteArrayOutputStream(clean.length * 3 / 4)
            var index = 0
            while (index < clean.length) {
                fun digit(position: Int): Int {
                    val c = clean[position]
                    if (c == '=') return 0
                    val result = BASE64_ALPHABET.indexOf(c)
                    require(result >= 0) { "Invalid Base64 character" }
                    return result
                }
                val a = digit(index)
                val b = digit(index + 1)
                val c = clean[index + 2]
                val d = clean[index + 3]
                out.write((a shl 2) or (b ushr 4))
                if (c != '=') out.write(((b and 0x0f) shl 4) or (digit(index + 2) ushr 2))
                if (d != '=') out.write(((digit(index + 2) and 0x03) shl 6) or digit(index + 3))
                index += 4
            }
            return out.toByteArray()
        }

        @Volatile
        private var memoryFallbackKey: SecretKey? = null

        @Volatile
        private var activeSecretKey: SecretKey? = null

        @Synchronized
        fun getMasterKey(context: Context? = null): SecretKey {
            activeSecretKey?.let { return it }

            // Clean up any legacy insecure file if present
            context?.let { ctx ->
                try {
                    val legacyKeyFile = File(ctx.filesDir, ".maximus_enc_key")
                    if (legacyKeyFile.exists()) {
                        legacyKeyFile.delete()
                    }
                } catch (_: Exception) {}
            }

            // 1. Try AndroidKeyStore first (Hardware-backed / TEE / StrongBox where supported)
            var keyStoreException: Exception? = null
            try {
                val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                if (!keyStore.containsAlias(KEY_ALIAS)) {
                    val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                    val spec = KeyGenParameterSpec.Builder(
                        KEY_ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .setRandomizedEncryptionRequired(true)
                        .build()
                    keyGenerator.init(spec)
                    val key = keyGenerator.generateKey()
                    activeSecretKey = key
                    return key
                }
                val entry = keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                if (entry != null) {
                    val key = entry.secretKey
                    activeSecretKey = key
                    return key
                }
            } catch (e: Exception) {
                keyStoreException = e
            }

            // In production Android runtime, never silently fall back to an ephemeral key that would make stored data unreadable on restart
            val isJvmTest = runCatching {
                android.os.Build.FINGERPRINT.isNullOrBlank() ||
                    android.os.Build.FINGERPRINT.startsWith("robolectric") ||
                    android.os.Build.HARDWARE == "robolectric"
            }.getOrDefault(true)

            if (isJvmTest) {
                memoryFallbackKey?.let { return it }
                val randomBytes = ByteArray(32).apply { SecureRandom().nextBytes(this) }
                val fallbackKey = SecretKeySpec(randomBytes, "AES")
                memoryFallbackKey = fallbackKey
                activeSecretKey = fallbackKey
                return fallbackKey
            } else {
                throw SecurityException(
                    "AndroidKeyStore hardware master key unavailable: ${keyStoreException?.message}",
                    keyStoreException
                )
            }
        }

        /**
         * Encrypts a string using AES-256-GCM with a fresh random 12-byte IV for every invocation.
         */
        fun encrypt(plaintext: String, context: Context? = null): String {
            if (plaintext.isEmpty()) return ""
            return try {
                val secretKey = getMasterKey(context)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.ENCRYPT_MODE, secretKey)
                val iv = cipher.iv
                val cipherText = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))

                val combined = ByteArray(iv.size + cipherText.size)
                System.arraycopy(iv, 0, combined, 0, iv.size)
                System.arraycopy(cipherText, 0, combined, iv.size, cipherText.size)

                encodeBase64(combined)
            } catch (_: Exception) {
                // Fail closed: return empty if encryption fails
                ""
            }
        }

        /**
         * Decrypts an AES-256-GCM ciphertext string.
         * Fails closed: returns empty string on any decryption error or corrupted ciphertext.
         */
        fun decrypt(ciphertext: String, context: Context? = null): String {
            if (ciphertext.isEmpty()) return ""
            return try {
                val combined = decodeBase64(ciphertext)
                if (combined.size < GCM_IV_LENGTH + 16) {
                    // Invalid ciphertext length: fail closed
                    return ""
                }

                val iv = ByteArray(GCM_IV_LENGTH)
                System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
                val cipherBytes = ByteArray(combined.size - GCM_IV_LENGTH)
                System.arraycopy(combined, GCM_IV_LENGTH, cipherBytes, 0, cipherBytes.size)

                val secretKey = getMasterKey(context)
                val cipher = Cipher.getInstance(TRANSFORMATION)
                val spec = GCMParameterSpec(GCM_TAG_LENGTH, iv)
                cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)
                val decryptedBytes = cipher.doFinal(cipherBytes)
                String(decryptedBytes, Charsets.UTF_8)
            } catch (_: Exception) {
                // Fail-closed: Never return ciphertext or partial data on error
                ""
            }
        }

        /**
         * Reads current ciphertext while retaining compatibility with rows written
         * before field encryption was introduced.
         */
        fun decryptOrPlaintext(value: String, context: Context? = null): String {
            if (value.isEmpty()) return ""
            // Only treat the value as legacy plaintext when it cannot be our ciphertext
            // format (IV + GCM tag in Base64). Otherwise a failed decrypt would leak
            // raw ciphertext into UUID/key fields.
            val looksEncrypted = value.length >= 40 && value.length % 4 == 0 &&
                value.all { it in BASE64_ALPHABET || it == '=' }
            if (looksEncrypted) return decrypt(value, context)
            return value
        }
    }

    fun encryptAndSave(key: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(key).apply()
            return
        }
        val encrypted = encrypt(value, context)
        if (encrypted.isNotEmpty()) {
            prefs.edit().putString(key, encrypted).apply()
        }
    }

    fun getAndDecrypt(key: String, defaultValue: String = ""): String {
        val stored = prefs.getString(key, null) ?: return defaultValue
        if (stored.isBlank()) return defaultValue
        val decrypted = decrypt(stored, context)
        return if (decrypted.isNotEmpty()) decrypted else defaultValue
    }
}

