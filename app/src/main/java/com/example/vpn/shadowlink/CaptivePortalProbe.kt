package com.example.vpn.shadowlink

/**
 * A small gap-filler for ShadowLink: whether a captive portal (a hotel / airport / café sign-in page)
 * stands between the phone and the open internet. It uses the same "generate_204" endpoints Android's
 * own connectivity check uses: an untouched network answers 204 with no body, while a portal answers
 * 200 with a page or redirects elsewhere. Only meaningful on the physical network (VPN off); once a
 * tunnel is up the portal no longer applies.
 */
object CaptivePortalProbe {
    enum class Portal(val title: String, val note: String) {
        OPEN("Open", "The connectivity probe returned the expected empty reply"),
        CAPTIVE("Sign-in required", "A portal page stands between you and the internet; sign in first"),
        CLOSED("No reply", "The connectivity probe got no answer at all"),
        UNKNOWN("Not checked", "The captive-portal check did not run (for example the VPN is on)")
    }

    /** Endpoints that return 204 No Content when the network is open. */
    val PROBE_URLS = listOf(
        "http://connectivitycheck.gstatic.com/generate_204",
        "http://www.google.com/generate_204"
    )

    /** What one probe GET returned. [location] is set only when the request was redirected elsewhere. */
    data class ProbeResponse(val status: Int?, val location: String?, val bodyLength: Int?)

    /**
     * Pure classifier. The probe endpoints promise an empty 204 on an open network, so any other
     * successful answer to them is interception, and no answer at all is no connectivity.
     */
    fun judge(status: Int?, location: String?, bodyLength: Int?): Portal = when {
        status == null -> Portal.CLOSED
        status == 204 && (bodyLength ?: 0) == 0 -> Portal.OPEN
        status in 300..399 && !location.isNullOrBlank() -> Portal.CAPTIVE
        status in 200..399 -> Portal.CAPTIVE
        else -> Portal.CLOSED
    }

    /**
     * Runs [fetch] against each probe URL (the caller binds it to the physical network) and returns the
     * first definitive verdict. CLOSED only when every probe failed or got no answer.
     */
    inline fun check(fetch: (String) -> ProbeResponse): Portal {
        for (url in PROBE_URLS) {
            val r = runCatching { fetch(url) }.getOrNull() ?: continue
            val verdict = judge(r.status, r.location, r.bodyLength)
            if (verdict != Portal.CLOSED) return verdict
        }
        return Portal.CLOSED
    }
}
