package com.example.vpn.dns

internal object DnsResponse {
    fun isResponseTo(query: ByteArray, response: ByteArray): Boolean =
        response.size <= 65507 && DnsMessageValidator.isResponseTo(query, response, response.size)
}
