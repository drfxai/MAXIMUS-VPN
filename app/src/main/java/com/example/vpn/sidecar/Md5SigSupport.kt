package com.example.vpn.sidecar

/**
 * Whether this phone's kernel has TCP_MD5SIG, which ByeDPI's `--md5sig` needs (ByeDPI's README: it is often
 * compiled out of Android kernels). Checked once per process without root and without any network traffic:
 * `setsockopt(TCP_MD5SIG)` with a 4-byte value on a fresh, unconnected TCP socket. A kernel built with MD5
 * support parses the option and rejects the short length (EINVAL); one without it does not know the option
 * (ENOPROTOOPT). ByeDPI runs under the app's own uid, so it gets the same answer.
 *
 * Android 8.0+ only (the public setsockoptInt); older phones never get the MD5 decoy.
 * Not yet verified on a device: if the check is wrong, the MD5 candidate fails its real request and is not
 * tried again on that network for a few days ([com.example.vpn.smart.FakeSniMemory]).
 */
object Md5SigSupport {
    /** Linux `TCP_MD5SIG` (include/uapi/linux/tcp.h). */
    const val TCP_MD5SIG = 14
    /** Linux errno values; identical on every Android ABI. */
    const val EINVAL = 22
    const val ENOPROTOOPT = 92

    @Volatile private var cached: Boolean? = null

    /** Null when setsockopt succeeded, otherwise its errno. Replaced in unit tests. */
    @Volatile var probe: () -> Int? = ::probeErrno

    /** EINVAL (option known, length rejected) or success: supported. ENOPROTOOPT or anything else: not. Pure. */
    fun classify(errno: Int?): Boolean = errno == null || errno == EINVAL

    fun supported(): Boolean = cached ?: classify(runCatching { probe() }.getOrElse { ENOPROTOOPT }).also { cached = it }

    private fun probeErrno(): Int? {
        // Os.setsockoptInt is public from API 26; on Android 7 the MD5 decoy is simply not offered.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return ENOPROTOOPT
        return probeErrnoO()
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.O)
    private fun probeErrnoO(): Int? {
        val fd = android.system.Os.socket(android.system.OsConstants.AF_INET, android.system.OsConstants.SOCK_STREAM,
            android.system.OsConstants.IPPROTO_TCP)
        return try {
            android.system.Os.setsockoptInt(fd, android.system.OsConstants.IPPROTO_TCP, TCP_MD5SIG, 0)
            null
        } catch (e: android.system.ErrnoException) {
            e.errno
        } finally {
            runCatching { android.system.Os.close(fd) }
        }
    }

    internal fun resetForTest() { cached = null }
}
