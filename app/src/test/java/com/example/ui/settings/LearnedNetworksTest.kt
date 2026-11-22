package com.example.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Learned network data: carriers by name, Wi-Fi never by name, most routes first. */
class LearnedNetworksTest {
    @Test fun carriersAreNamedAndWifiIsNot() {
        val rows = LearnedNetworks.rows(mapOf("cell:43211" to 1, "wifi:a1b2c3d4e5f6" to 2, "cell:43235" to 3))
        assertEquals(listOf(
            LearnedNetworks.Row("Irancell", 3),
            LearnedNetworks.Row("Wi-Fi network (name not stored)", 2),
            LearnedNetworks.Row("Hamrah-e Aval (MCI)", 1)
        ), rows)
        assertEquals(listOf("3 routes", "2 routes", "1 route"), rows.map { it.count })
        assertTrue(rows.none { "a1b2" in it.name })
    }

    @Test fun severalWifiNetworksAreNumberedNotNamed() {
        val rows = LearnedNetworks.rows(mapOf("wifi:aaaaaaaaaaaa" to 1, "wifi:bbbbbbbbbbbb" to 1, "cell" to 1, "cell:99999" to 2))
        assertEquals(listOf("Mobile carrier 99999", "Mobile network", "Wi-Fi network 1 (name not stored)", "Wi-Fi network 2 (name not stored)"),
            rows.map { it.name })
    }

    @Test fun nothingLearnedIsAnEmptyList() {
        assertTrue(LearnedNetworks.rows(emptyMap()).isEmpty())
        assertTrue(LearnedNetworks.rows(mapOf("cell:43235" to 0)).isEmpty())
    }

    @Test fun theExplanationUsesTheBooksOwnLimit() {
        assertEquals("Routes that worked, per carrier or Wi-Fi. Kept for 3 days, no passwords or keys.", LearnedNetworks.explanation)
    }

    @Test fun clearingWipesEveryPerNetworkStoreAndReportsFailures() {
        val net = "cell:43211"
        var winners: String? = null
        val revival = com.example.vpn.lab.RevivalWinnerMemory({ winners }, { winners = it })
        revival.record(net, "cfg", "ech")
        var resolvers: String? = null
        val pool = com.example.vpn.dns.VerifiedResolverPool({ resolvers }, { resolvers = it })
        pool.record(net, listOf(com.example.vpn.dns.ResolverScanner.Result("8.8.8.8", "test", 3, 3, 40)))
        var edges: String? = null
        val cache = com.example.vpn.cleanip.CleanIpCache({ edges }, { edges = it })
        cache.put(net, com.example.vpn.cleanip.Cdn.CLOUDFLARE, "edge.example.com", listOf("104.16.1.1"))
        cache.record(net, com.example.vpn.cleanip.SniProxyDetector.Verdict(proxied = true, conclusive = true, reasons = emptyList()))
        assertTrue(revival.wins(net).isNotEmpty() && pool.verified(net).isNotEmpty())

        val failed = LearnedNetworks.clearAll(listOf(
            "routes" to { error("disk full") },
            "revival winners" to { revival.clear() },
            "verified resolvers" to { pool.clear() },
            "clean CDN addresses" to { cache.clear() }
        ))

        // One store failing does not keep the others.
        assertEquals(listOf("routes"), failed)
        assertTrue(revival.wins(net).isEmpty())
        assertTrue(pool.verified(net).isEmpty())
        assertTrue(cache.best(net, com.example.vpn.cleanip.Cdn.CLOUDFLARE, "edge.example.com").isEmpty())
        assertEquals(null, cache.sniProxied(net))
        // And it is persisted: fresh instances over what was saved remember nothing.
        assertTrue(com.example.vpn.lab.RevivalWinnerMemory({ winners }, { winners = it }).wins(net).isEmpty())
        assertTrue(com.example.vpn.dns.VerifiedResolverPool({ resolvers }, { resolvers = it }).verified(net).isEmpty())
        assertEquals(null, com.example.vpn.cleanip.CleanIpCache({ edges }, { edges = it }).sniProxied(net))
    }
}
