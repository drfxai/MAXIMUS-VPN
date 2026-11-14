package com.example.vpn.smart

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Mobile data starts at 1400 (1280 for WireGuard/MASQUE); Wi-Fi keeps the setting; the MTU trial stays consistent. */
class TunMtuDefaultsTest {
    @Test fun mobileDataDefaultsTo1400() {
        assertEquals(1400, TunMtuDefaults.base(1500, "cell:43235", wireGuardPath = false))
        assertEquals(1400, TunMtuDefaults.base(1500, "cell", wireGuardPath = false))
    }

    @Test fun wireGuardAndMasqueOnMobileDataUse1280() {
        assertEquals(1280, TunMtuDefaults.base(1500, "cell:43235", wireGuardPath = true))
        assertEquals(1280, TunMtuDefaults.base(1400, "cell:43211", wireGuardPath = true))
    }

    @Test fun wifiAndEthernetKeepTheSetting() {
        assertEquals(1500, TunMtuDefaults.base(1500, "wifi:abcdef012345", wireGuardPath = false))
        assertEquals(1500, TunMtuDefaults.base(1500, "wifi", wireGuardPath = true))
        assertEquals(1460, TunMtuDefaults.base(1460, "ethernet:0123", wireGuardPath = false))
    }

    @Test fun aLowerValueTheUserChoseIsKeptAndOutOfRangeIsClamped() {
        assertEquals(1320, TunMtuDefaults.base(1320, "cell:43235", wireGuardPath = false))
        assertEquals(1280, TunMtuDefaults.base(900, "cell:43235", wireGuardPath = false))
        assertEquals(1500, TunMtuDefaults.base(9000, "wifi", wireGuardPath = false))
    }

    @Test fun theTrialWorksFromTheCellularBase() {
        var stored: String? = null
        val trial = TunMtuTrial({ stored }, { stored = it }, { 1_000L })
        val net = "cell:43235"
        val base = TunMtuDefaults.base(1500, net, wireGuardPath = false)
        assertEquals(1400, trial.mtuFor(net, base))
        // A stall at 1400 tests 1280 next; it does not go back to 1500.
        assertEquals(TunMtuTrial.State.TRYING_LOW, trial.onBulkCheck(net, base, base, stalled = true, passed = false))
        assertEquals(TunMtuTrial.LOW_MTU, trial.mtuFor(net, base))
        assertEquals(TunMtuTrial.State.LOW_DID_NOT_HELP, trial.onBulkCheck(net, TunMtuTrial.LOW_MTU, base, stalled = true, passed = false))
        assertEquals(1400, trial.mtuFor(net, base))
    }

    @Test fun atTheWireGuardBaseThereIsNothingLowerToTry() {
        var stored: String? = null
        val trial = TunMtuTrial({ stored }, { stored = it }, { 1_000L })
        val net = "cell:43235"
        val base = TunMtuDefaults.base(1500, net, wireGuardPath = true)
        assertNull(trial.onBulkCheck(net, base, base, stalled = true, passed = false))
        assertEquals(1280, trial.mtuFor(net, base))
    }
}
