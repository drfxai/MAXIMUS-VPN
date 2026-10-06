package com.example

import com.example.vpn.diagnostics.events.RuntimeHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 4: which earlier exits are reported, and how much of an ANR dump is kept. */
class RuntimeHealthTest {
    @Test fun crashesAnrsAndMemoryKillsAreProblemsUserExitsAreNot() {
        assertEquals("ANR", RuntimeHealth.reasonName(6))
        assertEquals("CRASH_NATIVE", RuntimeHealth.reasonName(5))
        assertTrue(RuntimeHealth.isProblem(RuntimeHealth.reasonName(4)))
        assertTrue(RuntimeHealth.isProblem(RuntimeHealth.reasonName(3)))
        assertFalse(RuntimeHealth.isProblem(RuntimeHealth.reasonName(10)))
        assertFalse(RuntimeHealth.isProblem(RuntimeHealth.reasonName(1)))
    }

    @Test fun onlyTheMainThreadOfAnAnrDumpIsKept() {
        val dump = "----- pid 1 -----\n\"Signal Catcher\" daemon\n  at x\n\n\"main\" prio=5 tid=1 Blocked\n  at com.example.Ui.draw\n\n\"other\" tid=9\n  at y\n"
        val part = RuntimeHealth.mainThreadPart(dump)
        assertTrue(part.startsWith("\"main\""))
        assertTrue(part.contains("Ui.draw"))
        assertFalse(part.contains("other"))
    }
}
