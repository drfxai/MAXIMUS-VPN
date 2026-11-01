package com.example.panels.servers

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Reviewed upstream Psiphon-Labs psiphond at a fixed Git commit, verified by a
 * separate GitHub Actions job against Git blob a5d9b83d8291676f6e219683710a02b8d33b7038.
 * The SHA-256 below is the 2026-11-01 audit's actual digest. Never download master.
 *
 * Only x86_64 Linux is available in this audited artifact. ARM is fail-closed.
 */
object PrivatePsiphonArtifact {
    const val COMMIT = "e02a99daa375567d2df20740bb3d4673eaec11fe"
    const val SHA256 = "915f638c20177ead91e2ded02d6e0abb9ad82795c1d36dac40be163a0ba29afb"
    const val SIZE = 37_799_528
    const val URL = "https://raw.githubusercontent.com/Psiphon-Labs/psiphon-tunnel-core-binaries/" +
        COMMIT + "/psiphond/psiphond"

    fun supported(facts: ServerFacts): Boolean = facts.goArch == "amd64" && facts.systemd &&
        facts.root && facts.socketInspectionAvailable

    fun verify(bytes: ByteArray): Boolean = bytes.size == SIZE &&
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it.toInt() and 0xff)
        } == SHA256

    fun download(client: OkHttpClient): ByteArray {
        val bounded = client.newBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true).build()
        bounded.newCall(Request.Builder().url(URL).get().build()).execute().use { reply ->
            check(reply.isSuccessful) { "The pinned official Psiphon artifact is unavailable." }
            val body = requireNotNull(reply.body) { "Empty Psiphon artifact response." }
            require(body.contentLength() in -1L..SIZE.toLong()) { "The upstream artifact length is unexpected." }
            val out = ByteArrayOutputStream(SIZE)
            val buf = ByteArray(65536)
            body.byteStream().use { stream ->
                while (true) {
                    val n = stream.read(buf)
                    if (n == -1) break
                    check(out.size() + n <= SIZE) { "The downloaded Psiphon artifact exceeds its pinned size." }
                    out.write(buf, 0, n)
                }
            }
            val bytes = out.toByteArray()
            check(verify(bytes)) { "Pinned Psiphon binary SHA-256 mismatch; no SSH upload was attempted." }
            return bytes
        }
    }
}
