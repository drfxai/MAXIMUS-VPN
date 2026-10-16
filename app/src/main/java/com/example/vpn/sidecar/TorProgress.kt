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
