package com.example

import com.example.core.AppResult
import com.example.panels.ManagedPanel
import com.example.panels.PanelType
import com.example.panels.ThreeXUiConfigGenerator
import com.example.panels.ThreeXUiProtocol
import com.example.panels.ThreeXUiSecurity
import com.example.vless.VlessParser
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Runs every in-app 3X-UI flow against a real panel. Skipped unless XUI_LIVE_URL is set, e.g.
 *
 *   XUI_LIVE_URL=https://127.0.0.1:2053/base/ XUI_LIVE_USER=… XUI_LIVE_PASS=… \
 *   XUI_LIVE_TOKEN=… XUI_LIVE_CERT_SHA256=… ./gradlew :app:testDebugUnitTest --tests '*ThreeXUiLivePanelTest*'
 *
 * Leave XUI_LIVE_TOKEN empty to exercise the username/password session path.
 */
class ThreeXUiLivePanelTest {
    private val url = System.getenv("XUI_LIVE_URL").orEmpty()

    private fun panel() = ManagedPanel(
        id = "live",
        type = PanelType.XUI,
        name = "live",
        url = url,
        username = System.getenv("XUI_LIVE_USER").orEmpty(),
        password = System.getenv("XUI_LIVE_PASS").orEmpty(),
        apiToken = System.getenv("XUI_LIVE_TOKEN").orEmpty(),
        host = System.getenv("XUI_LIVE_HOST").orEmpty().ifBlank { java.net.URI(url).host },
        certSha256 = System.getenv("XUI_LIVE_CERT_SHA256").orEmpty()
    )

    private val generator = ThreeXUiConfigGenerator(
        OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).build(),
        reachabilityProbe = { _, _ -> true }
    )

    private fun assertImportable(uri: String) {
        assertTrue(uri, VlessParser.parse(uri) is AppResult.Success)
    }

    @Test
    fun everyFlowCreatesAnImportableConfig() {
        assumeTrue("XUI_LIVE_URL not set", url.isNotBlank())
        val p = panel()
        assertImportable(generator.generateRecommended(p).clientUri)
        assertImportable(generator.generateRapidVlessTcp(p).clientUri)
        for (protocol in ThreeXUiProtocol.values()) {
            for (security in listOf(ThreeXUiSecurity.NONE, ThreeXUiSecurity.REALITY)) {
                if (protocol == ThreeXUiProtocol.WEBSOCKET && security == ThreeXUiSecurity.REALITY) continue
                assertImportable(generator.generateInbound(p, protocol, security).clientUri)
            }
        }
        assertImportable(generator.generateInbound(p, ThreeXUiProtocol.WEBSOCKET, ThreeXUiSecurity.TLS).clientUri)
    }
}
