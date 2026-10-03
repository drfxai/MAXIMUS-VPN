package com.example

import com.example.data.model.VlessProfile
import com.example.panels.BpbFix
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BpbFixTest {
    private val host = "maximus-bpb-abc.acme.workers.dev"
    private val base = VlessProfile(
        name = "BPB", address = host, port = 443, uuid = "8b0e2c4a-9f6d-4c1e-a7b3-2d5f8e1c0a9b",
        transport = "ws", security = "tls", sni = host, host = host, path = "/vl/abc?ed=2560", fingerprint = "chrome"
    )

    @Test
    fun appliesAllFourSettingsToTlsConfigs() {
        val fixed = BpbFix.apply(base)
        assertEquals("unsafe", fixed.fingerprint)
        assertEquals("http/1.1", fixed.alpn)
        assertEquals(BpbFix.CIPHER_SUITES, fixed.cipherSuites)
        assertEquals(13, fixed.cipherSuites.split(':').size)
        val mask = JSONObject(fixed.finalMask).getJSONArray("tcp")
        assertEquals(2, mask.length())
        assertEquals("tlshello", mask.getJSONObject(0).getJSONObject("settings").getString("packets"))
        assertEquals("11", mask.getJSONObject(1).getJSONObject("settings").getString("maxSplit"))
        assertTrue(BpbFix.isApplied(fixed))
        // Identity of the config is untouched.
        assertEquals(base.uuid, fixed.uuid)
        assertEquals(base.path, fixed.path)
    }

    @Test
    fun leavesNonTlsConfigsAlone() {
        val plain = base.copy(port = 80, security = "none")
        assertEquals(plain, BpbFix.apply(plain))
    }

    @Test
    fun recognisesConfigsOfTheWorker() {
        assertTrue(BpbFix.belongsTo(base, host))
        assertTrue(BpbFix.belongsTo(base.copy(address = "104.16.1.2"), host)) // clean-IP variant
        assertTrue(BpbFix.belongsTo(base.copy(address = "1.1.1.1", host = "", sni = "", sourceSubscription = "https://$host/x/sub/raw?app=xray"), host))
        assertFalse(BpbFix.belongsTo(base.copy(address = "1.1.1.1", host = "other.dev", sni = "other.dev"), host))
    }
}
