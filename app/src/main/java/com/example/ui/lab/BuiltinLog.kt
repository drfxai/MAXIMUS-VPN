package com.example.ui.lab

/**
 * Reads the built-in engine lines that [com.example.xray.XrayLogManager] keeps ("[HH:mm:ss.SSS] [WARN] [BUILTIN] aether/h2 · …")
 * and the bare progress lines a manual check reports, so Diagnostics can colour and count them by level.
 */
internal object BuiltinLog {
    enum class Level { INFO, OK, WARN, ERR }

    data class Line(val time: String, val level: Level, val text: String)

    data class Summary(val lines: List<Line>) {
        val warn: Int get() = lines.count { it.level == Level.WARN }
        val error: Int get() = lines.count { it.level == Level.ERR }
        val info: Int get() = lines.size - warn - error
        val issues: Int get() = warn + error
        /** Time of the newest line that carries one, or null when none does. */
        val lastTime: String? get() = lines.lastOrNull { it.time.isNotEmpty() }?.time
    }

    private val stamp = Regex("""^\[(\d{2}:\d{2}:\d{2})(?:\.\d+)?]\s*""")
    private val bracket = Regex("""^\[([A-Za-z_]+)]\s*""")
    private val levels = setOf("DEBUG", "INFO", "WARN", "ERROR", "FATAL")
    private val failWord = Regex("""\b(FAIL|FAILED|ERROR)\b""")
    private val passWord = Regex("""\b(PASS|PASSED|OK)\b""")
    private val warnWord = Regex("""\b(WARN|WARNING)\b""")

    fun parse(raw: String): Line {
        var rest = raw.trim()
        var time = ""
        stamp.find(rest)?.let { time = it.groupValues[1]; rest = rest.substring(it.range.last + 1) }
        var tagged: String? = null
        // At most a level tag and the logger tag ("[WARN] [BUILTIN]") precede the message.
        repeat(2) {
            bracket.find(rest)?.let { m ->
                val value = m.groupValues[1]
                if (value in levels) tagged = value
                rest = rest.substring(m.range.last + 1)
            }
        }
        val level = when {
            tagged == "ERROR" || tagged == "FATAL" -> Level.ERR
            tagged == "WARN" -> Level.WARN
            failWord.containsMatchIn(rest) -> Level.ERR
            warnWord.containsMatchIn(rest) -> Level.WARN
            passWord.containsMatchIn(rest) -> Level.OK
            else -> Level.INFO
        }
        return Line(time, level, rest)
    }

    fun summarize(raw: List<String>): Summary = Summary(raw.filter { it.isNotBlank() }.map(::parse))
}
