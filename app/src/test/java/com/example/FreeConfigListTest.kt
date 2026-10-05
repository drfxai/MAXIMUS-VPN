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
    private val now = System.currentTimeMillis() / 1000
    private val list = "vless://11111111-1111-1111-1111-111111111111@203.0.113.7:443?security=reality&sni=www.example.com&pbk=abc&sid=ab&type=tcp#Free%20VLESS%201\n"
    private val manifest = """{"count":1,"created":"2026-10-05T18:00:00Z","files":{"free.txt":{"bytes":${list.length},"sha256":"${sha(list)}"}},"verification":{"policy":"iran-proxy-v1","minimum_networks":2,"valid_until":${now + 3600}},"version":1}"""
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

    @Test fun expiredAndOversizedFreeListsAreRefusedEvenWithValidSignatures() {
        val base = FreeConfigList.URL.substringBeforeLast('/')
        assertThrows(HubManifest.Refused::class.java) {
            FreeConfigList.download(FreeConfigList.URL, server(base), publicKey, nowSeconds = now + 7200)
        }
        for (replacement in listOf(
            manifest.replace("\"count\":1", "\"count\":31"),
            manifest.replace("iran-proxy-v1", "tcp-only"),
            manifest.replace("\"minimum_networks\":2", "\"minimum_networks\":1")
        )) {
            val sig = java.util.Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private); update(replacement.toByteArray()); sign()
            })
            assertThrows(HubManifest.Refused::class.java) {
                FreeConfigList.download(FreeConfigList.URL, { url ->
                    when { url.endsWith("manifest.json") -> replacement
                           url.endsWith("manifest.sig") -> sig
                           else -> list }
                }, publicKey)
            }
        }
    }

    @Test fun theAppCapsLegacyAndImportedFreeProfilesAt30() {
        val profiles = (1..100).map { profile("Free $it", "203.0.113.$it") }
        assertEquals(30, FreeConfigList.prepare(profiles).size)
        assertEquals(1, FreeConfigList.prepare(listOf(profiles.first(), profiles.first())).size)
    }

    @Test fun aSignedEmptyListRevokesTheFeedAndCountMismatchIsRefused() {
        val empty = """{"count":0,"files":{"free.txt":{"sha256":"${sha("")}"}},"verification":{"policy":"iran-proxy-v1","minimum_networks":2,"valid_until":0},"version":1}"""
        fun serve(body: String, text: String): (String) -> String {
            val sig = java.util.Base64.getEncoder().encodeToString(Signature.getInstance("SHA256withECDSA").run {
                initSign(keys.private); update(body.toByteArray()); sign()
            })
            return { url -> when { url.endsWith("manifest.json") -> body
                                   url.endsWith("manifest.sig") -> sig
                                   else -> text } }
        }
        assertEquals("", FreeConfigList.download(FreeConfigList.URL, serve(empty, ""), publicKey))
        assertThrows(HubManifest.Refused::class.java) {
            FreeConfigList.download(FreeConfigList.URL, serve(manifest.replace("\"count\":1", "\"count\":2"), list), publicKey)
        }
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

    @Test fun serversDroppedFromTheListGoExceptFavouritesAndTheOneInUse() {
        val kept = profile("a", "203.0.113.1")
        val gone = profile("b", "203.0.113.2")
        val favourite = profile("c", "203.0.113.3", favorite = true)
        val inUse = profile("d", "203.0.113.4")
        val stale = FreeConfigList.stale(listOf(kept, gone, favourite, inUse), listOf(kept.copy(id = "new")), keepId = inUse.id)
        assertEquals(listOf(gone), stale)
    }
}
