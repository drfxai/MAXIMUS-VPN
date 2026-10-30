package com.example.vpn.lab

import org.junit.Assert.assertEquals
import org.junit.Test

/** Smart Connect races first what the latest Full Analysis on this network found working. */
class LabProvenOnTest {
    private val net = "cell:43235"
    private fun method(id: String, status: PathStatus, stage: ConnectionStage = ConnectionStage.APPLICATION_REQUEST_PASSED, why: String = "passed") =
        RankedMethod(id, "N$id", PathFamily.VLESS_REALITY, status, stage, 500, 0.8, why, 3, 3)

    private fun report(key: String, finishedAt: Long, vararg m: RankedMethod) = AnalysisReport("NS-1", key, "Irancell", 0, finishedAt,
        "Filtered", 0.6, "Ordinary", emptyList(), emptyList(), emptyList(), m.toList(), emptyList(), "Use a.")

    @Test fun workingSavedConfigsOfTheLatestRunOnThisNetworkOnly() {
        val now = 10_000_000L
        val here = report("$net|IPv4", now - 60_000,
            method("a", PathStatus.VERIFIED), method("cut", PathStatus.DEGRADED), method("dead", PathStatus.FAILED, ConnectionStage.TCP_REACHED),
            method("copy", PathStatus.VERIFIED, why = "safe copy of a"), method("b", PathStatus.CANDIDATE))
        val elsewhere = report("wifi:xyz|IPv4", now, method("w", PathStatus.VERIFIED))
        assertEquals(listOf("a", "b"), FullAnalysis.provenOn(listOf(here, elsewhere), net, now))
        assertEquals(emptyList<String>(), FullAnalysis.provenOn(listOf(here), "cell:43211", now))
    }

    @Test fun anOldRunNoLongerSteers() {
        val now = 10L * FullAnalysis.PROVEN_MAX_AGE_MS
        val old = report("$net|IPv4", now - FullAnalysis.PROVEN_MAX_AGE_MS, method("a", PathStatus.VERIFIED))
        assertEquals(emptyList<String>(), FullAnalysis.provenOn(listOf(old), net, now))
    }
}
