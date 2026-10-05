package com.example.ui.vip

import com.example.data.model.ProtocolType
import com.example.ui.freeconfigs.FreeNode
import com.example.ui.freeconfigs.NodeHealth
import java.util.Locale

/** What encrypts this server's traffic, for the lock tag, or null when it travels readable (plain VLESS or Trojan without TLS). */
fun FreeNode.secureLabel(): String? = when (profile.protocolType) {
    ProtocolType.HYSTERIA2, ProtocolType.TUIC -> "QUIC"
    ProtocolType.WIREGUARD -> "WireGuard"
    ProtocolType.SHADOWSOCKS -> "AEAD"
    else -> when (profile.security.lowercase(Locale.ROOT)) {
        "reality" -> "REALITY"
        "tls", "ssl" -> "TLS"
        else -> if (profile.protocolType == ProtocolType.VMESS) "AEAD" else null
    }
}

data class VipUiState(
    /** The MAXIMUS VIP subscription exists on this install. */
    val subscribed: Boolean = false,
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val testing: Boolean = false,
    val syncError: String? = null,
    val lastUpdated: Long = 0L,
    val refreshIntervalMinutes: Int = 360,
    val nodes: List<FreeNode> = emptyList(),
    /** The VPN is connected through one of these servers right now. */
    val connectedHere: Boolean = false,
    val message: String? = null
) {
    val total: Int get() = nodes.size
    val online: List<FreeNode> get() = nodes.filter { it.online }
    val encrypted: Int get() = nodes.count { it.secureLabel() != null }
    val nextUpdate: Long get() = if (lastUpdated == 0L) 0L else lastUpdated + refreshIntervalMinutes * 60_000L

    /** Every server: online fastest first, then those waiting for a test, then those that did not answer. */
    val rows: List<FreeNode>
        get() = nodes.sortedWith(
            compareBy<FreeNode> {
                when (it.health) { NodeHealth.FAST, NodeHealth.SLOW -> 0; NodeHealth.QUEUED -> 1; NodeHealth.OFFLINE -> 2 }
            }.thenBy { it.latencyMs ?: Long.MAX_VALUE }
        )

    /** The server "Connect VIP" uses: the fastest online one. */
    val best: FreeNode? get() = online.minByOrNull { it.latencyMs ?: Long.MAX_VALUE }
}
