package com.example.vpn.stealth.recordslice

import com.example.data.model.AppSettings
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.vpn.smart.FreezeFailover
import com.example.vpn.smart.UplinkShieldBook
import com.example.vpn.stealth.StealthVariants
import com.example.xray.XrayConfigBuilder
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.random.Random

/** Record slicing as a stealth vector (config through the bridge) and the Uplink Shield as a probed per-network rescue. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecordSliceConfigTest {
    private val reality = VlessProfile(
        id = "r1", name = "REALITY", address = "203.0.113.10", port = 443,
        uuid = "11111111-2222-3333-4444-555555555555", transport = "tcp", security = "reality",
        sni = "www.example.com", publicKey = "pbk", shortId = "ab", flow = "xtls-rprx-vision", fingerprint = "chrome",
        createdAt = 0L
    )
    private val tls = reality.copy(id = "t1", security = "tls", publicKey = "", shortId = "", flow = "", transport = "ws",
        sni = "edge.example.com", host = "edge.example.com")

    @After fun cleanUp() {
        RecordSliceBridges.closeAll()
        UplinkShield.networkMss = null
    }

    private fun proxy(root: JSONObject): JSONObject {
        val outbounds = root.getJSONArray("outbounds")
        return (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.first { it.optString("tag") == "proxy" }
    }

    @Test fun realityAndTlsGetTheThreeRecordVectorsAsLastAlternatesButNeverEch() {
        for (p in listOf(reality, tls)) {
            val keys = StealthVariants.of(p).map { it.key }
            val expected = StealthVariants.RECORD_SLICE_ORDER.map { StealthVariants.recordSliceKey(it) }
            assertEquals(expected, keys.takeLast(3))
        }
        val ech = tls.copy(echConfigList = "edge.example.com+${StealthVariants.ECH_DNS}")
        assertTrue(StealthVariants.of(ech).none { it.key.startsWith(StealthVariants.RECORD_SLICE_PREFIX) })
        assertFalse(RecordSliceConfig.eligible(tls.copy(alpn = "h3")))
        assertFalse(RecordSliceConfig.eligible(reality.copy(protocolType = ProtocolType.HYSTERIA2)))
    }

    @Test fun theVariantDialsThroughTheBridgeAndDropsFragmentMasks() {
        val masked = reality.copy(finalMask = """{"tcp":[{"type":"fragment","settings":{"packets":"1-3","length":"100-200","delay":"10-20"}}]}""")
        val variant = StealthVariants.of(masked).first { it.key == StealthVariants.recordSliceKey(TlsRecordSlicer.Vector.SNI_DOT_3) }.profile
        val root = JSONObject(XrayConfigBuilder.buildJson(variant, AppSettings()))
        val stream = proxy(root).getJSONObject("streamSettings")
        assertEquals(RecordSliceConfig.DIALER_TAG, stream.getJSONObject("sockopt").getString("dialerProxy"))
        assertFalse(stream.has("finalmask"))
        // REALITY settings are untouched: Xray still does the REALITY handshake end to end.
        assertEquals("www.example.com", stream.getJSONObject("realitySettings").getString("serverName"))
        val outbounds = root.getJSONArray("outbounds")
        val dialer = (0 until outbounds.length()).map { outbounds.getJSONObject(it) }.first { it.optString("tag") == RecordSliceConfig.DIALER_TAG }
        assertEquals("freedom", dialer.getString("protocol"))
        val port = dialer.getJSONObject("settings").getString("redirect").removePrefix("127.0.0.1:").toInt()
        assertEquals(port, RecordSliceBridges.portFor(reality.address, reality.port, RecordSliceConfig.spec(variant)!!))
    }

    @Test fun theUplinkShieldSetsXraysOwnTcpMaxSeg() {
        val shielded = UplinkShield.enable(reality)
        assertEquals(UplinkShield.MSS, UplinkShield.mss(shielded))
        val stream = proxy(JSONObject(XrayConfigBuilder.buildJson(shielded, AppSettings()))).getJSONObject("streamSettings")
        assertEquals(UplinkShield.MSS, stream.getJSONObject("sockopt").getInt("tcpMaxSeg"))
        // Not on a config that did not ask, unless the network's memory says the shield helped here.
        val plain = proxy(JSONObject(XrayConfigBuilder.buildJson(reality, AppSettings()))).getJSONObject("streamSettings")
        assertFalse(plain.getJSONObject("sockopt").has("tcpMaxSeg"))
        UplinkShield.networkMss = UplinkShield.MSS
        val auto = proxy(JSONObject(XrayConfigBuilder.buildJson(reality, AppSettings()))).getJSONObject("streamSettings")
        assertEquals(UplinkShield.MSS, auto.getJSONObject("sockopt").getInt("tcpMaxSeg"))
        assertFalse(UplinkShield.canShield(reality.copy(protocolType = ProtocolType.HYSTERIA2)))
    }

    @Test fun shieldChunksCoverTheWindowInMssSizedPieces() {
        val sizes = UplinkShield.chunkSizes(UplinkShield.WINDOW_BYTES, UplinkShield.MSS, Random(9))
        assertEquals(UplinkShield.WINDOW_BYTES, sizes.sum())
        assertTrue(sizes.all { it in 1..UplinkShield.MSS + UplinkShield.JITTER })
        assertTrue(sizes.dropLast(1).all { it >= UplinkShield.MSS - UplinkShield.JITTER })
    }

    @Test fun anUploadStallAtSixteenKbTriesTheShieldFirstEvenOnReality() {
        val input = FreezeFailover.Input(reality, reality, pinned = false, rescueTried = false, switchesSoFar = 0, uplinkStall = true)
        val d = FreezeFailover.decide(input)
        assertTrue(d is FreezeFailover.Decision.Retry)
        d as FreezeFailover.Decision.Retry
        assertEquals(FreezeFailover.Rescue.UPLINK_SHIELD, d.rescue)
        assertEquals(UplinkShield.MSS, UplinkShield.mss(d.variant))
        assertEquals(reality.id, d.variant.id)
        // Not where it just failed to help, and never twice.
        assertFalse((FreezeFailover.decide(input.copy(shieldMayTry = false)) as? FreezeFailover.Decision.Retry)?.rescue == FreezeFailover.Rescue.UPLINK_SHIELD)
        assertFalse(FreezeFailover.decide(input.copy(running = d.variant, server = d.variant)).let { it is FreezeFailover.Decision.Retry && it.rescue == FreezeFailover.Rescue.UPLINK_SHIELD })
        // Without the upload signature the old rescue order is unchanged (REALITY: no deep fragment).
        assertFalse(FreezeFailover.decide(input.copy(uplinkStall = false)) is FreezeFailover.Decision.Retry)
    }

    @Test fun theShieldResultIsKeptPerNetwork() {
        var now = 1_000_000L
        var saved: String? = null
        val book = UplinkShieldBook({ saved }, { saved = it }, { now })
        assertTrue(book.mayTry("cell:43235"))
        assertFalse(book.active("cell:43235"))
        book.record("cell:43235", helped = true)
        book.record("cell:43211", helped = false)
        assertTrue(book.active("cell:43235"))
        assertFalse(book.mayTry("cell:43211"))
        assertTrue(book.mayTry("wifi"))
        // Read back from storage.
        val again = UplinkShieldBook({ saved }, { saved = it }, { now })
        assertTrue(again.active("cell:43235"))
        assertEquals("no-help", again.describe("cell:43211"))
        now += UplinkShieldBook.NO_HELP_COOLDOWN_MS + 1
        assertTrue(again.mayTry("cell:43211"))
        now += UplinkShieldBook.TTL_MS
        assertFalse(again.active("cell:43235"))
        assertNull(RecordSliceConfig.spec(reality))
    }
}
