package com.example.vpn.tunnel

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngineResult

/** TLS over an already-proxied byte stream; never opens a socket or resolves a name. */
internal class ProxyTlsChannel(
    private val upstream: InputStream,
    private val downstream: OutputStream,
    host: String,
    port: Int,
    context: SSLContext = SSLContext.getDefault()
) {
    private val engine = context.createSSLEngine(host, port).apply {
        useClientMode = true
        sslParameters = sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
    }
    private val encrypted = ByteBuffer.allocate(65536).apply { limit(0) }
    private val plaintext = ByteBuffer.allocate(65536).apply { limit(0) }
    private val outgoing = ByteBuffer.allocate(65536)
    private val empty = ByteBuffer.allocate(0)
    private val deadline = System.nanoTime() + 30_000_000_000L

    private fun checkDeadline() = check(System.nanoTime() < deadline) { "Proxied TLS timed out" }

    private fun wrap(source: ByteBuffer) {
        checkDeadline()
        outgoing.clear()
        val result = engine.wrap(source, outgoing)
        check(result.status == SSLEngineResult.Status.OK) { "TLS wrap failed: ${result.status}" }
        downstream.write(outgoing.array(), 0, outgoing.position())
        downstream.flush()
    }

    private fun unwrap(): Boolean {
        checkDeadline()
        plaintext.compact()
        val result = engine.unwrap(encrypted, plaintext)
        plaintext.flip()
        return when (result.status) {
            SSLEngineResult.Status.OK -> {
                check(result.bytesConsumed() > 0 || result.bytesProduced() > 0 ||
                    engine.handshakeStatus != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
                    "TLS made no progress"
                }
                true
            }
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                encrypted.compact()
                check(encrypted.hasRemaining()) { "TLS record exceeds limit" }
                val count = upstream.read(encrypted.array(), encrypted.position(), encrypted.remaining())
                check(count > 0) { "Truncated proxied TLS response" }
                encrypted.position(encrypted.position() + count)
                encrypted.flip()
                false
            }
            else -> error("TLS unwrap failed: ${result.status}")
        }
    }

    private fun handshake() {
        while (true) {
            checkDeadline()
            when (engine.handshakeStatus) {
                SSLEngineResult.HandshakeStatus.NEED_TASK -> {
                    while (true) (engine.delegatedTask ?: break).run()
                }
                SSLEngineResult.HandshakeStatus.NEED_WRAP -> wrap(empty)
                SSLEngineResult.HandshakeStatus.NEED_UNWRAP -> unwrap()
                // NEED_UNWRAP_AGAIN is provider-specific and requires no new socket data.
                else -> {
                    if (engine.handshakeStatus.name == "NEED_UNWRAP_AGAIN") unwrap()
                    else return
                }
            }
        }
    }

    init {
        engine.beginHandshake()
        handshake()
    }

    fun write(bytes: ByteArray) {
        val source = ByteBuffer.wrap(bytes)
        while (source.hasRemaining()) {
            handshake()
            wrap(source)
        }
    }

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            while (!plaintext.hasRemaining()) {
                handshake()
                unwrap()
            }
            return plaintext.get().toInt() and 255
        }
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
            if (length == 0) return 0
            bytes[offset] = read().toByte()
            val count = minOf(length - 1, plaintext.remaining())
            plaintext.get(bytes, offset + 1, count)
            return count + 1
        }
    }
}
