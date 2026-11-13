package com.example

import com.example.vpn.subscription.SubscriptionFetcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The failure shown for a subscription is the one that explains it, not the one that failed fastest. */
class SubscriptionMainReasonTest {
    @Test fun anInstantRedirectRefusalDoesNotHideTimeouts() {
        val failures = listOf(
            "https://cdn.statically.io/x" to "SSRF blocked: Subscription redirect target is restricted",
            "https://raw.githubusercontent.com/x" to "timeout",
            "(still waiting)" to "no answer within 75 s"
        )
        assertEquals("timeout", SubscriptionFetcher.mainReason(failures))
    }

    @Test fun aBlockPageWinsOverEverything() {
        val failures = listOf(
            "a" to "HTTP 404",
            "b" to "timeout",
            "c" to "a web page instead of a subscription (blocked?)"
        )
        assertEquals("a web page instead of a subscription (blocked?)", SubscriptionFetcher.mainReason(failures))
    }

    @Test fun httpStatusBeatsGuardRefusalAndTheGuardIsTheLastResort() {
        assertEquals("HTTP 503", SubscriptionFetcher.mainReason(listOf("a" to "SSRF blocked: x", "b" to "HTTP 503")))
        assertEquals("SSRF blocked: x", SubscriptionFetcher.mainReason(listOf("a" to "SSRF blocked: x")))
        assertNull(SubscriptionFetcher.mainReason(emptyList()))
    }
}
