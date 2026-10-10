package com.example.vpn.sidecar

import com.example.data.model.VlessProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MasqueSidecarTest {
    @Test fun aMasqueProfileIsHandledByThisEngine() {
        val profile = MasqueSidecar.profile()
        assertTrue(MasqueSidecar.handles(profile))
        assertEquals("masque", MasqueSidecar.id)
        assertEquals("usque", MasqueSidecar.binary)
    }

    @Test fun anOrdinaryProfileIsNotHandled() {
        assertFalse(MasqueSidecar.handles(VlessProfile(name = "s", address = "1.2.3.4", port = 443, uuid = "x")))
    }

    @Test fun theProfileCarriesTheEngineTag() {
        assertEquals("masque", org.json.JSONObject(MasqueSidecar.profile().extraSettings).getString("engine"))
    }
}
