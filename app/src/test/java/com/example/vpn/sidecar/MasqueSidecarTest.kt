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

    @Test fun aFailedRegistrationReportsTheLastLinesNotTheMissingConfigWarning() {
        val out = "2026/10/18 14:01:51 Config file not found: failed to open config file: open /data/x/usque.json: no such file or directory\n" +
            "2026/10/18 14:01:51 Registering with locale en_US and model PC\n" +
            "2026/10/18 14:02:21 Failed to register: Post \"https://api.cloudflareclient.com/v0a4471/reg\": i/o timeout\n"
        assertEquals("Registering with locale en_US and model PC; Failed to register: Post \"https://api.cloudflareclient.com/v0a4471/reg\": i/o timeout",
            MasqueSidecar.registrationReason(out))
        assertEquals("usque gave no reason", MasqueSidecar.registrationReason("2026/10/18 14:01:51 Config file not found: x\n"))
    }
}
