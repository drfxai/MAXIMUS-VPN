package com.example.ai.gateway

import com.example.core.AiPrivacyFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Shared HTTP plumbing for provider adapters: timeouts, error capture and redaction. */
object ProviderHttp {
    val JSON = "application/json; charset=utf-8".toMediaType()
    const val MAX_ERROR_BODY = 4_000

    /** One client for every provider; per-provider timeouts are applied per call. */
    val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(false)
            .build()
    }

    fun client(base: OkHttpClient, timeoutMs: Long): OkHttpClient =
        base.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()

    fun post(url: String, body: String, headers: Map<String, String>): Request =
        Request.Builder().url(url).post(body.toRequestBody(JSON)).apply { headers.forEach { (k, v) -> header(k, v) } }.build()

    fun get(url: String, headers: Map<String, String>): Request =
        Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build()

    /**
     * Runs [request] and returns the body of a 2xx answer; any other answer or transport failure becomes an
     * [AiException] through [normalize].
     */
    suspend fun execute(
        client: OkHttpClient,
        request: Request,
        normalize: (Int?, String?, Throwable?, String?) -> AiError
    ): String = withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response -> bodyOrThrow(response, normalize) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            throw e
        } catch (e: IOException) {
            throw AiException(normalize(null, null, e, null))
        }
    }

    fun bodyOrThrow(response: Response, normalize: (Int?, String?, Throwable?, String?) -> AiError): String {
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw AiException(normalize(response.code, text.take(MAX_ERROR_BODY), null, response.header("Retry-After")))
        return text
    }

    /** The transport-level kind of an exception. */
    fun transportKind(cause: Throwable?): AiErrorKind = when (cause) {
        null -> AiErrorKind.UNKNOWN
        is SocketTimeoutException -> AiErrorKind.TIMEOUT
        is InterruptedIOException -> if (cause.message?.contains("timeout", true) == true) AiErrorKind.TIMEOUT else AiErrorKind.CANCELLED
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> AiErrorKind.UNREACHABLE
        is javax.net.ssl.SSLException -> AiErrorKind.UNREACHABLE
        is IOException -> AiErrorKind.UNREACHABLE
        else -> AiErrorKind.UNKNOWN
    }

    /** Retry-After in seconds or as an HTTP date; null when absent or unreadable. */
    fun retryAfterMs(header: String?, now: Long = System.currentTimeMillis()): Long? {
        val h = header?.trim().orEmpty()
        if (h.isEmpty()) return null
        h.toLongOrNull()?.let { return (it * 1000).coerceIn(0, 24 * 3600_000L) }
        val format = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
        return runCatching { format.parse(h)!!.time - now }.getOrNull()?.coerceIn(0, 24 * 3600_000L)
    }

    /** Provider error text is shown and logged only after redaction, so an echoed key or URL never leaks. */
    fun safe(text: String?): String = AiPrivacyFilter.redact(text.orEmpty().take(300)).ifBlank { "no details" }
}
