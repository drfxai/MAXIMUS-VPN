package com.example

import com.example.data.model.ProtocolType
import com.example.vpn.engine.RuntimeCapabilities
import com.example.vpn.engine.UniversalImportEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** BPB serves its raw subscription as standard Base64 (with '+' and '/'), not URL-safe Base64. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BpbSubscriptionImportTest {

    private val host = "maximus-bpb-abc.acme.workers.dev"
    private val uuid = "8b0e2c4a-9f6d-4c1e-a7b3-2d5f8e1c0a9b"

    private fun links() = listOf(
        "vless://$uuid@$host:443?encryption=none&host=$host&type=ws&security=tls" +
            "&path=%2Fvl%2FAbCdEfGh12345678%3Fed%3D2560&sni=$host&fp=chrome&alpn=http%2F1.1#>>>???-1",
        "vless://$uuid@104.16.1.2:8443?encryption=none&host=$host&type=ws&security=tls" +
            "&path=%2Fvl%2FXyZ0987654321abc%3Fed%3D2560&sni=$host&fp=chrome&alpn=http%2F1.1#>>>???-2",
        "trojan://S3cretPass@$host:443?host=$host&type=ws&security=tls" +
            "&path=%2Ftr%2FQwErTy12345678%3Fed%3D2560&sni=$host&fp=chrome&alpn=http%2F1.1#>>>???-3"
    )

    @Test
    fun importsStandardBase64SubscriptionWithPlusAndSlash() {
        val payload = java.util.Base64.getEncoder()
            .encodeToString(links().joinToString("\n").toByteArray())
        assertTrue("payload must exercise '+' and '/'", payload.contains('+') && payload.contains('/'))

        val result = UniversalImportEngine.importText(payload, "BPB", "https://$host/secret/sub/raw?app=xray")
        assertEquals(3, result.validProfiles.size)

        val vless = result.validProfiles.first { it.address == host }
        assertEquals(uuid, vless.uuid)
        assertEquals("ws", vless.transport)
        assertEquals("/vl/AbCdEfGh12345678?ed=2560", vless.path)
        assertEquals(host, vless.sni)
        assertNull(RuntimeCapabilities.unsupportedReason(vless))

        val trojan = result.validProfiles.first { it.protocolType == ProtocolType.TROJAN }
        assertNull(RuntimeCapabilities.unsupportedReason(trojan))
    }

    @Test
    fun importsUrlSafeBase64Too() {
        val payload = java.util.Base64.getUrlEncoder()
            .encodeToString(links().joinToString("\n").toByteArray())
        val result = UniversalImportEngine.importText(payload, "BPB", null)
        assertEquals(3, result.validProfiles.size)
    }
}
