package com.example.vpn.smartsni

import com.example.data.model.VlessProfile
import com.example.vpn.connections.BuiltinMethodMemory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Build 181 Phase 5 (B): Smart ECH key parsing, DoH refresh, refresh-on-reject, manual precedence, clean fallback. */
class SmartEchTest {
    private val store = mutableMapOf<String, String>()
    private val settings = mutableMapOf<String, String>()

    @Before fun setUp() {
        store.clear(); settings.clear()
        BuiltinMethodMemory.read = { store[it] }
        BuiltinMethodMemory.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
        SmartSniSettings.read = { settings[it] }
        SmartSniSettings.write = { k, v -> if (v == null) settings.remove(k) else settings[k] = v }
    }

    @After fun tearDown() {
        BuiltinMethodMemory.read = { null }
        BuiltinMethodMemory.write = { _, _ -> }
        SmartSniSettings.read = { null }
        SmartSniSettings.write = { _, _ -> }
        SmartEch.doh = { null }
    }

    /** One ECHConfig (0xfe0d) with [publicName] and a key whose first byte is [keyByte]. */
    private fun configList(publicName: String, keyByte: Int = 1): ByteArray {
        val c = ByteArrayOutputStream()
        c.write(7)                                   // config_id
        c.write(byteArrayOf(0, 0x20))                // kem_id X25519
        c.write(byteArrayOf(0, 32)); c.write(ByteArray(32) { keyByte.toByte() })
        c.write(byteArrayOf(0, 4, 0, 1, 0, 1))       // HKDF-SHA256 / AES-128-GCM
        c.write(0)                                   // maximum_name_length
        c.write(publicName.length); c.write(publicName.toByteArray())
        c.write(byteArrayOf(0, 0))                   // extensions
        val body = c.toByteArray()
        val cfg = ByteArrayOutputStream()
        cfg.write(byteArrayOf(0xfe.toByte(), 0x0d, (body.size shr 8).toByte(), body.size.toByte())); cfg.write(body)
        val one = cfg.toByteArray()
        return byteArrayOf((one.size shr 8).toByte(), one.size.toByte()) + one
    }

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)

    /** A DNS answer: one HTTPS record (priority 1, target ".") with an ech SvcParam, TTL [ttl]. */
    private fun dnsAnswer(ech: ByteArray, ttl: Int = 300): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0, 0, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 1, 0, 0, 0, 0))
        o.write(byteArrayOf(1, 'x'.code.toByte(), 0, 0, 65, 0, 1))        // question x. HTTPS IN
        o.write(byteArrayOf(0xc0.toByte(), 12, 0, 65, 0, 1))              // answer name ptr, type, class
        o.write(byteArrayOf((ttl shr 24).toByte(), (ttl shr 16).toByte(), (ttl shr 8).toByte(), ttl.toByte()))
        val rdata = ByteArrayOutputStream()
        rdata.write(byteArrayOf(0, 1, 0))                                  // priority 1, target root
        rdata.write(byteArrayOf(0, 5, (ech.size shr 8).toByte(), ech.size.toByte())); rdata.write(ech)
        val rd = rdata.toByteArray()
        o.write(byteArrayOf((rd.size shr 8).toByte(), rd.size.toByte())); o.write(rd)
        return o.toByteArray()
    }

    private val worker = VlessProfile(name = "w", address = "104.16.1.1", port = 443, uuid = "u", transport = "ws",
        security = "tls", sni = "a.example.workers.dev", host = "a.example.workers.dev")

    @Test fun parsesOuterNameAndValidates() {
        val list = configList("cloudflare-ech.com")
        assertEquals(listOf("cloudflare-ech.com"), SmartEch.publicNames(list))
        assertTrue(SmartEch.validConfigList(b64(list)))
        assertFalse(SmartEch.validConfigList("not base64 !!"))
        assertFalse(SmartEch.validConfigList(b64(byteArrayOf(0, 3, 1, 2, 3))))
        val (ech, ttl) = SmartEch.echParam(dnsAnswer(list, 120))!!
        assertTrue(ech.contentEquals(list))
        assertEquals(120L, ttl)
    }

    @Test fun refreshFetchesWhenStaleAndKeepsTtlBounds() {
        var calls = 0
        SmartEch.doh = { calls++; dnsAnswer(configList("cloudflare-ech.com"), 30) }
        val s = SmartEch.refresh("cell", now = 1_000L)
        assertEquals("cloudflare-ech.com", s.outer)
        assertEquals(SmartEch.MIN_TTL_MS, s.ttlMs)
        SmartEch.refresh("cell", now = 2_000L)
        assertEquals(1, calls)                       // fresh: cached
        SmartEch.refresh("cell", now = 1_000L + SmartEch.MIN_TTL_MS)
        assertEquals(2, calls)                       // stale: fetched again
    }

    @Test fun rejectionRefreshesKeyAndRetriesOnceThenFallsBackToPlainTls() {
        var key = 1
        SmartEch.doh = { dnsAnswer(configList("cloudflare-ech.com", key)) }
        SmartEch.refresh("cell")
        val first = SmartEch.decide(worker, "cell")
        assertTrue(first.applies)
        // Cloudflare rotated its key: the failure refreshes it and the next attempt retries with the new one.
        key = 2
        assertEquals(SmartEch.Next.RETRY_NEW_KEY, SmartEch.onResult("cell", first, verified = false))
        val retry = SmartEch.decide(worker, "cell")
        assertTrue(retry.applies)
        assertNotEquals(first.config, retry.config)
        // The retry fails too: ECH off here, outer name cooling, plain TLS.
        assertEquals(SmartEch.Next.PLAIN_TLS, SmartEch.onResult("cell", retry, verified = false))
        assertFalse(SmartEch.decide(worker, "cell").applies)
        assertTrue("cloudflare-ech.com" in SmartSni.cooling("cell"))
        assertEquals(worker, SmartEch.applyTo(worker, SmartEch.decide(worker, "cell")))
    }

    @Test fun unchangedKeyAfterFailureGoesStraightToPlainTls() {
        SmartEch.doh = { dnsAnswer(configList("cloudflare-ech.com")) }
        SmartEch.refresh("cell")
        val d = SmartEch.decide(worker, "cell")
        assertEquals(SmartEch.Next.PLAIN_TLS, SmartEch.onResult("cell", d, verified = false))
        assertTrue(SmartEch.state("cell").failed(System.currentTimeMillis()))
    }

    @Test fun successKeepsEch() {
        SmartEch.doh = { dnsAnswer(configList("cloudflare-ech.com")) }
        SmartEch.refresh("cell")
        val d = SmartEch.decide(worker, "cell")
        assertEquals(SmartEch.Next.OK, SmartEch.onResult("cell", d, verified = true))
        assertTrue(SmartEch.decide(worker, "cell").applies)
        val applied = SmartEch.applyTo(worker, d)
        assertEquals(d.config, applied.echConfigList)
        assertEquals("chrome", applied.fingerprint)
    }

    @Test fun manualConfigWinsOverAutomaticUntilCleared() {
        SmartEch.doh = { dnsAnswer(configList("cloudflare-ech.com")) }
        SmartEch.refresh("cell")
        val manual = b64(configList("ech.example.org", 9))
        assertEquals("not a valid base64 ECHConfigList", SmartSniSettings.setManual(SmartSniSettings.MANUAL_ECH_CONFIG, "abc"))
        assertNull(SmartSniSettings.setManual(SmartSniSettings.MANUAL_ECH_CONFIG, manual))
        val d = SmartEch.decide(worker, "cell")
        assertTrue(d.manual)
        assertEquals(manual, d.config)
        assertEquals("ech.example.org", d.outer)
        // a manual value is never turned off automatically
        SmartEch.onResult("cell", d, verified = false)
        assertEquals(manual, SmartEch.decide(worker, "cell").config)
        // even with Smart ECH off, the manual value applies; cleared, automatic is back
        SmartSniSettings.setFlag(SmartSniSettings.SMART_ECH, false)
        assertEquals(manual, SmartEch.decide(worker, "cell").config)
        SmartSniSettings.setManual(SmartSniSettings.MANUAL_ECH_CONFIG, "")
        assertFalse(SmartEch.decide(worker, "cell").applies)
        SmartSniSettings.setFlag(SmartSniSettings.SMART_ECH, true)
        assertFalse(SmartEch.decide(worker, "cell").manual)
        assertTrue(SmartEch.decide(worker, "cell").applies)
    }

    @Test fun manualOuterNameIsTheNameLookedUp() {
        val asked = mutableListOf<String>()
        SmartEch.doh = { url -> asked += url; dnsAnswer(configList("cloudflare-ech.com")) }
        assertNull(SmartSniSettings.setManual(SmartSniSettings.MANUAL_ECH_OUTER, "ech.example.org"))
        assertEquals(listOf("ech.example.org"), SmartEch.queryNames("cell"))
        SmartSniSettings.setManual(SmartSniSettings.MANUAL_ECH_OUTER, "")
        assertEquals("cloudflare-ech.com", SmartEch.queryNames("cell").first())
    }

    @Test fun blockedOuterNameSkipsEchAndConfigsOwnEchIsNeverTouched() {
        SmartEch.doh = { dnsAnswer(configList("cloudflare-ech.com")) }
        SmartEch.refresh("cell")
        SmartSni.rotate("cell", "cloudflare-ech.com")
        val d = SmartEch.decide(worker, "cell")
        assertFalse(d.applies)
        assertTrue(d.reason.contains("cooling"))
        val own = worker.copy(echConfigList = "cloudflare-ech.com+https://1.1.1.1/dns-query")
        assertFalse(SmartEch.decide(own, "cell").applies)
        assertFalse(SmartEch.cloudflareFronted(worker.copy(address = "151.101.1.1", sni = "x.example.org", host = "x.example.org")))
        assertTrue(SmartEch.cloudflareFronted(worker))
    }
}
