package com.example.vpn.tunnel
object ProxyDnsTransport {
    fun isLiteralAddress(host: String): Boolean =
        host.matches(Regex("(?:\\d{1,3}\\.){3}\\d{1,3}")) && host.split('.').all { it.toInt() in 0..255 } ||
            host.contains(':') && host.matches(Regex("[0-9a-fA-F:]+"))
}
