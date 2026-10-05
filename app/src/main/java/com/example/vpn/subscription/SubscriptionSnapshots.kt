package com.example.vpn.subscription

import java.io.File
import java.security.MessageDigest

/**
 * The last payload of each subscription that held configurations, kept encrypted on the device. When
 * every source of a subscription is blocked, its servers come back from this copy, so a reinstall of
 * the profile list, a cleared server list or a censor that empties the file never leaves the user with
 * nothing.
 */
class SubscriptionSnapshots(
    private val dir: File,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String
) {
    data class Snapshot(val savedAt: Long, val sourceUrl: String, val payload: String)

    fun save(subscriptionId: String, sourceUrl: String, payload: String, now: Long = System.currentTimeMillis()) {
        val sealed = encrypt("$now\n$sourceUrl\n$payload")
        if (sealed.isEmpty()) return
        dir.mkdirs()
        val target = file(subscriptionId)
        val temp = File(dir, target.name + ".tmp")
        temp.writeText(sealed)
        if (!temp.renameTo(target)) {
            target.delete()
            temp.renameTo(target)
        }
    }

    fun load(subscriptionId: String): Snapshot? {
        val f = file(subscriptionId)
        if (!f.isFile) return null
        val plain = runCatching { decrypt(f.readText()) }.getOrNull().orEmpty()
        val lines = plain.split('\n', limit = 3)
        if (lines.size < 3) return null
        val savedAt = lines[0].toLongOrNull() ?: return null
        return Snapshot(savedAt, lines[1], lines[2])
    }

    fun delete(subscriptionId: String) {
        file(subscriptionId).delete()
    }

    private fun file(subscriptionId: String): File {
        val name = MessageDigest.getInstance("SHA-256").digest(subscriptionId.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        return File(dir, "$name.snap")
    }
}
