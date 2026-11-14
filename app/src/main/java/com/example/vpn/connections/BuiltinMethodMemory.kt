package com.example.vpn.connections

/** Only local HTTPS evidence. Never represents device-level TUN verification. */
object BuiltinMethodMemory {
    @Volatile var read: (String) -> String? = { null }
    @Volatile var write: (String, String?) -> Unit = { _, _ -> }
    @Volatile var network: () -> String? = { null }
    const val TTL_MS = 6 * 60 * 60 * 1000L

    fun preferred(scope: String?, methods: List<String>, now: Long = System.currentTimeMillis()): String? {
        if (scope == null) return null
        val saved = read(scope)?.split('|') ?: return null
        val at = saved.getOrNull(1)?.toLongOrNull() ?: return null
        val age = now - at
        val method = saved.firstOrNull()?.takeIf { it in methods && age in 0 until TTL_MS }
        if (method == null) write(scope, null)
        return method
    }
    fun record(scope: String?, method: String, now: Long = System.currentTimeMillis()) {
        if (scope != null) write(scope, "$method|$now")
    }
    fun forget(scope: String?) { if (scope != null) write(scope, null) }

    /**
     * Methods that failed on this scope (same network, engine and options) in the last [FAILED_TTL_MS].
     * Only an ordering hint: a recently failed method is tried last, never skipped, so one bad minute on
     * a network cannot lock a method out (PattNG keeps the endpoint its scan found in the same spirit).
     */
    const val FAILED_TTL_MS = 30 * 60 * 1000L

    private fun failedKey(scope: String) = "$scope#failed"

    fun recentlyFailed(scope: String?, now: Long = System.currentTimeMillis()): Set<String> {
        if (scope == null) return emptySet()
        return parseFailed(read(failedKey(scope))).filterValues { now - it in 0 until FAILED_TTL_MS }.keys
    }

    fun recordFailure(scope: String?, method: String, now: Long = System.currentTimeMillis()) {
        if (scope == null || method.isBlank()) return
        val live = parseFailed(read(failedKey(scope))).filterValues { now - it in 0 until FAILED_TTL_MS }.toMutableMap()
        live[method] = now
        write(failedKey(scope), live.entries.joinToString(";") { "${it.key}=${it.value}" })
    }

    fun clearFailure(scope: String?, method: String) {
        if (scope == null) return
        val all = parseFailed(read(failedKey(scope))).toMutableMap()
        if (all.remove(method) == null) return
        write(failedKey(scope), all.entries.joinToString(";") { "${it.key}=${it.value}" }.ifEmpty { null })
    }

    /**
     * Every automatic method failed here and WireGuard's handshake passed without data: the network lets WARP
     * handshakes through and drops the data (the Irancell pattern). Kept for [BLOCKED_TTL_MS], far longer than
     * the per-method [FAILED_TTL_MS], so the next connect hours later spends one quick recheck, not five minutes.
     * Cleared as soon as any method carries HTTPS here.
     */
    const val BLOCKED_TTL_MS = 6 * 60 * 60 * 1000L

    private fun blockedKey(scope: String) = "$scope#warpBlocked"

    fun warpDataBlocked(scope: String?, now: Long = System.currentTimeMillis()): Boolean {
        if (scope == null) return false
        val at = read(blockedKey(scope))?.toLongOrNull() ?: return false
        return now - at in 0 until BLOCKED_TTL_MS
    }

    fun recordWarpDataBlocked(scope: String?, now: Long = System.currentTimeMillis()) {
        if (scope != null) write(blockedKey(scope), now.toString())
    }

    fun clearWarpDataBlocked(scope: String?) { if (scope != null) write(blockedKey(scope), null) }

    private fun parseFailed(text: String?): Map<String, Long> = text.orEmpty().split(';').mapNotNull { part ->
        val i = part.indexOf('=')
        if (i <= 0) null else part.substring(0, i) to (part.substring(i + 1).toLongOrNull() ?: return@mapNotNull null)
    }.toMap()
}
