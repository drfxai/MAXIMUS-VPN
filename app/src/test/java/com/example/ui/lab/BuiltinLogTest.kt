package com.example.ui.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuiltinLogTest {
    @Test fun readsTimeLevelAndMessageFromLoggerLines() {
        val warn = BuiltinLog.parse("[01:40:02.123] [WARN] [BUILTIN] aether/relay · background · FAIL · cap used")
        assertEquals("01:40:02", warn.time)
        assertEquals(BuiltinLog.Level.WARN, warn.level)
        assertEquals("aether/relay · background · FAIL · cap used", warn.text)

        val info = BuiltinLog.parse("[01:40:01.000] [BUILTIN] aether/h2 · fragment 8-16 B")
        assertEquals(BuiltinLog.Level.INFO, info.level)
        assertEquals("aether/h2 · fragment 8-16 B", info.text)

        assertEquals(BuiltinLog.Level.ERR, BuiltinLog.parse("[01:40:04.000] [ERROR] [BUILTIN] aether/h3 · blocked").level)
        assertEquals(BuiltinLog.Level.ERR, BuiltinLog.parse("[01:40:04.000] [BUILTIN] aether/keys · FAIL · no route").level)
        assertEquals(BuiltinLog.Level.OK, BuiltinLog.parse("[01:40:05.000] [BUILTIN] aether/keys · wg · PASS").level)
    }

    @Test fun progressLinesWithoutStampStillCount() {
        val line = BuiltinLog.parse("Checking HTTPS through the local engine")
        assertEquals("", line.time)
        assertEquals(BuiltinLog.Level.INFO, line.level)
        val summary = BuiltinLog.summarize(listOf(
            "[01:00:00.000] [BUILTIN] serverless/fragA · PASS",
            "[01:00:01.000] [WARN] [BUILTIN] serverless/fragB · TLS reset",
            "progress without a stamp",
            " "
        ))
        assertEquals(3, summary.lines.size)
        assertEquals(1, summary.warn)
        assertEquals(0, summary.error)
        assertEquals(2, summary.info)
        assertEquals("01:00:01", summary.lastTime)
        assertNull(BuiltinLog.summarize(listOf("no stamp")).lastTime)
    }
}
