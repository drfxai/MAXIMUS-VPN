package com.example.vpn.sidecar

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Build 181 3.3: Shir o Khorshid personal pairing (InproxyClientPersonalCompartmentID) and Iranian conduits by tier. */
class PersonalPairingTest {
    private val id = "UJYY3duvsjc16qRgjgf/zCcE5uaNz3GHnWiUzBeAqAc"
    private val settings = JSONObject()
        .put("PropagationChannelId", "TEST-CHANNEL")
        .put("SponsorId", "TEST-SPONSOR")
        .put("RemoteServerListURLs", JSONArray().put(JSONObject().put("URL", "aHR0cHM6Ly9leGFtcGxlLmludmFsaWQvbGlzdA==")))
        .put("RemoteServerListSignaturePublicKey", "TEST-KEY")

    private fun strings(a: JSONArray?) = a?.let { (0 until it.length()).map { i -> it.optString(i) } }.orEmpty()

    @Test fun pairingIdIs32BytesOfUnpaddedBase64() {
        assertTrue(ShirokhorshidSidecar.validPairingId(id))
        assertFalse(ShirokhorshidSidecar.validPairingId(id.dropLast(1)))
        assertFalse(ShirokhorshidSidecar.validPairingId(id.dropLast(1) + "B")) // low bits set: 258-bit value
        assertFalse(ShirokhorshidSidecar.validPairingId("$id="))
    }

    @Test fun pairingIsFoundInAPastedLinkOrMessage() {
        assertEquals(id, ShirokhorshidSidecar.parsePairing(id))
        assertEquals(id, ShirokhorshidSidecar.parsePairing("  $id \n"))
        val urlSafe = id.replace('/', '_')
        assertEquals(id, ShirokhorshidSidecar.parsePairing("https://example.invalid/pair?c=$urlSafe"))
        assertEquals(id, ShirokhorshidSidecar.parsePairing("conduit link: https://example.invalid/#" + java.net.URLEncoder.encode(id, "UTF-8")))
        assertNull(ShirokhorshidSidecar.parsePairing("hello"))
    }

    @Test fun pairingGoesIntoTheConfigAndKeepsInProxyProtocols() {
        val options = ShirokhorshidSidecar.Options(mode = ShirokhorshidSidecar.Mode.DIRECT, personalPairing = id)
        assertNull(options.problem())
        val base = PsiphonSidecar.buildConfig(JSONObject(settings.toString()), "", 40123, File("/tmp/b181-sok-pair"))
        val out = ShirokhorshidSidecar.applyOptions(base, options, rejectCidrs = listOf("2.176.0.0/12"))
        assertEquals(id, out.optString("InproxyClientPersonalCompartmentID"))
        assertEquals(ShirokhorshidSidecar.CONDUIT_PROTOCOLS, strings(out.optJSONArray("LimitTunnelProtocols")))
        assertEquals(listOf("2.176.0.0/12"), strings(out.optJSONArray("InproxyRejectProxyCIDRs")))
        // round trip through the profile extras
        val back = ShirokhorshidSidecar.Options.read(options.writeTo(JSONObject()))
        assertEquals(id, back.personalPairing)
        assertNotNull(ShirokhorshidSidecar.Options(personalPairing = "bad").problem())
    }

    @Test fun noPairingLeavesTheSharedPool() {
        val base = PsiphonSidecar.buildConfig(JSONObject(settings.toString()), "", 40123, File("/tmp/b181-sok-nopair"))
        val out = ShirokhorshidSidecar.applyOptions(base, ShirokhorshidSidecar.Options())
        assertFalse(out.has("InproxyClientPersonalCompartmentID"))
    }

    @Test fun iranianConduitsAreAllowedOnlyOnceAfterTheTierSaysSo() {
        ShirokhorshidSidecar.iranConduitsAllowedOnce = true
        assertTrue(ShirokhorshidSidecar.takeIranConduitsAllowed())
        assertFalse(ShirokhorshidSidecar.takeIranConduitsAllowed())
    }
}
