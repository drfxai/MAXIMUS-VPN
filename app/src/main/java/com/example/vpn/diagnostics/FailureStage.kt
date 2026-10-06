package com.example.vpn.diagnostics

/**
 * Where a connection or a test stopped. "Server failed" says nothing; the stage says what to fix.
 */
enum class FailureStage {
    DNS_RESOLUTION_FAILED,
    TCP_CONNECT_FAILED,
    TLS_HANDSHAKE_FAILED,
    PROXY_AUTH_FAILED,
    PROXY_HANDSHAKE_FAILED,
    HTTP_REQUEST_FAILED,
    HTTP_STATUS_INVALID,
    TUN_ESTABLISH_FAILED,
    ENGINE_START_FAILED,
    TIMEOUT,
    CANCELLED,
    NETWORK_CHANGED,
    UNKNOWN;

    companion object {
        /**
         * The stage an exception or an error text points to. Exception classes are checked first
         * (they are certain); message words second (engines report failures as text). The cause chain
         * is followed, because wrappers hide the real class.
         */
        fun of(error: Throwable?): FailureStage {
            var e = error
            var depth = 0
            while (e != null && depth < 6) {
                classOf(e)?.let { return it }
                e = e.cause
                depth++
            }
            return fromText(error?.message)
        }

        private fun classOf(e: Throwable): FailureStage? = when (e) {
            is kotlinx.coroutines.CancellationException -> CANCELLED
            is java.net.UnknownHostException -> DNS_RESOLUTION_FAILED
            is java.net.SocketTimeoutException -> TIMEOUT
            is java.net.ConnectException -> TCP_CONNECT_FAILED
            is java.net.NoRouteToHostException -> TCP_CONNECT_FAILED
            is javax.net.ssl.SSLException -> TLS_HANDSHAKE_FAILED
            is java.security.cert.CertificateException -> TLS_HANDSHAKE_FAILED
            else -> null
        }

        /** The stage named by an error text, such as a probe's or an engine's failure reason. */
        fun fromText(text: String?): FailureStage {
            val t = text?.lowercase() ?: return UNKNOWN
            return when {
                "cancel" in t -> CANCELLED
                "network changed" in t || "network lost" in t -> NETWORK_CHANGED
                "unknownhost" in t || "unable to resolve" in t || "no address associated" in t ||
                    "cannot resolve" in t || "dns" in t && "fail" in t -> DNS_RESOLUTION_FAILED
                "timed out" in t || "timeout" in t || "deadline" in t -> TIMEOUT
                "establish" in t && ("tun" in t || "interface" in t) -> TUN_ESTABLISH_FAILED
                "engine" in t && ("start" in t || "did not" in t) -> ENGINE_START_FAILED
                "handshake" in t && ("tls" in t || "ssl" in t || "certificate" in t) -> TLS_HANDSHAKE_FAILED
                "certificate" in t || "ssl" in t || "tls" in t -> TLS_HANDSHAKE_FAILED
                "auth" in t || "invalid user" in t || "unauthor" in t || "407" in t -> PROXY_AUTH_FAILED
                "connection refused" in t || "econnrefused" in t || "connect failed" in t ||
                    "no route" in t || "unreachable" in t -> TCP_CONNECT_FAILED
                "handshake" in t || "proxy" in t || "socks" in t -> PROXY_HANDSHAKE_FAILED
                Regex("http (status )?[1-5][0-9][0-9]").containsMatchIn(t) || "returned http" in t -> HTTP_STATUS_INVALID
                "http" in t || "eof" in t || "reset" in t -> HTTP_REQUEST_FAILED
                else -> UNKNOWN
            }
        }
    }
}
