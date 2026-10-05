package com.example

import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile
import com.example.ui.freeconfigs.FreeNode
import com.example.ui.freeconfigs.NodeHealth
import com.example.ui.vip.VipUiState
import com.example.ui.vip.secureLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the VIP screen counts as encrypted, lists first and offers to connect to. */
class VipStateTest {
    private fun node(id: String, ms: Long?, health: NodeHealth = FreeNode.healthOf(ms), type: ProtocolType = ProtocolType.VLESS, security: String = "reality") =
        FreeNode(VlessProfile(id = id, name = id, address = "203.0.113.1", port = 443, uuid = "u", security = security, protocolType = type), null, ms, health)

    private val reality = node("reality", 140)
    private val plain = node("plain", 96, security = "none")
    private val hy2 = node("hy2", null, NodeHealth.OFFLINE, ProtocolType.HYSTERIA2, security = "none")
    private val waiting = node("wait", null, type = ProtocolType.TROJAN, security = "tls")
    private val state = VipUiState(nodes = listOf(hy2, waiting, reality, plain))

    @Test fun encryptionLabels() {
        assertEquals("REALITY", reality.secureLabel())
        assertNull(plain.secureLabel())
        assertEquals("QUIC", hy2.secureLabel())
        assertEquals("TLS", waiting.secureLabel())
        assertEquals("AEAD", node("ss", 1, type = ProtocolType.SHADOWSOCKS, security = "none").secureLabel())
        assertEquals("AEAD", node("vm", 1, type = ProtocolType.VMESS, security = "none").secureLabel())
        assertNull(node("tj", 1, type = ProtocolType.TROJAN, security = "none").secureLabel())
        assertEquals(3, state.encrypted)
    }

    @Test fun onlineFastestFirstThenWaitingThenOffline() {
        assertEquals(listOf(plain, reality, waiting, hy2), state.rows)
        assertEquals(plain, state.best)
        assertEquals(2, state.online.size)
    }

    @Test fun nothingToConnectWhenNothingAnswered() {
        assertNull(VipUiState(nodes = listOf(hy2, waiting)).best)
    }
}
