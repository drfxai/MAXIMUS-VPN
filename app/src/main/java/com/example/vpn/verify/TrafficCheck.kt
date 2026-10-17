package com.example.vpn.verify

import com.example.vpn.diagnostics.FailureStage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Opens a TCP stream to host:port along the route under test (the VPN network, or an engine's port). */
fun interface Dialer {
    fun open(host: String, port: Int, timeoutMs: Int): Socket
}

/**
 * Real transfers along one route: a small request (the ordinary "does it carry traffic" check), a
 * download whose every byte is counted, and an upload the far side must echo back. Each check is bounded
 * in bytes and time, verifies the reference's certificate and name, and reports a check it could not run
 * as not tested rather than failed.
 *
 * The references are ordinary public services outside Cloudflare first (a Cloudflare Worker exit cannot
 * reach Cloudflare-hosted sites), each with a second reference on another provider. A payload carries no
 * identifier: random text with a random marker at its end.
 */
class TrafficCheck(
    private val dialer: Dialer,
    /** Off only in tests, which run a plain HTTP server on the loopback interface. */
    private val tls: Boolean = true,
    private val downloads: List<Target> = DOWNLOADS,
    private val uploads: List<Target> = UPLOADS,
    private val small: Target = SMALL,
    private val stepTimeoutMs: Int = STEP_TIMEOUT_MS,
    private val random: java.util.Random = SecureRandom(),
    private val sslFactory: () -> SSLSocketFactory = { SSLSocketFactory.getDefault() as SSLSocketFactory },
    private val hostnameVerifier: HostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier(),
    private val clock: () -> Long = System::currentTimeMillis
) {
    data class Target(val host: String, val port: Int, val path: String, val expectBytes: Int = 0) {
        override fun toString(): String = host
    }

    /** One small request (HTTP 204 expected); returns its round-trip time. Throws on any failure. */
    fun smallRequest(): Long {
        val started = clock()
        val r = exchange(small, "GET", null, keepBody = false)
        if (r.status != 204 && r.status !in 200..399) throw IOException("Probe returned HTTP ${r.status}")
        return (clock() - started).coerceAtLeast(1)
    }

    fun download(): LegResult = firstPassing(downloads) { t -> downloadFrom(t) }

    fun upload(): LegResult = firstPassing(uploads) { t -> uploadTo(t) }

    /** Download then upload, as one piece of evidence for [attemptId] on [networkGeneration]. */
    fun both(attemptId: String?, networkGeneration: Int, path: ProbePath): TrafficEvidence =
        TrafficEvidence(attemptId, networkGeneration, path, download(), upload(), clock())

    internal fun downloadFrom(t: Target): LegResult {
        val started = clock()
        return try {
            val r = exchange(t, "GET", null, keepBody = false)
            when {
                r.status !in 200..299 -> LegResult.NotTested("$t answered HTTP ${r.status}")
                r.bodyBytes == t.expectBytes -> LegResult.Passed(r.bodyBytes, clock() - started)
                else -> LegResult.Failed(FailureStage.HTTP_REQUEST_FAILED, r.bodyBytes, stalled = false,
                    reason = "$t sent ${r.bodyBytes} of ${t.expectBytes} bytes")
            }
        } catch (e: TransferException) {
            failed(e, t)
        }
    }

    internal fun uploadTo(t: Target): LegResult {
        val marker = token(32)
        val payload = (token(UPLOAD_BYTES - marker.length) + marker).toByteArray(Charsets.US_ASCII)
        val started = clock()
        return try {
            val r = exchange(t, "POST", payload, keepBody = true)
            when {
                r.status !in 200..299 -> LegResult.NotTested("$t answered HTTP ${r.status}")
                // The marker sits at the very end of the payload: echoed back, every byte before it arrived.
                r.body?.toString(Charsets.US_ASCII.name())?.contains(marker) == true ->
                    LegResult.Passed(payload.size, clock() - started)
                else -> LegResult.NotTested("$t did not echo the upload")
            }
        } catch (e: TransferException) {
            failed(e, t)
        }
    }

    private fun failed(e: TransferException, t: Target): LegResult {
        val stage = FailureStage.of(e.cause ?: e)
        // A reference the engine could not reach says nothing about this route's bulk transfers.
        (e.cause as? Socks5.Socks5Exception)?.let { return LegResult.NotTested("$t: ${it.message}") }
        val stalled = e.moved > 0 && (stage == FailureStage.TIMEOUT || e.timedOut)
        return LegResult.Failed(if (e.timedOut) FailureStage.TIMEOUT else stage, e.moved, stalled,
            "$t: ${e.cause?.javaClass?.simpleName ?: "error"}")
    }

    /** Tries each reference in turn: the first pass wins; otherwise the most telling failure. */
    private fun firstPassing(targets: List<Target>, run: (Target) -> LegResult): LegResult {
        val results = mutableListOf<LegResult>()
        for (t in targets) {
            val r = run(t)
            if (r is LegResult.Passed) return r
            results += r
        }
        val failures = results.filterIsInstance<LegResult.Failed>()
        return failures.firstOrNull { it.stalled }
            ?: failures.firstOrNull { it.stage == FailureStage.CERTIFICATE_VALIDATION_FAILED }
            ?: failures.firstOrNull()
            ?: LegResult.NotTested(results.filterIsInstance<LegResult.NotTested>().joinToString("; ") { it.reason }.ifEmpty { "no reference" })
    }

    private fun token(n: Int): String {
        val sb = StringBuilder(n)
        repeat(n) { sb.append(ALPHABET[random.nextInt(ALPHABET.length)]) }
        return sb.toString()
    }

    internal class Response(val status: Int, val bodyBytes: Int, val body: ByteArrayOutputStream?)

    /** A failed exchange; [moved] is how many body bytes went (upload) or came (download) before it. */
    internal class TransferException(cause: Throwable, val moved: Int, val timedOut: Boolean) : IOException(cause.message, cause)

    /**
     * One HTTP/1.1 request over a fresh connection, bounded by [stepTimeoutMs] in total: a socket write has
     * no timeout of its own, so a watchdog closes the socket at the deadline.
     */
    internal fun exchange(t: Target, method: String, payload: ByteArray?, keepBody: Boolean): Response {
        var moved = 0
        val socket = AtomicReference<Socket?>(null)
        val expired = AtomicBoolean(false)
        val watchdog = WATCHDOG.schedule({ expired.set(true); runCatching { socket.get()?.close() } }, stepTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
        try {
            val raw = dialer.open(t.host, t.port, stepTimeoutMs)
            socket.set(raw)
            if (expired.get()) raw.close()
            raw.soTimeout = stepTimeoutMs
            val s = if (tls) secure(raw, t.host, t.port) else raw
            socket.set(s)
            val out = s.getOutputStream()
            val head = buildString {
                append("$method ${t.path} HTTP/1.1\r\n")
                append("Host: ${t.host}\r\n")
                append("User-Agent: Mozilla/5.0\r\n")
                append("Accept: */*\r\n")
                append("Accept-Encoding: identity\r\n")
                append("Cache-Control: no-cache\r\n")
                append("Connection: close\r\n")
                if (payload != null) {
                    append("Content-Type: text/plain\r\n")
                    append("Content-Length: ${payload.size}\r\n")
                }
                append("\r\n")
            }
            out.write(head.toByteArray(Charsets.US_ASCII))
            if (payload != null) {
                var off = 0
                while (off < payload.size) {
                    val n = minOf(CHUNK, payload.size - off)
                    out.write(payload, off, n)
                    off += n
                    moved = off
                }
            }
            out.flush()
            val input = s.getInputStream()
            val headers = readHead(input)
            val status = headers.first
            val body = if (keepBody) ByteArrayOutputStream() else null
            if (payload == null) moved = 0
            val counter: (Int) -> Unit = { n -> if (payload == null) moved += n }
            val length = headers.second["content-length"]?.trim()?.toIntOrNull()
            val chunked = headers.second["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true
            val total = when {
                method == "HEAD" || status == 204 || status == 304 -> 0
                chunked -> readChunked(input, body, counter)
                length != null -> readFixed(input, length, body, counter)
                else -> readFixed(input, MAX_BODY, body, counter, untilEof = true)
            }
            return Response(status, total, body)
        } catch (e: Exception) {
            val timedOut = expired.get() || e is SocketTimeoutException
            throw TransferException(if (expired.get() && e !is SocketTimeoutException) SocketTimeoutException("deadline") else e, moved, timedOut)
        } finally {
            watchdog.cancel(false)
            runCatching { socket.get()?.close() }
        }
    }

    private fun secure(raw: Socket, host: String, port: Int): Socket {
        val s = sslFactory().createSocket(raw, host, port, true) as SSLSocket
        s.sslParameters = s.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
        s.startHandshake()
        // Checked again explicitly: a reference that is not who it says is a failure, never a pass.
        if (!hostnameVerifier.verify(host, s.session)) {
            throw SSLPeerUnverifiedException("Certificate does not match $host").apply {
                initCause(java.security.cert.CertificateException("name mismatch"))
            }
        }
        return s
    }

    companion object {
        const val DOWNLOAD_BYTES = 65_536
        const val UPLOAD_BYTES = 24_576
        const val STEP_TIMEOUT_MS = 10_000
        private const val CHUNK = 4096
        private const val MAX_HEAD = 16 * 1024
        private const val MAX_BODY = 256 * 1024
        private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

        val SMALL = Target("www.gstatic.com", 443, "/generate_204")
        val DOWNLOADS = listOf(
            Target("httpbin.org", 443, "/bytes/$DOWNLOAD_BYTES", DOWNLOAD_BYTES),
            Target("speed.cloudflare.com", 443, "/__down?bytes=$DOWNLOAD_BYTES", DOWNLOAD_BYTES)
        )
        val UPLOADS = listOf(
            Target("httpbin.org", 443, "/anything"),
            Target("postman-echo.com", 443, "/post")
        )

        private val WATCHDOG: ScheduledExecutorService by lazy {
            Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "traffic-check-deadline").apply { isDaemon = true } }
        }

        /** Status line and lower-cased headers. */
        internal fun readHead(input: InputStream): Pair<Int, Map<String, String>> {
            val buf = ByteArrayOutputStream()
            var last4 = 0
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("Connection closed before the response headers")
                buf.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0D0A0D0A) break
                if (buf.size() > MAX_HEAD) throw IOException("Response headers too long")
            }
            val lines = buf.toString(Charsets.ISO_8859_1.name()).split("\r\n")
            val status = lines.first().split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("Bad status line")
            val headers = lines.drop(1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
            }.toMap()
            return status to headers
        }

        private fun readFixed(input: InputStream, length: Int, body: ByteArrayOutputStream?, counter: (Int) -> Unit, untilEof: Boolean = false): Int {
            val cap = length.coerceAtMost(MAX_BODY)
            val buf = ByteArray(8192)
            var total = 0
            while (total < cap) {
                val n = input.read(buf, 0, minOf(buf.size, cap - total))
                if (n < 0) {
                    if (untilEof) break
                    throw IOException("Connection closed after $total of $length bytes")
                }
                body?.write(buf, 0, n)
                total += n
                counter(n)
            }
            return total
        }

        private fun readChunked(input: InputStream, body: ByteArrayOutputStream?, counter: (Int) -> Unit): Int {
            var total = 0
            while (true) {
                val sizeLine = readLine(input)
                val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw IOException("Bad chunk size")
                if (size == 0) { readLine(input); return total }
                if (total + size > MAX_BODY) throw IOException("Response too long")
                total += readFixed(input, size, body, counter)
                readLine(input)
            }
        }

        private fun readLine(input: InputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("Connection closed in a chunk")
                if (b == '\n'.code) return sb.toString().trimEnd('\r')
                sb.append(b.toChar())
                if (sb.length > 1024) throw IOException("Chunk line too long")
            }
        }
    }
}
