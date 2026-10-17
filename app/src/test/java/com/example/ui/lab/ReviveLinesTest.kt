package com.example.ui.lab

import com.example.vpn.lab.ConfigRevival
import com.example.vpn.lab.RevivalTriage
import org.junit.Assert.assertEquals
import org.junit.Test

/** Each revival result says why the config failed as it was and what was done, in plain words. */
class ReviveLinesTest {
    private fun result(outcome: ConfigRevival.Outcome, detail: String, cause: RevivalTriage.Cause? = null, tried: Int = 0) =
        ConfigRevival.Result("p", "Config", outcome, detail, tried = tried, cause = cause)

    @Test fun aRevivedConfigNamesTheCauseAndTheRecipe() {
        val l = ReviveLines.of(result(ConfigRevival.Outcome.REVIVED, "ECH via Cloudflare + Chrome fingerprint · used next time it connects",
            RevivalTriage.Cause.CONNECTION_CUT, tried = 3))
        assertEquals("Cut during the handshake → ECH via Cloudflare + Chrome fingerprint · used next time it connects", l.text)
        assertEquals("Revived", l.badge)
        assertEquals(ReviveLines.Tone.GOOD, l.tone)
    }

    @Test fun aNameThatDoesNotResolveWithNoAddressIsSkippedNotFailed() {
        val l = ReviveLines.of(result(ConfigRevival.Outcome.NOT_REVIVED,
            "the server name does not resolve here; only another address could help and none is validated on this network",
            RevivalTriage.Cause.NAME_NOT_RESOLVED))
        assertEquals("The name does not resolve here. Only another address could help, and none is validated on this network.", l.text)
        assertEquals("Skipped", l.badge)
        assertEquals(ReviveLines.Tone.WARN, l.tone)
    }

    @Test fun recipesThatAllFailedAreCounted() {
        val l = ReviveLines.of(result(ConfigRevival.Outcome.NOT_REVIVED, "no recipe got a request through (10 tried)", RevivalTriage.Cause.UNKNOWN, tried = 10))
        assertEquals("Cause not clear · 10 recipes tried, none got a request through", l.text)
        assertEquals("Not revived", l.badge)
        assertEquals(ReviveLines.Tone.BAD, l.tone)
        assertEquals("Certificate refused · 1 recipe tried, none got a request through",
            ReviveLines.of(result(ConfigRevival.Outcome.NOT_REVIVED, "x", RevivalTriage.Cause.CERTIFICATE_REFUSED, tried = 1)).text)
    }

    @Test fun aWorkingConfigIsLeftAlone() {
        val l = ReviveLines.of(result(ConfigRevival.Outcome.ALREADY_WORKING, "answers as it is; nothing was changed"))
        assertEquals("Answers as it is; nothing was changed", l.text)
        assertEquals("Working", l.badge)
        assertEquals(ReviveLines.Tone.INFO, l.tone)
    }

    @Test fun everyCauseHasAPlainLine() {
        RevivalTriage.Cause.values().forEach { c ->
            val l = ReviveLines.of(result(ConfigRevival.Outcome.REVIVED, "Clean Cloudflare IP 188.114.97.3 · used next time it connects", c, tried = 1))
            assertEquals("${ReviveLines.cause(c)} → Clean Cloudflare IP 188.114.97.3 · used next time it connects", l.text)
        }
    }
}
