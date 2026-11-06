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
}
