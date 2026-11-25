package com.example.update

import com.example.vpn.verify.Socks5
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * Build 175 report: the upkeep's GeoFiles update failed through Serverless (11:03) and Aether (10:24) even after it
 * moved to the engine's local SOCKS port.
 */
class Build175GeoFilesTest {
    private val held = HeldCertificate.Builder()
        .addSubjectAlternativeName("localhost")
        .addSubjectAlternativeName("api.example.test")
        .addSubjectAlternativeName("files.example.test")
        .build()
    private val trust = HandshakeCertificates.Builder().addTrustedCertificate(held.certificate).build()
    private val server = MockWebServer()
    private val closers = CopyOnWriteArrayList<AutoCloseable>()

    @After fun tearDown() {
        closers.forEach { runCatching { it.close() } }
        server.shutdown()
    }

    private fun startTls(dispatcher: Dispatcher, h2: Boolean = false) {
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(held).build().sslSocketFactory(), false)
        if (h2) server.protocols = listOf(Protocol.HTTP_2, Protocol.HTTP_1_1)
        server.dispatcher = dispatcher
        server.start(InetAddress.getByName("localhost"), 0)
    }

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** A no-auth SOCKS5 server that records each requested name and forwards the stream to the mock server. */
    private fun socks(seen: MutableList<String>): Int {
        val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        closers += listener
        thread(isDaemon = true) {
            while (!listener.isClosed) {
                val client = runCatching { listener.accept() }.getOrNull() ?: break
                closers += client
                thread(isDaemon = true) {
                    runCatching {
                        val input = DataInputStream(client.getInputStream())
                        val out = client.getOutputStream()
                        input.readUnsignedByte(); input.readFully(ByteArray(input.readUnsignedByte()))
                        out.write(byteArrayOf(5, 0)); out.flush()
                        input.readFully(ByteArray(3))
                        check(input.readUnsignedByte() == 3) { "expected a name" }
                        val host = String(ByteArray(input.readUnsignedByte()).also { input.readFully(it) })
                        input.readUnsignedShort()
                        seen += host
                        val upstream = Socket("127.0.0.1", server.port)
                        closers += upstream
                        out.write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0)); out.flush()
                        thread(isDaemon = true) { runCatching { upstream.getInputStream().copyTo(out) } }
                        runCatching { input.copyTo(upstream.getOutputStream()) }
                    }
                }
            }
        }
        return listener.localPort
    }

    /**
     * Every name had the placeholder 0.0.0.0, so OkHttp let one HTTP/2 connection carry another name whose
     * certificate it covers (api.github.com's covers github.com). Each name now gets its own connection.
     */
    @Test fun eachHostGetsItsOwnConnectionThroughTheEngine() {
        startTls(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("ok ${request.path}")
        }, h2 = true)
        val seen = CopyOnWriteArrayList<String>()
        val port = socks(seen)
        val client = GeoFilesUpdater.socksClient(Socks5.Endpoint("127.0.0.1", port, null, null),
            OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager))
        for ((host, path) in listOf("api.example.test" to "/a", "files.example.test" to "/b", "api.example.test" to "/c")) {
            client.newCall(Request.Builder().url("https://$host:${server.port}$path").build()).execute().use { r ->
                assertEquals(200, r.code)
                assertEquals(Protocol.HTTP_1_1, r.protocol)
                assertEquals("ok $path", r.body!!.string())
            }
        }
        assertTrue("$seen", "api.example.test" in seen && "files.example.test" in seen)
    }

    /** The API's per-address limit is shared behind carrier NAT and WARP/Lantern exits: the release pages work too. */
    @Test fun aRefusedApiFallsBackToTheReleasePages() {
        val tag = "202611231517"
        val payload = mapOf(
            "geoip-lite.dat" to "ip-v3".toByteArray(),
            "geosite-lite.dat" to "site-v3".toByteArray(),
            "geoip-services.dat" to "services-v3".toByteArray()
        )
        startTls(object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val dl = "/repo/releases/download/$tag/"
                return when {
                    path == "/api/latest" -> MockResponse().setResponseCode(403).setBody("{\"message\":\"API rate limit exceeded\"}")
                    path == "/repo/releases/latest" -> MockResponse().setResponseCode(302)
                        .setHeader("Location", "https://localhost:${server.port}/repo/releases/tag/$tag")
                    path.startsWith(dl) && path.endsWith(".sha256sum") -> {
                        val name = path.removePrefix(dl).removeSuffix(".sha256sum")
                        MockResponse().setBody("${sha(payload.getValue(name))}  $name\n")
                    }
                    path.startsWith(dl) -> MockResponse().setBody(okio.Buffer().write(payload.getValue(path.removePrefix(dl))))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        })
        val dir = Files.createTempDirectory("geo175").toFile()
        try {
            val bundled = JSONObject().put("source", GeoFilesUpdater.REPO).put("license", "GPL-3.0")
                .put("tag", "202611211258").put("files", JSONObject()).toString()
            val client = OkHttpClient.Builder().sslSocketFactory(trust.sslSocketFactory(), trust.trustManager).build()
            val base = "https://localhost:${server.port}"
            val staged = runBlocking {
                GeoFilesUpdater.download(client, "$base/api/latest", bundled, dir, webBase = "$base/repo")
            } as GeoFilesUpdater.Downloaded.Staged
            assertEquals(tag, staged.tag)
            payload.forEach { (name, bytes) -> assertTrue(name, staged.files.getValue(name).readBytes().contentEquals(bytes)) }
            // Without the fallback the refusal is reported as it was.
            val refused = runCatching { runBlocking { GeoFilesUpdater.download(client, "$base/api/latest", bundled, dir) } }
            assertTrue(refused.exceptionOrNull()?.message.orEmpty(), refused.exceptionOrNull()?.message.orEmpty().contains("HTTP 403"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun theTagComesFromTheRedirect() {
        assertEquals("202611231517", GeoFilesUpdater.tagFromLocation(
            "https://github.com/Chocolate4U/Iran-v2ray-rules/releases/tag/202611231517"))
        assertEquals("202611231517", GeoFilesUpdater.tagFromLocation("/x/releases/tag/202611231517?a=b"))
        assertTrue(runCatching { GeoFilesUpdater.tagFromLocation("https://github.com/x/releases") }.isFailure)
        assertTrue(runCatching { GeoFilesUpdater.tagFromLocation("https://github.com/x/releases/tag/..%2F..%2Fevil") }.isFailure)
        val web = GeoFilesUpdater.webRelease(GeoFilesUpdater.WEB_BASE, "202611231517")
        assertEquals("${GeoFilesUpdater.WEB_BASE}/releases/download/202611231517/geoip-lite.dat.sha256sum",
            web.assets.getValue("geoip-lite.dat.sha256sum").url)
        assertEquals(-1L, web.assets.getValue("geoip-lite.dat").size)
        assertTrue(runCatching { GeoFilesUpdater.webRelease("http://github.com/x", "1") }.isFailure)
    }
}
