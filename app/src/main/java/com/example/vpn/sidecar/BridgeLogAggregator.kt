package com.example.vpn.sidecar

/** Diagnostic-only aggregation. Input must already be redacted; never controls bridge retries. */
internal class BridgeLogAggregator(
    private val emit: (String) -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val windowMs: Long = 30_000
) {
    private var last: String? = null
    private var repeated = 0L
    private var lastEmission = 0L

    fun accept(redacted: String) {
        val now = clock()
        if (last == redacted) {
            repeated++
            if (now - lastEmission >= windowMs) {
                flush()
                lastEmission = now
            }
            return
        }
        flush()
        last = redacted
        lastEmission = now
        emit(redacted)
    }

    fun flush() {
        if (repeated > 0) emit("Repeated bridge log " + repeated + " additional time(s): " + last)
        repeated = 0
    }
}
