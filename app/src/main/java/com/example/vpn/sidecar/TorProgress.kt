package com.example.vpn.sidecar

import java.io.File
import java.io.RandomAccessFile

/**
 * Reads the notice log Tor writes in its own process and reports how far it got, so the app's log shows
 * where a start stalls (reaching the Snowflake broker, finding a volunteer, building a circuit) instead
 * of only "did not start" after two minutes.
 */
class TorProgress(private val file: File) {
    private var offset = 0L
    private var partial = ""

    /** The lines worth showing that Tor wrote since the last call. */
    fun newLines(): List<String> {
        if (!file.exists()) return emptyList()
        val text = runCatching {
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < offset) offset = 0
                raf.seek(offset)
                val bytes = ByteArray((raf.length() - offset).coerceIn(0, MAX_READ.toLong()).toInt())
                raf.readFully(bytes)
                offset += bytes.size
                String(bytes, Charsets.UTF_8)
            }
        }.getOrDefault("")
        val lines = (partial + text).split('\n')
        partial = lines.last()
        return lines.dropLast(1).mapNotNull(::summary)
    }

    companion object {
        private const val MAX_READ = 64 * 1024

        /** "Bootstrapped 10% (conn_done): Connected to a relay" and warnings; other notices are noise. */
        internal fun summary(line: String): String? {
            val body = line.substringAfter("] ", line).trim()
            return when {
                "Bootstrapped " in body -> body.substringAfter("Bootstrapped ")
                    .let { "Tor ${it.substringBefore(' ')}: ${it.substringAfter(": ", "").ifEmpty { it }}" }
                line.contains("[warn]") || line.contains("[err]") -> "Tor warning: ${body.take(200)}"
                else -> null
            }
        }
    }
}

/**
 * How long a Tor start may take. Over Snowflake on a filtered network Tor can still be loading relay
 * descriptors when the first [baseMs] run out, so each higher bootstrap percentage buys [stallMs] more
 * from that moment, never past [capMs] from the start. A start that stops advancing ends as before.
 */
class TorStartDeadline(
    private val startedAt: Long,
    baseMs: Long,
    private val stallMs: Long = STALL_MS,
    private val capMs: Long = CAP_MS
) {
    private var deadline = startedAt + baseMs
    private var best = -1

    /** Reads one line from [TorProgress.newLines]; a higher "Tor N%" moves the deadline out. */
    fun saw(line: String, now: Long) {
        val percent = percentOf(line) ?: return
        if (percent <= best) return
        best = percent
        deadline = maxOf(deadline, minOf(now + stallMs, startedAt + capMs))
    }

    fun expired(now: Long): Boolean = now >= deadline

    companion object {
        const val STALL_MS = 150_000L
        const val CAP_MS = 360_000L

        internal fun percentOf(line: String): Int? =
            Regex("^Tor (\\d{1,3})%:").find(line)?.groupValues?.get(1)?.toIntOrNull()
    }
}
