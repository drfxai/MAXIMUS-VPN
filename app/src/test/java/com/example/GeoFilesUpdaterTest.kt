package com.example

import com.example.update.GeoFilesUpdater
import com.example.xray.GeoAssets
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

/** GeoFiles update: published sha256 checksums gate the install, and the manifest records the new set. */
class GeoFilesUpdaterTest {
    private lateinit var dir: File
    private val server = MockWebServer()
    private val client = OkHttpClient()

    private val bundled = JSONObject()
        .put("source", GeoFilesUpdater.REPO).put("license", "GPL-3.0").put("tag", "202611211258")
        .put("files", JSONObject()).toString()

    private val payload = mapOf(
        "geoip-lite.dat" to "ip-v2".toByteArray(),
        "geosite-lite.dat" to "site-v2".toByteArray(),
        "geoip-services.dat" to "services-v2".toByteArray()
    )

    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    @Before fun setUp() {
        dir = Files.createTempDirectory("geo").toFile()
        GeoAssets.FILES.values.forEach { File(dir, it).writeText("old-$it") }
    }

    @After fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    /** Serves a release [tag]; [published] overrides the checksum file text for a name. */
    private fun serve(tag: String, published: Map<String, String> = emptyMap(), body: Map<String, ByteArray> = payload) {
        val base = server.url("/").toString().trimEnd('/')
        val assets = JSONArray()
        payload.forEach { (name, bytes) ->
            assets.put(JSONObject().put("name", name).put("size", bytes.size).put("browser_download_url", "$base/d/$name"))
            assets.put(JSONObject().put("name", "$name.sha256sum").put("size", 90).put("browser_download_url", "$base/d/$name.sha256sum"))
        }
        val release = JSONObject().put("tag_name", tag).put("assets", assets).toString()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path == "/latest" -> MockResponse().setBody(release)
                    path.endsWith(".sha256sum") -> {
                        val name = path.removePrefix("/d/").removeSuffix(".sha256sum")
                        MockResponse().setBody(published[name] ?: "${sha(payload.getValue(name))}  release/$name\n")
                    }
                    path.startsWith("/d/") -> MockResponse().setBody(Buffer().write(body.getValue(path.removePrefix("/d/"))))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
    }

    private fun download() = runBlocking { GeoFilesUpdater.download(client, server.url("/latest").toString(), bundled, dir) }

    @Test
    fun verifiedNewerReleaseIsInstalledAndManifestUpdated() {
        serve("202611221249")
        val staged = download() as GeoFilesUpdater.Downloaded.Staged
        GeoAssets.installFiles(dir, staged.manifest, staged.files)

        payload.forEach { (asset, bytes) ->
            assertTrue(asset, File(dir, GeoAssets.FILES.getValue(asset)).readBytes().contentEquals(bytes))
        }
        val stamp = File(dir, ".manifest").readText()
        assertEquals("202611221249", GeoAssets.tagOf(stamp))
        assertEquals(payload.mapValues { sha(it.value) }, GeoAssets.expectedHashes(stamp))
        assertTrue(GeoAssets.isNewer(stamp, bundled))
        assertFalse("no temporary files left", dir.listFiles()!!.any { it.name.endsWith(".download") || it.name.endsWith(".tmp") })
    }

    @Test
    fun checksumMismatchRejectsWholeUpdate() {
        serve("202611221249", published = mapOf("geosite-lite.dat" to "${"0".repeat(64)}  geosite-lite.dat"))
        try {
            download()
            fail("mismatch must be rejected")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!, e.message!!.contains("checksum mismatch"))
        }
        GeoAssets.FILES.values.forEach { assertEquals("old-$it", File(dir, it).readText()) }
        assertFalse(File(dir, ".manifest").exists())
        assertFalse(dir.listFiles()!!.any { it.name.endsWith(".download") })
    }

    @Test
    fun tamperedBodyIsRejected() {
        serve("202611221249", body = payload + ("geoip-lite.dat" to "ip-v3".toByteArray()))
        runCatching { download() }.onSuccess { fail("tampered file accepted") }
        GeoAssets.FILES.values.forEach { assertEquals("old-$it", File(dir, it).readText()) }
    }

    @Test
    fun sameOrOlderTagIsUpToDate() {
        serve("202611211258")
        assertEquals(GeoFilesUpdater.Downloaded.Current("202611211258"), download())
        serve("202611010000")
        assertTrue(download() is GeoFilesUpdater.Downloaded.Current)
    }

    @Test
    fun installRefusesFilesThatDoNotMatchManifest() {
        val staged = payload.mapValues { (name, bytes) -> File(dir, "$name.download").apply { writeBytes(bytes) } }
        val wrong = GeoFilesUpdater.manifestFor(bundled, "202611221249", payload.mapValues { "f".repeat(64) })
        runCatching { GeoAssets.installFiles(dir, wrong, staged) }.onSuccess { fail("unverified install accepted") }
        GeoAssets.FILES.values.forEach { assertEquals("old-$it", File(dir, it).readText()) }
    }

    @Test
    fun parsesSha256SumFormats() {
        val h = "a".repeat(64)
        assertEquals(h, GeoFilesUpdater.parseChecksum("$h  release/geoip-lite.dat\n", "geoip-lite.dat"))
        assertEquals(h, GeoFilesUpdater.parseChecksum("$h *geoip-lite.dat", "geoip-lite.dat"))
        assertEquals(h, GeoFilesUpdater.parseChecksum(h.uppercase(), "geoip-lite.dat"))
        assertTrue(runCatching { GeoFilesUpdater.parseChecksum("$h  other.dat", "geoip-lite.dat") }.isFailure)
        assertTrue(runCatching { GeoFilesUpdater.parseChecksum("abc  geoip-lite.dat", "geoip-lite.dat") }.isFailure)
    }

    @Test
    fun downloadedSetSurvivesUntilBundleIsNewer() {
        val downloaded = GeoFilesUpdater.manifestFor(bundled, "202611221249", emptyMap())
        assertTrue(GeoAssets.isNewer(downloaded, bundled))
        val laterApk = GeoFilesUpdater.manifestFor(bundled, "202612010000", emptyMap())
        assertFalse(GeoAssets.isNewer(downloaded, laterApk))
        assertFalse(GeoAssets.isNewer(JSONObject(downloaded).put("source", "someone/else").toString(), bundled))
    }
}
