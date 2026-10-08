package com.example.vpn.subscription

import org.json.JSONObject
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * The official subscriptions (the bot's /sub and /vip) as signed envelopes, `<address>.signed`:
 * `{"manifest": "...", "signature": "...", "payload": "..."}`. The manifest names the subscription,
 * a sequence that only goes up, an expiry and the payload's SHA-256, and is signed by the Worker's
 * OFFICIAL_SIGNING_KEY (ECDSA P-256). Because the envelope verifies on its own, any mirror can serve
 * it unchanged: a copy that is altered, belongs to the other subscription, has expired or is older than
 * one already accepted is refused, and the next address is tried.
 *
 * Enforced only when the build carries the public key ([PUBLIC_KEY]); otherwise the plain list is used
 * as before, so a build is never cut off from a Worker that does not sign yet.
 */
object OfficialSigning {
    val PUBLIC_KEY: String get() = com.example.BuildConfig.OFFICIAL_PUBLIC_KEY
    const val SUFFIX = ".signed"
    private const val ALGORITHM = "SHA256withECDSA"
    /** A sequence further ahead than this is a clock error or a forgery, not a newer list. */
    private const val MAX_FUTURE_MS = 2 * 24 * 3_600_000L

    class Refused(message: String) : Exception(message)
    data class Verified(val payload: String, val sequence: Long, val expires: Long)

    fun enforced(key: String = PUBLIC_KEY): Boolean = key.isNotBlank()

    /** The official subscriptions, by their primary address. */
    fun isOfficial(url: String): Boolean = url in OfficialSubscriptions.URLS || url == VipSubscription.URL

    /** "official-sub" for …/sub, "official-vip" for …/vip: the id the signed manifest must carry. */
    fun idFor(url: String): String = "official-" + url.substringBefore('?').trimEnd('/').substringAfterLast('/')

    /**
     * Downloads `[url].signed` with [get] and returns the payload when the envelope verifies for
     * [expectedId], has not expired at [now] and is not older than [minSequence]; throws [Refused].
     */
    fun download(url: String, get: (String) -> String, expectedId: String, minSequence: Long,
                 key: String = PUBLIC_KEY, now: Long = System.currentTimeMillis()): Verified =
        verify(get(url + SUFFIX), expectedId, minSequence, key, now)

    fun verify(envelope: String, expectedId: String, minSequence: Long, key: String = PUBLIC_KEY,
               now: Long = System.currentTimeMillis()): Verified {
        if (key.isBlank()) throw Refused("This build has no key to check the official subscription")
        val outer = runCatching { JSONObject(envelope) }.getOrElse { throw Refused("The copy is not a signed subscription") }
        val manifestText = outer.optString("manifest")
        val signature = outer.optString("signature")
        val payload = outer.optString("payload")
        if (manifestText.isEmpty() || signature.isEmpty()) throw Refused("The copy is not a signed subscription")
        if (!signatureValid(manifestText.toByteArray(Charsets.UTF_8), signature, key)) {
            throw Refused("The official subscription's signature does not match")
        }
        val manifest = JSONObject(manifestText)
        val id = manifest.optString("id")
        val sequence = manifest.optLong("sequence", -1)
        val expires = manifest.optLong("expires", -1)
        val sha = manifest.optString("sha256")
        if (id != expectedId) throw Refused("The signed copy belongs to '$id', not '$expectedId'")
        if (sha256(payload).equals(sha, ignoreCase = true).not()) throw Refused("The payload does not match its signed manifest")
        if (expires <= now) throw Refused("The signed copy expired")
        if (sequence < 0 || sequence > now + MAX_FUTURE_MS) throw Refused("The signed copy has an invalid sequence")
        if (sequence < minSequence) throw Refused("The copy is older than one already accepted")
        return Verified(payload, sequence, expires)
    }

    private fun signatureValid(data: ByteArray, signatureBase64: String, key: String): Boolean = runCatching {
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(java.util.Base64.getDecoder().decode(key)))
        var sig = java.util.Base64.getDecoder().decode(signatureBase64)
        if (sig.size == 64) sig = rawToDer(sig) // r||s from WebCrypto, if a signer did not convert it
        Signature.getInstance(ALGORITHM).run {
            initVerify(publicKey)
            update(data)
            verify(sig)
        }
    }.getOrDefault(false)

    private fun rawToDer(raw: ByteArray): ByteArray {
        fun int(bytes: ByteArray): ByteArray {
            // Minimal two's-complement INTEGER: no leading zeros (one byte stays), a zero added when the top bit is set.
            var start = 0
            while (start < bytes.size - 1 && bytes[start] == 0.toByte()) start++
            var v: ByteArray = bytes.copyOfRange(start, bytes.size)
            if (v[0].toInt() and 0x80 != 0) v = byteArrayOf(0) + v
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = int(raw.copyOfRange(0, 32)) + int(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    internal fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

/** The highest sequence accepted per official subscription, so an older copy is never taken again. */
interface OfficialSequences {
    fun last(id: String): Long
    fun record(id: String, sequence: Long)

    class InMemory : OfficialSequences {
        private val map = java.util.concurrent.ConcurrentHashMap<String, Long>()
        override fun last(id: String): Long = map[id] ?: 0L
        override fun record(id: String, sequence: Long) { map.merge(id, sequence, ::maxOf) }
    }

    class Prefs(private val prefs: android.content.SharedPreferences) : OfficialSequences {
        override fun last(id: String): Long = prefs.getLong(id, 0L)
        override fun record(id: String, sequence: Long) {
            if (sequence > last(id)) prefs.edit().putLong(id, sequence).apply()
        }
    }
}

/** How a verified official list replaces the servers saved from it. */
object OfficialSwap {
    data class Plan(
        val delete: List<com.example.data.model.VlessProfile>,
        val insert: List<com.example.data.model.VlessProfile>,
        val kept: Int,
        val retained: Int
    )

    /**
     * [saved]: servers saved from this subscription; [fresh]: the verified list; [allSaved]: every saved
     * server (a server already saved from elsewhere is not added twice); [protectedIds]: in use or selected.
     * Servers the list dropped are deleted unless protected or favourite.
     */
    fun plan(
        saved: List<com.example.data.model.VlessProfile>,
        fresh: List<com.example.data.model.VlessProfile>,
        allSaved: List<com.example.data.model.VlessProfile>,
        protectedIds: Set<String>
    ): Plan {
        val freshKeys = fresh.map { it.effectiveFingerprint }.toSet()
        val dropped = saved.filter { it.effectiveFingerprint !in freshKeys }
        val (retained, delete) = dropped.partition { it.isFavorite || it.id in protectedIds }
        val existing = allSaved.map { it.effectiveFingerprint }.toSet()
        val insert = fresh.filter { it.effectiveFingerprint !in existing }.distinctBy { it.effectiveFingerprint }
        return Plan(delete, insert, saved.size - dropped.size, retained.size)
    }
}
