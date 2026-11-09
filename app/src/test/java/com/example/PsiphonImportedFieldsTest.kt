package com.example
import com.example.vpn.sidecar.PsiphonSidecar
import org.json.JSONObject
import java.io.File
import org.junit.Assert.*
import org.junit.Test
class PsiphonImportedFieldsTest {
    @Test fun desktopAliasesCannotOverrideRuntimeListenersOrUpstream() {
        val source = JSONObject().put("upstreamproxyurl", "http://fixture.invalid:1234")
            .put("LOCALSOCKSPROXYPORT", 1111).put("listeninterface", "any")
            .put("clientplatform", "Windows").put("enableupgradedownload", true)
        val result = PsiphonSidecar.buildConfig(source, "DE", 12345, File("/tmp/fixture"))
        assertFalse(result.has("upstreamproxyurl")); assertFalse(result.has("LOCALSOCKSPROXYPORT"))
        assertEquals(12345, result.getInt("LocalSocksProxyPort")); assertEquals("lo", result.getString("ListenInterface"))
        assertEquals("Android", result.getString("ClientPlatform")); assertFalse(result.getBoolean("EnableUpgradeDownload"))
        assertEquals("DE", result.getString("EgressRegion"))
    }
}
