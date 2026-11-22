package com.example

import com.example.data.model.AppSettings
import com.example.data.model.RoutingMode
import com.example.data.model.VlessProfile
import com.example.xray.GeoAssets
import com.example.xray.XrayConfigBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** The bundled Iran GeoFiles match their manifest, and the Iran presets reference categories they contain. */
class GeoFilesTest {
    private val assetDir = listOf(File("src/main/assets/geo"), File("app/src/main/assets/geo")).first { it.isDirectory }

    private val node = VlessProfile(
        name = "geo", address = "203.0.113.10", port = 443,
        uuid = "00000000-0000-4000-8000-000000000000", transport = "tcp", security = "tls", sni = "edge.example.com"
    )

    private fun sha256(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    /** Category names in a V2Ray GeoIPList/GeoSiteList `.dat` (field 1 of each top-level entry). */
    private fun categories(file: File): Set<String> {
        val b = file.readBytes()
        var i = 0
        fun varint(): Long { var r = 0L; var s = 0; while (true) { val c = b[i++].toInt() and 0xff; r = r or ((c and 0x7f).toLong() shl s); s += 7; if (c < 0x80) return r } }
        val names = mutableSetOf<String>()
        while (i < b.size) {
            varint(); val len = varint().toInt(); val end = i + len
            varint(); val nameLen = varint().toInt()
            names += String(b, i, nameLen, Charsets.UTF_8).lowercase()
            i = end
        }
        return names
    }

    private fun rules(mode: RoutingMode): List<JSONObject> {
        val json = JSONObject(XrayConfigBuilder.buildJson(node, AppSettings(routingMode = mode, dnsServer = "1.1.1.1")))
        val arr = json.getJSONObject("routing").getJSONArray("rules")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
    }

    private fun values(rules: List<JSONObject>, key: String, prefix: String): Set<String> = rules.flatMap { r ->
        val a = r.optJSONArray(key) ?: JSONArray()
        (0 until a.length()).map { a.getString(it) }
    }.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet()

    @Test
    fun bundledFilesMatchManifest() {
        val expected = GeoAssets.expectedHashes(File(assetDir, GeoAssets.MANIFEST).readText())
        assertEquals(GeoAssets.FILES.keys, expected.keys)
        expected.forEach { (name, hash) -> assertEquals(name, hash, sha256(File(assetDir, name))) }
    }

    @Test
    fun presetCategoriesExistInBundledFiles() {
        val geoip = categories(File(assetDir, "geoip-lite.dat"))
        val geosite = categories(File(assetDir, "geosite-lite.dat"))
        val r = rules(RoutingMode.IRAN_DIRECT_BLOCK_ADS)
        val ipTags = values(r, "ip", "geoip:")
        val siteTags = values(r, "domain", "geosite:")
        assertTrue(ipTags.containsAll(setOf("ir", "private")))
        assertTrue(siteTags.containsAll(setOf("category-ir", "ads")))
        assertTrue("missing geoip $ipTags in $geoip", geoip.containsAll(ipTags))
        assertTrue("missing geosite $siteTags in $geosite", geosite.containsAll(siteTags))
    }

    @Test
    fun adsBlockedOnlyInAdsPreset() {
        fun blocksAds(mode: RoutingMode) = rules(mode).any { it.optString("outboundTag") == "block" && values(listOf(it), "domain", "geosite:").contains("ads") }
        assertTrue(blocksAds(RoutingMode.IRAN_DIRECT_BLOCK_ADS))
        assertFalse(blocksAds(RoutingMode.IRAN_DIRECT))
        val direct = rules(RoutingMode.IRAN_DIRECT).filter { it.optString("outboundTag") == "direct" }
        assertTrue(values(direct, "ip", "geoip:").contains("ir"))
        assertTrue(values(direct, "domain", "geosite:").contains("category-ir"))
        assertEquals("proxy", rules(RoutingMode.IRAN_DIRECT).last().optString("outboundTag"))
    }

    @Test
    fun defaultRoutingUsesNoGeoFiles() {
        assertFalse(AppSettings().routingMode.usesGeoFiles)
        val r = rules(AppSettings().routingMode)
        assertTrue(values(r, "ip", "geoip:").isEmpty() && values(r, "domain", "geosite:").isEmpty())
    }

    @Test
    fun assetLocationIsSetOnceAndUserValueKept() {
        val config = JSONObject().put("env", JSONObject().put("xray.tun.fd", "5"))
        GeoAssets.applyAssetLocation(config, File("/data/geo"))
        assertEquals("/data/geo", config.getJSONObject("env").getString("xray.location.asset"))
        assertEquals("/data/geo", config.getJSONObject("env").getString("XRAY_LOCATION_ASSET"))
        val custom = JSONObject().put("env", JSONObject().put("xray.location.asset", "/custom"))
        GeoAssets.applyAssetLocation(custom, File("/data/geo"))
        assertEquals("/custom", custom.getJSONObject("env").getString("xray.location.asset"))
        assertFalse(custom.getJSONObject("env").has("XRAY_LOCATION_ASSET"))
    }
}
