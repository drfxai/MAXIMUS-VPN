package com.example.vpn.lab

/**
 * LAB "Service check": opens the real sites people use (Gemini, AI Studio, ChatGPT, YouTube, Telegram,
 * X) the way a browser would and judges what came back, because a ping or a 204 test passes on paths
 * where the site itself is region-blocked or replaced by the filter's page.
 *
 * The check runs from the app, so with the VPN on it goes through the connected config (for configs run
 * by Xray; configs run by a separate engine leave the app's own traffic outside the tunnel), and with the
 * VPN off it shows what the network allows directly. It is signed out: a site that decides the region
 * from the signed-in account (Gemini can) may still differ for the user's account.
 *
 * What is sent is an ordinary GET of each site's front page; nothing about the user or their configs.
 */
object ServiceCheck {
    data class Service(val id: String, val title: String, val url: String)

    val SERVICES = listOf(
        Service("gemini", "Gemini", "https://gemini.google.com/app"),
        Service("aistudio", "Google AI Studio", "https://aistudio.google.com/"),
        Service("chatgpt", "ChatGPT", "https://chatgpt.com/"),
        Service("youtube", "YouTube", "https://www.youtube.com/"),
        Service("telegram", "Telegram", "https://web.telegram.org/"),
        Service("x", "X", "https://x.com/")
    )

    /** Where the exit address and its country are read from (Cloudflare's plain-text trace). */
    const val TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"

    enum class Verdict(val title: String) {
        WORKS("Works"),
        REGION_BLOCKED("Region blocked"),
        FILTERED("Filtered"),
        REFUSED("Refused"),
        NO_CONNECTION("No connection"),
        NOT_RUN("Not checked")
    }

    /** What one GET returned: the final status and address after redirects, and the start of the body. */
    data class Response(val status: Int, val finalUrl: String, val body: String, val ms: Long)

    data class Result(val service: Service, val verdict: Verdict, val detail: String, val ms: Long? = null)

    data class Exit(val ip: String, val country: String)

    data class Report(val results: List<Result>, val exit: Exit?, val throughVpn: Boolean, val at: Long)

    /** Wording the sites use when they refuse a region (lower case). */
    private val REGION_MARKERS = listOf(
        "not available in your country", "isn't available in your country", "is not available in your country",
        "not supported in your country", "isn't supported in your country", "not available in your region",
        "isn't available in your region", "not available in your location", "unsupported_country",
        "unsupported country"
    )

    /** Iran's filter answers with its own page or a redirect to it. */
    private val FILTER_HOSTS = listOf("peyvandha.ir", "10.10.34.34", "10.10.34.35", "10.10.34.36")

    /** A Cloudflare bot check: the site was reached, the page is a challenge. */
    private val CHALLENGE_MARKERS = listOf("just a moment...", "cf-chl", "challenge-platform")

    /** Judges one service from what [response] holds, or from [error] when the request failed. */
    fun judge(service: Service, response: Response?, error: String? = null): Result {
        if (response == null) return Result(service, Verdict.NO_CONNECTION, error?.take(120) ?: "no answer")
        val body = response.body.lowercase()
        // Working pages carry these words in their bundled translations, so they count only in the
        // page title, in an error answer, or in the address a redirect ended on.
        val title = body.substringAfter("<title", "").substringAfter('>', "").substringBefore("</title>", "")
        val regionWords = REGION_MARKERS.any { it in title } || (response.status >= 400 && REGION_MARKERS.any { it in body })
        val path = response.finalUrl.substringAfter("://").substringAfter('/', "").lowercase()
        val host = response.finalUrl.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        return when {
            FILTER_HOSTS.any { host == it || host.endsWith(".$it") } || "peyvandha" in body ->
                Result(service, Verdict.FILTERED, "the filter's page answered instead of the site", response.ms)
            response.status == 451 || regionWords || path.startsWith("sorry") || "unsupported" in path ->
                Result(service, Verdict.REGION_BLOCKED, "the site refuses this exit's country", response.ms)
            response.status in 200..399 -> Result(service, Verdict.WORKS, "page loaded (${response.status})", response.ms)
            CHALLENGE_MARKERS.any { it in body } ->
                Result(service, Verdict.WORKS, "reached; the site shows a bot check (${response.status})", response.ms)
            response.status == 403 -> Result(service, Verdict.REFUSED, "403 Forbidden, often a region or address block", response.ms)
            else -> Result(service, Verdict.REFUSED, "HTTP ${response.status}", response.ms)
        }
    }

    /** The exit address and country from Cloudflare's trace (`ip=...` and `loc=...` lines), or null. */
    fun parseTrace(text: String?): Exit? {
        val lines = text.orEmpty().lines().associate { it.substringBefore('=').trim() to it.substringAfter('=', "").trim() }
        val ip = lines["ip"].orEmpty()
        val loc = lines["loc"].orEmpty()
        return if (ip.isNotBlank() && loc.length == 2) Exit(ip, loc.uppercase()) else null
    }

    /** A country code as its flag, for the report. */
    fun flag(country: String): String =
        if (country.length != 2 || !country.all { it in 'A'..'Z' }) "" else
            String(Character.toChars(0x1F1E6 + (country[0] - 'A'))) + String(Character.toChars(0x1F1E6 + (country[1] - 'A')))

    /**
     * Checks every service with [fetch] (a GET that follows redirects; it returns the response or throws),
     * and reads the exit from [TRACE_URL]. [cancelled] is checked between services.
     */
    fun run(
        fetch: (String) -> Response,
        throughVpn: Boolean,
        now: Long = System.currentTimeMillis(),
        cancelled: () -> Boolean = { false },
        onResult: (Result) -> Unit = {}
    ): Report {
        val exit = runCatching { parseTrace(fetch(TRACE_URL).body) }.getOrNull()
        val results = SERVICES.map { s ->
            val r = if (cancelled()) Result(s, Verdict.NOT_RUN, "stopped") else {
                val (resp, err) = try { fetch(s.url) to null } catch (e: Exception) { null to (e.message ?: e.javaClass.simpleName) }
                judge(s, resp, err)
            }
            onResult(r)
            r
        }
        return Report(results, exit, throughVpn, now)
    }
}
