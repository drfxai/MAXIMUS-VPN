package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.data.model.SubscriptionInfo
import com.example.data.security.SecureStorage

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val lastUpdated: Long = 0L,
    val autoRefresh: Boolean = true,
    val refreshIntervalMinutes: Int = 1440,
    val nodeCount: Int = 0,
    val lastError: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    /** Mirror addresses, one per line, encrypted like [url]. */
    val mirrors: String = ""
) {
    fun toDomain(): SubscriptionInfo = SubscriptionInfo(
        id = id,
        name = name,
        url = SecureStorage.decryptOrPlaintext(url).let { decoded ->
            // Backward compatibility for databases written before URLs were encrypted.
            if (decoded.startsWith("http://", ignoreCase = true) ||
                decoded.startsWith("https://", ignoreCase = true)
            ) decoded else ""
        },
        lastUpdated = lastUpdated,
        autoRefresh = autoRefresh,
        refreshIntervalMinutes = refreshIntervalMinutes,
        nodeCount = nodeCount,
        lastError = lastError,
        mirrors = SecureStorage.decryptOrPlaintext(mirrors).lines()
            .filter { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
    )

    companion object {
        fun fromDomain(sub: SubscriptionInfo, etag: String? = null, lastModified: String? = null): SubscriptionEntity =
            SubscriptionEntity(
                id = sub.id,
                name = sub.name,
                url = if (sub.url.isNotEmpty()) SecureStorage.encrypt(sub.url) else "",
                lastUpdated = sub.lastUpdated,
                autoRefresh = sub.autoRefresh,
                refreshIntervalMinutes = sub.refreshIntervalMinutes,
                nodeCount = sub.nodeCount,
                lastError = sub.lastError,
                etag = etag,
                lastModified = lastModified,
                mirrors = sub.mirrors.joinToString("\n").let { if (it.isNotEmpty()) SecureStorage.encrypt(it) else "" }
            )
    }
}
