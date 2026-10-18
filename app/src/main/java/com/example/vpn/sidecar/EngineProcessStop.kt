package com.example.vpn.sidecar

/** Cleanup must finish even when runInterruptible has interrupted the engine's worker thread. */
internal object EngineProcessStop {
    fun stop(process: Process, graceMs: Long = 500, force: (Process) -> Unit = {
        if (android.os.Build.VERSION.SDK_INT >= 26) it.destroyForcibly() else it.destroy()
    }) {
        var interrupted = Thread.interrupted()
        try {
            runCatching { process.outputStream.close() }
            process.destroy()
            val deadline = System.nanoTime() + graceMs * 1_000_000
            while (alive(process) && System.nanoTime() < deadline) {
                try { Thread.sleep(20) } catch (_: InterruptedException) { interrupted = true }
            }
            if (alive(process)) force(process)
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun alive(process: Process): Boolean = try {
        process.exitValue(); false
    } catch (_: IllegalThreadStateException) { true }
}
