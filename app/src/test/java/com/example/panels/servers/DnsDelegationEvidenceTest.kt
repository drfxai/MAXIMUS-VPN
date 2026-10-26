package com.example.panels.servers

import org.junit.Assert.*
import org.junit.Test

class DnsDelegationEvidenceTest {
    private val records = DnsDelegation.records("example.com", "tun", "192.0.2.10")
    @Test fun nodataIsMissingWithoutInventingPropagationAsTheCause() {
        val checks = DnsDelegation.judge(records, """{"Status":0,"Answer":[]}""", """{"Status":0,"Authority":[{"type":6,"data":"parent SOA"}]}""")
        assertTrue(checks.all { it.status == DnsDelegation.Status.MISSING })
        assertTrue(checks[1].detail.contains("parent zone NS record"))
        assertFalse(checks.any { it.detail.contains("few minutes") })
    }
    @Test fun servfailDoesNotProveServerReachability() {
        val checks = DnsDelegation.judge(records, """{"Status":2}""", """{"Status":2}""")
        assertTrue(checks.all { it.status == DnsDelegation.Status.UNKNOWN })
        assertTrue(checks[1].detail.contains("does not prove"))
    }
    @Test fun matchingRecordsAreReportedAsDnsOnlyNotTrafficVerification() {
        val checks = DnsDelegation.judge(records,
            """{"Status":0,"Answer":[{"type":1,"data":"192.0.2.10"}]}""",
            """{"Status":0,"Answer":[{"type":2,"data":"ns.example.com."}]}""")
        assertTrue(checks.all { it.status == DnsDelegation.Status.OK })
    }
}
