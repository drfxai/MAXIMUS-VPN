package com.example.vpn.ghost

import com.example.data.model.VlessProfile
import com.example.vpn.sidecar.TorSidecar
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GhostRiderControllerTest {
    @Test fun aSavedTorBridgeConfigCountsAsAlreadyRoutedThroughTor() {
        assertTrue(GhostRiderController.carriesTor(TorSidecar.profile(name = "Tor WebTunnel (seed 2)")))
        assertFalse(GhostRiderController.carriesTor(VlessProfile(name = "NL", address = "203.0.113.4", port = 443, uuid = "u")))
    }
}
