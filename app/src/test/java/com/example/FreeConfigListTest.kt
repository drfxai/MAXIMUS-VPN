package com.example

import com.example.data.model.VlessProfile
import com.example.vpn.hub.FreeConfigList
import com.example.vpn.hub.HubManifest
import com.example.vpn.subscription.SubscriptionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** The free list from GitHub is used only with a valid signature, from GitHub or any of its CDN copies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeConfigListTest {
    private val list = "vless://11111111-1111-1111-1111-111111111111@203.0.113.7:443?security=reality&sni=www.example.com&pbk=abc&sid=ab&type=tcp#Free%20VLESS%201\n"
    private val manifest = """{"count":1,"created":"2026-10-05T18:00:00Z","files":{"free.txt":{"bytes":${list.length},"sha256":"${sha(list)}"}},"version":1}"""
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = java.util.Base64.getEncoder().encodeToString(keys.public.encoded)
    private val signature = java.util.Base64.getEncoder().encodeToString(
        Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(manifest.toByteArray()); sign() })

    private fun sha(text: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Serves the three files under [base]; [listBody] replaces the list to model a changed or stale copy. */
    private fun server(base: String, listBody: String = list, sig: String = signature): (String) -> String = { url ->
        when (url) {
            "$base/manifest.json" -> manifest
            "$base/manifest.sig" -> sig
            "$base/free.txt" -> listBody
            else -> throw java.io.IOException("404 $url")
        }
    }

    @Test fun theListAndItsMirrorsAreRecognised() {
        assertTrue(FreeConfigList.isList(FreeConfigList.URL))
        val mirrors = SubscriptionSources.derivedMirrors(FreeConfigList.URL)
        assertTrue(mirrors.size >= 4)
        mirrors.forEach { assertTrue(it, FreeConfigList.isList(it)) }
        assertFalse(FreeConfigList.isList("https://raw.githubusercontent.com/someone/MAXIMUS-VPN/free-configs/free.txt"))
        assertFalse(FreeConfigList.isList("https://raw.githubusercontent.com/drfxai/MAXIMUS-VPN/main/free.txt"))
    }

    @Test fun aSignedCopyIsAcceptedFromAnyMirror() {
        val mirror = SubscriptionSources.derivedMirrors(FreeConfigList.URL).first { "jsdelivr" in it }
        val base = mirror.substringBeforeLast('/')
        assertEquals(list, FreeConfigList.download(mirror, server(base), publicKey))
    }

    @Test fun aChangedUnsignedOrUncheckableCopyIsRefused() {
        val base = FreeConfigList.URL.substringBeforeLast('/')
        assertThrows(HubManifest.Refused::class.java) {
            FreeConfigList.download(FreeConfigList.URL, server(base, listBody = list + "vless://evil@198.51.100.1:443?security=tls#x\n"), publicKey)
        }
        assertThrows(HubManifest.Refused::class.java) { FreeConfigList.download(FreeConfigList.URL, server(base, sig = ""), publicKey) }
        assertThrows(HubManifest.Refused::class.java) { FreeConfigList.download(FreeConfigList.URL, server(base), "") }
        assertFalse(FreeConfigList.available(""))
        assertTrue(FreeConfigList.available(publicKey))
    }

    private fun profile(name: String, host: String, favorite: Boolean = false) = VlessProfile(
        id = host, name = name, address = host, port = 443, uuid = "11111111-1111-1111-1111-111111111111",
        security = "reality", sni = "www.example.com", publicKey = "abc", isFavorite = favorite
    )

    @Test fun theCountryComesFromTheNameTheListGave() {
        assertEquals("DE", FreeConfigList.countryOf("DE \u00B7 VLESS 12"))
        assertEquals(null, FreeConfigList.countryOf("Free VLESS 12"))
        assertEquals(null, FreeConfigList.countryOf("de \u00B7 VLESS 12"))
        assertEquals("NL", FreeConfigList.prepare(listOf(profile("NL \u00B7 VLESS 3", "203.0.113.3"))).single().countryCode)
    }

    @Test fun neverMoreThanThirtyServersAreTakenFromAList() {
        val long = (1..300).map { profile("Free VLESS $it", "203.0.113.${it % 250 + 1}").copy(id = "p$it") }
        val taken = FreeConfigList.prepare(long)
        assertEquals(30, taken.size)
        assertEquals((1..30).map { "p$it" }, taken.map { it.id })
    }

    @Test fun anInstallHoldingHundredsKeepsTheFastestThirtyAndWhatTheUserChose() {
        val saved = (1..300).map { profile("Free VLESS $it", "203.0.113.${it % 250 + 1}").copy(id = "p$it", lastLatencyMs = if (it % 2 == 0) it * 10L else null) }
        val favourite = saved[298].copy(isFavorite = true)       // slow and untested, but the user's
        val inUse = saved[299]
        val all = saved.take(298) + favourite + inUse
        val dropped = FreeConfigList.surplus(all, keepId = inUse.id)
        assertEquals(300 - 30, dropped.size)
        assertFalse(dropped.any { it.id == favourite.id || it.id == inUse.id })
        val kept = all - dropped.toSet()
        assertEquals(30, kept.size)
        // The rest of the kept servers are the fastest tested ones.
        assertTrue(kept.filter { it.id != favourite.id && it.id != inUse.id }.all { it.lastLatencyMs != null && it.lastLatencyMs!! <= 560 })
    }

    @Test fun serversDroppedFromTheListGoExceptFavouritesAndTheOneInUse() {
        val kept = profile("a", "203.0.113.1")
        val gone = profile("b", "203.0.113.2")
        val favourite = profile("c", "203.0.113.3", favorite = true)
        val inUse = profile("d", "203.0.113.4")
        val stale = FreeConfigList.stale(listOf(kept, gone, favourite, inUse), listOf(kept.copy(id = "new")), keepId = inUse.id)
        assertEquals(listOf(gone), stale)
    }

    @Test fun sitesAreReadFromTheName() {
        assertEquals(setOf("YT", "TG", "X"), FreeConfigList.sitesOf("DE · VLESS 12 · YT TG X"))
        assertEquals(setOf("TG"), FreeConfigList.sitesOf("Free TROJAN 3 · TG"))
        assertEquals(emptySet<String>(), FreeConfigList.sitesOf("DE · VLESS 12"))
        assertEquals(emptySet<String>(), FreeConfigList.sitesOf("My server · home"))
        assertEquals("DE", FreeConfigList.countryOf("DE · VLESS 12 · YT TG X"))
    }
}
