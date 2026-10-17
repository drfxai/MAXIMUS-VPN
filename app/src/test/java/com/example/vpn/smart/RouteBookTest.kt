package com.example.vpn.smart

import com.example.data.model.VlessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Remembered routes: per network, bounded, expiring, redacted, and surviving a restart. */
class RouteBookTest {
    private var stored: String? = null
    private fun book() = RouteBook({ stored }, { stored = it })
    private fun recipe(network: String = "cell:43235", profile: String = "p1", at: Long = 1_000, variant: String? = null,
                       endpoint: String? = null, bi: Boolean = false) =
        RouteRecipe(network, profile, "fp-$profile", variantKey = variant, endpoint = endpoint, kind = "TLS",
            latencyMs = 300, verifiedAt = at, expiresAt = at + RouteBook.TTL_MS, bidirectional = bi)

    @Test fun aRouteIsOnlyACandidateOnTheNetworkItWorkedOn() {
        val b = book()
        b.recordVerified(recipe(network = "wifi:aaaa11112222"))
        assertEquals(1, b.forNetwork("wifi:aaaa11112222", now = 2_000).size)
        assertTrue(b.forNetwork("wifi:bbbb33334444", now = 2_000).isEmpty())
        assertTrue(b.forNetwork("cell:43235", now = 2_000).isEmpty())
    }

    @Test fun routesSurviveARestartAndExpire() {
        book().recordVerified(recipe(endpoint = "203.0.113.9", variant = "frag"))
        val again = book().forNetwork("cell:43235", now = 2_000).single()
        assertEquals("203.0.113.9", again.endpoint)
        assertEquals("frag", again.variantKey)
        assertTrue(book().forNetwork("cell:43235", now = 1_000 + RouteBook.TTL_MS + 1).isEmpty())
    }

    @Test fun bothDirectionsVerifiedRanksFirstThenNewest() {
        val b = book()
        b.recordVerified(recipe(profile = "old-bi", at = 1_000, bi = true))
        b.recordVerified(recipe(profile = "new", at = 5_000))
        b.recordVerified(recipe(profile = "older", at = 3_000))
        assertEquals(listOf("old-bi", "new", "older"), b.forNetwork("cell:43235", now = 6_000).map { it.profileId })
    }

    @Test fun twoFailedReplaysInARowDropTheRouteAndASuccessResetsTheCount() {
        val b = book()
        val r = recipe()
        b.recordVerified(r)
        b.recordFailure(r.network, r.id)
        assertEquals(1, b.forNetwork(r.network, now = 2_000).single().failures)
        b.recordVerified(r.copy(verifiedAt = 1_500))
        assertEquals(0, b.forNetwork(r.network, now = 2_000).single().failures)
        b.recordFailure(r.network, r.id)
        b.recordFailure(r.network, r.id)
        assertTrue(b.forNetwork(r.network, now = 2_000).isEmpty())
    }

    @Test fun storageIsBounded() {
        val b = book()
        repeat(9) { b.recordVerified(recipe(profile = "p$it", at = 1_000L + it)) }
        assertEquals(RouteBook.MAX_PER_NETWORK, b.forNetwork("cell:43235", now = 2_000).size)
        repeat(RouteBook.MAX_NETWORKS + 3) { b.recordVerified(recipe(network = "wifi:n$it", at = 10_000L + it)) }
        assertTrue(b.forNetwork("cell:43235", now = 20_000).isEmpty())
        assertEquals(1, b.forNetwork("wifi:n${RouteBook.MAX_NETWORKS + 2}", now = 20_000).size)
    }

    @Test fun bidirectionalMarkSticksToTheSameRoute() {
        val b = book()
        val r = recipe()
        b.recordVerified(r)
        b.markBidirectional(r.network, r.id)
        assertTrue(b.forNetwork(r.network, now = 2_000).single().bidirectional)
        // The same route verified again keeps the stronger proof.
        b.recordVerified(r.copy(verifiedAt = 1_500))
        assertTrue(b.forNetwork(r.network, now = 2_000).single().bidirectional)
    }

    @Test fun anUnknownSchemaIsIgnoredNotMisread() {
        stored = """{"schema":99,"routes":[{"n":"cell:43235","p":"x"}]}"""
        assertTrue(book().forNetwork("cell:43235", now = 2_000).isEmpty())
        stored = "not json"
        assertTrue(book().forNetwork("cell:43235", now = 2_000).isEmpty())
    }

    @Test fun clearingForgetsEverything() {
        var legacy: String? = null
        val memory = NetworkMemory({ legacy }, { legacy = it }, { stored }, { stored = it })
        memory.recordSuccess("cell:43235", "TLS", 200)
        memory.routes.recordVerified(recipe())
        memory.clear()
        assertTrue(memory.workingKinds("cell:43235").isEmpty())
        assertTrue(memory.routes.forNetwork("cell:43235", now = 2_000).isEmpty())
        assertTrue(book().forNetwork("cell:43235", now = 2_000).isEmpty())
        assertTrue(memory.routes.countsByNetwork(now = 2_000).isEmpty())
    }

    @Test fun countsPerNetworkLeaveOutExpiredRoutes() {
        val b = book()
        b.recordVerified(recipe(profile = "p1"))
        b.recordVerified(recipe(profile = "p2"))
        b.recordVerified(recipe(network = "wifi:aaaa11112222", profile = "p1", at = 5_000))
        assertEquals(mapOf("cell:43235" to 2, "wifi:aaaa11112222" to 1), b.countsByNetwork(now = 6_000))
        assertEquals(mapOf("wifi:aaaa11112222" to 1), b.countsByNetwork(now = 1_000 + RouteBook.TTL_MS + 1))
    }

    @Test fun aRecipeHoldsNoCredential() {
        val json = recipe(endpoint = "203.0.113.9").toJson().toString()
        for (secret in listOf("uuid-secret", "password")) assertFalse(json.contains(secret))
        assertEquals(setOf("n", "p", "fp", "k", "l", "at", "exp", "bi", "f", "e"), recipe(endpoint = "203.0.113.9").toJson().keys().asSequence().toSet())
    }

    @Test fun pinningAnAddressKeepsTheServerNameForTlsAndHost() {
        val p = VlessProfile(id = "p", name = "n", address = "edge.example.com", port = 443, uuid = "uuid-secret",
            transport = "ws", security = "tls")
        val pinned = RouteRecipe.pinEndpoint(p, "203.0.113.9")
        assertEquals("203.0.113.9", pinned.address)
        assertEquals("edge.example.com", pinned.sni)
        assertEquals("edge.example.com", pinned.host)
        assertEquals(p.effectiveFingerprint, pinned.effectiveFingerprint)
        // A configured SNI is never replaced.
        assertEquals("cdn.example.org", RouteRecipe.pinEndpoint(p.copy(sni = "cdn.example.org"), "203.0.113.9").sni)
        assertNull(RouteRecipe.fromJson(org.json.JSONObject("{}")))
    }
}
