package com.example

import com.example.data.model.OperationalMode
import com.example.vpn.safety.MaximusVpnSupervisor
import com.example.vpn.safety.MaximusVpnSupervisor.Lockdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** V1.0.1 step 3: protection is released only for a named reason, and lockdown is reported. */
class VpnSupervisorTest {
    @Test fun protectionHoldsUntilReleasedForAReason() {
        val log = mutableListOf<String>()
        val s = MaximusVpnSupervisor { log += it }
        assertFalse(s.protectionRequested)
        s.requestProtection()
        s.requestProtection()
        assertTrue(s.protectionRequested)
        s.release(MaximusVpnSupervisor.Release.USER_DISCONNECT)
        assertFalse(s.protectionRequested)
        assertEquals(MaximusVpnSupervisor.Release.USER_DISCONNECT, s.lastRelease)
        assertEquals(listOf("Traffic protection released: user disconnect"), log)
    }

    @Test fun lockdownIsReadOnlyWhereAndroidCanTell() {
        assertEquals(Lockdown.UNKNOWN, MaximusVpnSupervisor.lockdownOf(28, { error("not on 28") }, { error("not on 28") }))
        assertEquals(Lockdown.ON, MaximusVpnSupervisor.lockdownOf(34, { true }, { true }))
        assertEquals(Lockdown.ALWAYS_ON_ONLY, MaximusVpnSupervisor.lockdownOf(34, { true }, { false }))
        assertEquals(Lockdown.OFF, MaximusVpnSupervisor.lockdownOf(34, { false }, { false }))
        assertEquals(Lockdown.OFF, MaximusVpnSupervisor.lockdownOf(34, { error("x") }, { error("x") }))
    }

    @Test fun godModeAsksForLockdownAndDailyOnlyMentionsWhatIsKnown() {
        assertNull(MaximusVpnSupervisor.lockdownAdvice(Lockdown.ON, OperationalMode.GOD_MODE))
        assertNotNull(MaximusVpnSupervisor.lockdownAdvice(Lockdown.UNKNOWN, OperationalMode.GOD_MODE))
        assertNotNull(MaximusVpnSupervisor.lockdownAdvice(Lockdown.OFF, OperationalMode.DAILY))
        assertNull(MaximusVpnSupervisor.lockdownAdvice(Lockdown.UNKNOWN, OperationalMode.DAILY))
    }
}
