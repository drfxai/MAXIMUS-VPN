package com.example.browser

object TrackerBlocker {

    private val BLOCKED_DOMAINS = setOf(
        "google-analytics.com",
        "googletagmanager.com",
        "doubleclick.net",
        "googleadservices.com",
        "facebook.net",
        "facebook.com/tr",
        "criteo.com",
        "criteo.net",
        "scorecardresearch.com",
        "hotjar.com",
        "taboola.com",
        "outbrain.com",
        "adnxs.com",
        "ads.twitter.com",
        "analytics.tiktok.com",
        "telemetry",
        "statcounter.com",
        "yandex.ru/metrika",
        "amplitude.com",
        "mixpanel.com",
        "appsflyer.com",
        "branch.io",
        "adjust.com",
        "segment.io",
        "segment.com",
        "optimizely.com",
        "fullstory.com",
        "mouseflow.com",
        "newrelic.com",
        "sentry.io",
        "chartbeat.com",
        "quantserve.com",
        "bugsnag.com",
        "intercom.io"
    )

    /**
     * Matches the request's host (and, for entries with a path, the start of its path) only. The query string
     * is never looked at, so a search for "segment.com" or "telemetry" is not mistaken for a tracker.
     */
    fun isBlocked(url: String): Boolean {
        val uri = try { java.net.URI(url) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase()?.trimEnd('.') ?: return false
        val path = uri.rawPath.orEmpty()
        return BLOCKED_DOMAINS.any { blocked ->
            when {
                blocked == "telemetry" -> host.split('.').any { it.contains(blocked) }
                '/' in blocked -> {
                    val domain = blocked.substringBefore('/')
                    val prefix = "/" + blocked.substringAfter('/')
                    (host == domain || host.endsWith(".$domain")) && (path == prefix || path.startsWith("$prefix/"))
                }
                else -> host == blocked || host.endsWith(".$blocked")
            }
        }
    }
}
