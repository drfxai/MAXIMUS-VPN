package com.example.vpn.connections

/**
 * Per-network memory of the Cloudflare WARP/MASQUE gateways that recently carried verified HTTPS over
 * MASQUE HTTP/2 ("clean H2 peers"). Adapted from UAC SNI Spoofer's saved clean HTTP/2 list
 * (PowCoreConfig.cleanH2File and the core's load/remember/forget_clean_h2, Floxu1/UAC-SNI-Spoofer-Android,
 * see THIRD_PARTY_NOTICES.md): the saved addresses are tried before any gateway scan, so a reconnect on a
 * network where HTTP/2 worked skips Aether's 30-60 s scan.
 *
 * Differences from UAC: the list is kept per network key ("cell:43235", "wifi:<fp>") instead of globally,
 * so an Irancell gateway is never tried first on Hamrah-e-Aval; entries expire after [TTL_MS]; a peer is
 * dropped as soon as a connect through it fails, and after [MAX_PREWARM_MISSES] screen-on prewarm misses in a
 * row. Whether the peer worked with the SNI-split ClientHello ([Peer.split]) is kept so the same hello is
 * used again. Only public IP:port literals are stored (Cloudflare anycast addresses, nothing personal).
 */
object CleanH2PeerMemory {
    @Volatile var read: (String) -> String? = { null }
    @Volatile var write: (String, String?) -> Unit = { _, _ -> }

    const val TTL_MS = 3L * 24 * 60 * 60 * 1000
    /** UAC keeps its speed pick small for battery; three is enough to survive one edge going bad. */
    const val MAX_PEERS = 3
    const val MAX_PREWARM_MISSES = 3

    data class Peer(
        val address: String,
        val split: Boolean = false,
        val at: Long,
        /** Last TCP connect time measured by the prewarm, when there was one. */
        val rttMs: Long? = null,
        val misses: Int = 0
    ) {
        fun encode(): String = listOf(address, if (split) "1" else "0", at.toString(), rttMs?.toString().orEmpty(), misses.toString()).joinToString(",")
        companion object {
            fun decode(text: String): Peer? {
                val p = text.split(',')
                if (p.size < 3) return null
                val address = p[0].takeIf(::validPeer) ?: return null
                return Peer(address, p[1] == "1", p[2].toLongOrNull() ?: return null,
                    p.getOrNull(3)?.toLongOrNull(), p.getOrNull(4)?.toIntOrNull() ?: 0)
            }
        }
    }

    private fun key(network: String) = "cleanH2|$network"

    @Synchronized
    fun peers(network: String?, now: Long = System.currentTimeMillis()): List<Peer> {
        if (network.isNullOrBlank()) return emptyList()
        val all = parse(read(key(network)))
        val live = all.filter { now - it.at in 0 until TTL_MS }
        if (live.size != all.size) save(network, live)
        return live
    }

    /** [address] carried verified HTTPS over HTTP/2 on [network]: put it first. */
    @Synchronized
    fun remember(network: String?, address: String, split: Boolean = false, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank() || !validPeer(address)) return
        val rest = peers(network, now).filter { it.address != address }
        save(network, (listOf(Peer(address, split, now)) + rest).take(MAX_PEERS))
    }

    /** A connect through [address] on [network] failed: it is no longer clean here. */
    @Synchronized
    fun forget(network: String?, address: String) {
        if (network.isNullOrBlank()) return
        val all = parse(read(key(network)))
        if (all.none { it.address == address }) return
        save(network, all.filter { it.address != address })
    }

    /**
     * Prewarm result: [rtt] maps each probed address to its TCP connect time, or null when it did not answer.
     * Answering peers move to the front by connect time; silent ones keep their place behind them and are
     * dropped after [MAX_PREWARM_MISSES] misses in a row. Returns the new order.
     */
    @Synchronized
    fun applyPrewarm(network: String?, rtt: Map<String, Long?>, now: Long = System.currentTimeMillis()): List<Peer> {
        if (network.isNullOrBlank()) return emptyList()
        val updated = peers(network, now).mapNotNull { peer ->
            if (peer.address !in rtt) return@mapNotNull peer
            val ms = rtt[peer.address]
            if (ms != null) peer.copy(rttMs = ms, misses = 0)
            else peer.copy(rttMs = null, misses = peer.misses + 1).takeIf { it.misses < MAX_PREWARM_MISSES }
        }
        val ordered = updated.withIndex().sortedWith(compareBy({ if (it.value.rttMs != null && it.value.misses == 0) 0 else 1 },
            { it.value.rttMs ?: Long.MAX_VALUE }, { it.index })).map { it.value }
        save(network, ordered)
        return ordered
    }

    private fun save(network: String, peers: List<Peer>) =
        write(key(network), peers.takeIf { it.isNotEmpty() }?.joinToString(";") { it.encode() })

    private fun parse(text: String?): List<Peer> =
        text.orEmpty().split(';').filter { it.isNotBlank() }.mapNotNull(Peer::decode)

    /** "1.2.3.4:443" or "[2606:4700::1]:443" with a public address (never a private or reserved one). */
    fun validPeer(value: String): Boolean {
        val text = value.trim()
        if (text != value || text.isEmpty() || text.length > 64) return false
        val host = if (text.startsWith("[")) text.substringAfter('[').substringBefore(']') else text.substringBeforeLast(':', "")
        if (!text.startsWith("[") && host.contains(':')) return false
        if (text.startsWith("[") && !text.matches(Regex("\\[[0-9a-fA-F:]+\\]:[0-9]{1,5}"))) return false
        val port = text.substringAfterLast(':', "").toIntOrNull() ?: return false
        if (port !in 1..65535) return false
        return com.example.vpn.connectivity.RecoverySecurityGate.isIpLiteral(host) &&
            !com.example.vpn.connectivity.RecoverySecurityGate.isPrivateOrReserved(host)
    }

    /** "162.159.198.x:443" style, for logs: the last part of the address is hidden. */
    fun masked(address: String): String =
        if (address.startsWith("[")) address.substringBefore(']').substringBeforeLast(':') + ":x]:" + address.substringAfterLast(':')
        else address.substringBeforeLast(':').substringBeforeLast('.') + ".x:" + address.substringAfterLast(':')
}
