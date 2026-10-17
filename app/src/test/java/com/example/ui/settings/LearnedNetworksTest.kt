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
}
