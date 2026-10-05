package com.example.vpn.subscription

import com.example.data.model.SubscriptionInfo
import com.example.data.repository.SubscriptionRepository

/**
 * The project's own subscription, served by the Telegram bot's Worker (`tools/telegram-bot`, route
 * `/sub`). The admin adds, edits and removes the underlying links from Telegram; every app picks up the
 * change on its next refresh. The first address is the subscription, the rest are its mirrors (for
 * example the workers.dev address and a custom domain on the same Worker).
 *
 * Each address is added once per install: if the user deletes the subscription it stays deleted.
 */
object OfficialSubscriptions {
    const val NAME = "MAXIMUS"

    val URLS: List<String> = listOf("https://maximus-bot.drpouriafx.workers.dev/sub")

    /**
     * Adds the official subscription if it was never added on this install. [seeded] and [markSeeded]
     * remember which addresses were offered already. Returns the new subscription, to sync, or null.
     */
    suspend fun ensure(
        repository: SubscriptionRepository,
        seeded: Set<String>,
        markSeeded: (Set<String>) -> Unit,
        urls: List<String> = URLS,
        name: String = NAME
    ): SubscriptionInfo? {
        val primary = urls.firstOrNull() ?: return null
        if (primary in seeded) return null
        markSeeded(seeded + urls)
        if (repository.getSubscriptionByUrl(primary) != null) return null
        val sub = SubscriptionInfo(name = name, url = primary, autoRefresh = true, refreshIntervalMinutes = 360, mirrors = urls.drop(1))
        repository.insertOrUpdate(sub)
        return sub
    }
}
