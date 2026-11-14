package com.example.vpn.smart

import com.example.data.model.ProfileType
import com.example.data.model.ProtocolType
import com.example.data.model.VlessProfile

/**
 * What to do when the upload/download check after a connect finds the ~16 KB freeze
 * ([com.example.vpn.verify.TrafficEvidence.fixedSizeFreeze]): small requests pass, so the failover watchdog
 * sees a healthy tunnel and never switches, yet every bulk download stops after its first ~16 KB.
 *
 * 1. Retry the same server once with a deep ClientHello fragmentation ([DEEP_FRAGMENT_MASK]), or, when its
 *    transport cannot take a TCP fragment mask, with a saved XHTTP form of the same server; the transfer check
 *    runs again on the retry.
 * 2. Still frozen (or the retry carried nothing): switch to the next verified backup (the servers that also
 *    carried traffic in this Smart Connect, then the last-known-good servers on this network), or, with none,
 *    a Smart Connect race that leaves the frozen servers out. The traffic-blocking interface stays up meanwhile.
 * 3. At most [MAX_SWITCHES] switches per user Connect; then the session stays as it is ([Decision.Fail]).
 *
 * Pure: the service supplies the state and carries the decision out. Deep fragment parameters follow UAC SNI
 * Spoofer's "deep-fragment rescue" (TLS ClientHello split up to 100 pieces, 5 ms apart; Floxu1/
 * UAC-SNI-Spoofer-Android AdaptiveConnection.kt, see THIRD_PARTY_NOTICES.md).
 */
object FreezeFailover {
    /** UAC's deep-fragment rescue as an Xray finalmask: 5-byte ClientHello pieces, 5 ms apart, at most 100. */
    const val DEEP_FRAGMENT_MASK =
        """{"tcp":[{"type":"fragment","settings":{"packets":"tlshello","length":"5","delay":"5","maxSplit":"100"}}]}"""

    const val MAX_SWITCHES = 2

    enum class Rescue(val key: String, val label: String) {
        DEEP_FRAGMENT("deep-fragment", "FRAGMENT"),
        XHTTP("xhttp", "XHTTP")
    }

    sealed class Decision {
        /**
         * Reconnect the same server as [variant]: a deep-fragment copy keeping [serverId], or the saved XHTTP
         * config of the same address and user id.
         */
        data class Retry(val serverId: String, val variant: VlessProfile, val rescue: Rescue) : Decision()
        /** Move off [fromId]: to [to] (a verified backup) or, when null, a race without the frozen servers. */
        data class Switch(val fromId: String, val to: VlessProfile?) : Decision()
        data class Fail(val reason: String) : Decision()
    }

    data class Input(
        /** The saved server the session runs on. */
        val server: VlessProfile,
        /** The config actually running (a derived copy of [server] after a retry). */
        val running: VlessProfile,
        /** Power button without fallback, Trade mode: the config is pinned and never changed. */
        val pinned: Boolean,
        /** A same-server rescue already ran for [server] in this session. */
        val rescueTried: Boolean,
        val switchesSoFar: Int,
        /** Servers that carried traffic in this Smart Connect, best first. */
        val runnersUp: List<VlessProfile> = emptyList(),
        /** Last-known-good servers on this network (passed the transfer check), most recent first. */
        val lastGood: List<VlessProfile> = emptyList(),
        /** Servers that froze on this network (this one included once recorded). */
        val frozen: Set<String> = emptySet(),
        /** Every saved server, to find an XHTTP form of [server]. */
        val saved: List<VlessProfile> = emptyList(),
        /** [running] is carried by an engine program (Aether, Psiphon, chains), which takes no Xray mask. */
        val engineProgram: Boolean = false
    )

    fun decide(i: Input): Decision {
        if (i.pinned) return Decision.Fail("the config is pinned (power button without fallback or Trade mode)")
        if (!i.rescueTried) {
            if (!i.engineProgram && canDeepFragment(i.running)) return Decision.Retry(i.server.id, deepFragment(i.running), Rescue.DEEP_FRAGMENT)
            if (!i.engineProgram) xhttpForm(i.server, i.saved, i.frozen)?.let { return Decision.Retry(i.server.id, it, Rescue.XHTTP) }
        }
        if (i.switchesSoFar >= MAX_SWITCHES) return Decision.Fail("$MAX_SWITCHES freeze switches already made in this session")
        val next = backups(i.server.id, i.runnersUp, i.lastGood, i.frozen).firstOrNull()
        return Decision.Switch(i.server.id, next)
    }

    /** Verified backups in order: this Smart Connect's runners-up, then last-good here; never frozen, never [currentId]. */
    fun backups(currentId: String, runnersUp: List<VlessProfile>, lastGood: List<VlessProfile>, frozen: Set<String>): List<VlessProfile> =
        (runnersUp + lastGood).filter { it.id != currentId && it.id !in frozen }.distinctBy { it.id }

    /**
     * A TCP-carried TLS or REALITY config of Xray's link formats takes the TCP fragment mask. UDP transports
     * (QUIC, Hysteria, TUIC, WireGuard), raw Xray JSON and engine programs do not; neither does a config that
     * already runs this mask.
     */
    fun canDeepFragment(p: VlessProfile): Boolean =
        p.profileType == ProfileType.VLESS &&
            p.protocolType in setOf(ProtocolType.VLESS, ProtocolType.VMESS, ProtocolType.TROJAN) &&
            p.security.lowercase() in setOf("tls", "reality") &&
            p.transport.lowercase() in TCP_TRANSPORTS && !p.alpn.lowercase().split(',').map { it.trim() }.contains("h3") &&
            p.finalMask != DEEP_FRAGMENT_MASK

    /** The same config with the deep ClientHello fragmentation; everything else, the certificate check included, is unchanged. */
    fun deepFragment(p: VlessProfile): VlessProfile = p.copy(finalMask = DEEP_FRAGMENT_MASK)

    /**
     * A saved XHTTP config of the same server (same address and user id) that has not frozen here; null when
     * there is none or [server] is XHTTP already. Only a saved config is used: the server's XHTTP support is
     * never guessed.
     */
    fun xhttpForm(server: VlessProfile, saved: List<VlessProfile>, frozen: Set<String>): VlessProfile? {
        if (server.transport.lowercase() in XHTTP) return null
        return saved.firstOrNull {
            it.id != server.id && it.id !in frozen && it.transport.lowercase() in XHTTP &&
                it.address.equals(server.address, ignoreCase = true) && it.uuid == server.uuid
        }
    }

    /** One `failover · freeze16k · …` log line. */
    fun describe(d: Decision, name: (String) -> String): String = when (d) {
        is Decision.Retry -> "failover · freeze16k · ${d.rescue.label} ${name(d.serverId)}"
        is Decision.Switch -> "failover · freeze16k · SWITCH ${name(d.fromId)} -> " +
            (d.to?.let { name(it.id) } ?: "Smart Connect race (frozen servers left out)")
        is Decision.Fail -> "failover · freeze16k · FAIL (${d.reason})"
    }

    private val TCP_TRANSPORTS = setOf("tcp", "raw", "ws", "grpc", "http", "h2", "httpupgrade", "xhttp", "splithttp")
    private val XHTTP = setOf("xhttp", "splithttp")
}
