package com.example.update

import java.util.Locale

/**
 * What the update sheet shows, derived only from what [InAppUpdater] really reports (its status lines and
 * percentage) plus the size the verified release metadata declares. Pure, so every state is unit tested;
 * no progress, speed or step is ever invented.
 */
object UpdateProgressModel {
    enum class Step { DOWNLOAD, VERIFY, INSTALL }

    enum class Phase {
        /** Nothing started yet: the primary action downloads. */
        IDLE,
        /** Connecting to the release host; no bytes yet. */
        CONNECTING,
        DOWNLOADING,
        /** SHA-256 digest check after the last byte. */
        CHECKING_DIGEST,
        /** Publisher certificate and Android package check. */
        CHECKING_SIGNATURE,
        /** Verified APK in private storage: the primary action installs. */
        READY,
        FAILED
    }

    data class State(
        val phase: Phase,
        val percent: Int,
        /** Bytes received, when the updater reported them (or derived from the percentage and declared size). */
        val receivedBytes: Long?,
        val totalBytes: Long?
    ) {
        /** The step that is running now; [Step.INSTALL] once verified. */
        val activeStep: Step get() = when (phase) {
            Phase.IDLE, Phase.CONNECTING, Phase.DOWNLOADING, Phase.FAILED -> Step.DOWNLOAD
            Phase.CHECKING_DIGEST, Phase.CHECKING_SIGNATURE -> Step.VERIFY
            Phase.READY -> Step.INSTALL
        }

        /** Download and verification are done once the APK is verified; installing is Android's step. */
        fun isDone(step: Step): Boolean = step.ordinal < activeStep.ordinal

        val busy: Boolean get() = phase in setOf(Phase.CONNECTING, Phase.DOWNLOADING, Phase.CHECKING_DIGEST, Phase.CHECKING_SIGNATURE)
    }

    private val received = Regex("""Received (\d{1,12}) / (\d{1,12}) KiB""")

    fun state(
        downloading: Boolean, verified: Boolean, error: String?, status: String?, percent: Int, declaredSize: Long?
    ): State {
        val pct = percent.coerceIn(0, 100)
        val match = status?.let { received.find(it) }
        val total = match?.groupValues?.get(2)?.toLongOrNull()?.times(1024) ?: declaredSize?.takeIf { it > 0 }
        val got = match?.groupValues?.get(1)?.toLongOrNull()?.times(1024)
            ?: total?.let { it * pct / 100 }?.takeIf { pct > 0 }
        val phase = when {
            verified && !downloading -> Phase.READY
            error != null && !downloading -> Phase.FAILED
            !downloading -> Phase.IDLE
            status?.contains("SHA-256", ignoreCase = true) == true -> Phase.CHECKING_DIGEST
            status?.contains("signature", ignoreCase = true) == true -> Phase.CHECKING_SIGNATURE
            pct == 0 && match == null -> Phase.CONNECTING
            else -> Phase.DOWNLOADING
        }
        return State(phase, if (phase == Phase.READY) 100 else pct, got?.coerceAtMost(total ?: Long.MAX_VALUE), total)
    }

    /** "12.4 MB" (decimal megabytes, one decimal under 100 MB), locale-independent digits. */
    fun megabytes(bytes: Long): String {
        val mb = bytes / 1_000_000.0
        return if (mb < 100) String.format(Locale.US, "%.1f MB", mb) else String.format(Locale.US, "%.0f MB", mb)
    }

    /** Average speed over a window of (elapsed ms, bytes) samples; null until there is a meaningful window. */
    fun bytesPerSecond(samples: List<Pair<Long, Long>>): Double? {
        if (samples.size < 2) return null
        val (t0, b0) = samples.first()
        val (t1, b1) = samples.last()
        val ms = t1 - t0
        if (ms < 700 || b1 <= b0) return null
        return (b1 - b0) * 1000.0 / ms
    }

    fun speed(bytesPerSecond: Double): String =
        if (bytesPerSecond >= 1_000_000) String.format(Locale.US, "%.1f MB/s", bytesPerSecond / 1_000_000)
        else String.format(Locale.US, "%.0f KB/s", bytesPerSecond / 1_000)

    /** "~2 min left" / "~35 s left"; null when it cannot be estimated honestly. */
    fun eta(remainingBytes: Long, bytesPerSecond: Double?): String? {
        val rate = bytesPerSecond?.takeIf { it > 1 } ?: return null
        if (remainingBytes <= 0) return null
        val seconds = (remainingBytes / rate).toLong()
        return when {
            seconds > 6 * 3600 -> null
            seconds >= 90 -> "~${(seconds + 30) / 60} min left"
            else -> "~${seconds.coerceAtLeast(1)} s left"
        }
    }
}
