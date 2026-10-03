package com.example.panels

import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Certificate pinning for self-signed panels.
 *
 * Maximus installs 3X-UI with a self-signed certificate it creates itself over the already
 * authenticated SSH session and records the SHA-256 fingerprint of that certificate. Later API
 * calls only trust a server presenting exactly that certificate, which is stricter than normal CA
 * validation, so no hostname/IP SAN checks are needed.
 */
object PinnedTls {
    fun normalize(fingerprint: String): String =
        fingerprint.removePrefix("SHA256:").replace(":", "").trim().lowercase()

    fun sha256Hex(der: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    fun client(base: OkHttpClient, expectedSha256: String): OkHttpClient {
        val expected = normalize(expectedSha256)
        require(expected.matches(Regex("[0-9a-f]{64}"))) { "Invalid certificate fingerprint" }
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("client certificates are not used")
            }

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
                val actual = sha256Hex(leaf.encoded)
                if (!MessageDigest.isEqual(actual.toByteArray(), expected.toByteArray())) {
                    throw CertificateException("panel certificate does not match the pinned fingerprint")
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        return base.newBuilder()
            .sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier { _, _ -> true }
            .build()
    }
}
