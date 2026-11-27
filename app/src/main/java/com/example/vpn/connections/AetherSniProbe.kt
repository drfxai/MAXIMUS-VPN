package com.example.vpn.connections

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Build 181 (3.2): a ≤10 s pre-check that picks the TLS name WARP's MASQUE handshakes send (Aether `--masque-sni`,
 * `AETHER_MASQUE_SNI`) on networks that filter Cloudflare.
 *
 * Build 180 on Irancell: a ClientHello naming `*.cloudflareclient.com` was dropped silently, while the same addresses
 * answered other names with a ServerHello (`www.speedtest.net` @162.159.198.1, `x.cloudflare.com` @162.159.192.1).
 * Iran's SNI filter is a blocklist (net4people #630), so a random name that is no site passes too, and Cloudflare's MASQUE
 * edge answers a name it does not know with its own self-signed MASQUE certificate.
 *
 * Certificate pinning stays. The probe accepts no certificate except one whose key is one of Aether's MASQUE SPKI pins
 * ([MASQUE_PINS], copied from the pinned Aether source, consts.rs); for any other it records the key and aborts the
 * handshake, so nothing is ever sent over an unpinned connection. The MASQUE edge chooses its certificate only after the
 * client certificate (Aether's identity), which this probe does not send, so the probe's verdict is about reachability:
 * a ServerHello (or a TLS alert) came back, so the name is not dropped by the filter. Aether then checks the pins on its
 * own handshake; a start that fails with a picked name records it ([recordFailure]) and the next pre-check skips it, so a
 * name that brings another site's certificate is a failure, never accepted. The probe sends nothing after the handshake.
 *
 * The Zero Trust range (162.159.197.x) is probed too and logged, so a device test shows whether it is reachable;
 * consumer WARP does not dial it (it needs a Zero Trust team).
 */
object AetherSniProbe {
    const val TIMEOUT_MS = 10_000L
    /** How long a pick or an all-failed verdict steers the next start on a network. */
    const val MEMORY_MS = 6 * 60 * 60_000L

    /** SHA-256 of the SubjectPublicKeyInfo of Cloudflare's MASQUE edge certificates (Aether d58f9d5, consts.rs MASQUE_PINS). */
    val MASQUE_PINS = listOf(
        "eb591b36ab26ba617e98371918c10bcdeae3742db6e76543f94be524dce1d555",
        "2a38b69b256696c09a11fbf1474fe3902363633ab00dd9c297b2de649b72d72e",
        "3fbb1d7452d32b3881eb4b5d48421445b6b9d8f5225959f033532d502637b040"
    )

    data class Candidate(val sni: String, val host: String, val port: Int = 443, val zeroTrust: Boolean = false) {
        val endpoint: String get() = "$host:$port"
    }

    enum class Outcome(val label: String, val reached: Boolean = false) {
        PINNED("ServerHello, MASQUE pin OK", true),
        OTHER_CERT("ServerHello, certificate is not a MASQUE pin (aborted; Aether's client-certificate handshake decides)", true),
        ALERT("TLS alert from the edge (not dropped)", true),
        DROPPED("no ServerHello (dropped)"),
        RESET("connection reset or refused"),
        TCP("TCP connect failed"),
        ERROR("failed")
    }

    data class Result(val candidate: Candidate, val outcome: Outcome, val ms: Long, val detail: String = "")

    /** The label a random name is remembered under: each start draws a new one. */
    const val RANDOM = "*random*"

    /**
     * A random name that is no site (passes a blocklist, gets the edge's own certificate), then the names that got a
     * ServerHello on Irancell in Build 180, then the Zero Trust range (logged only).
     */
    fun candidates(random: String = randomName(), learned: List<String> = emptyList()): List<Candidate> = listOf(
        Candidate(random, "162.159.198.1"),
        Candidate("www.speedtest.net", "162.159.198.1"),
        Candidate("x.cloudflare.com", "162.159.192.1")
    ) + learned.filter { it !in com.example.vpn.smartsni.SmartSni.LEARNED && validName(it) }.distinct().take(2)
        .mapIndexed { i, n -> Candidate(n, if (i == 0) "162.159.198.2" else "162.159.192.2") } + listOf(
        Candidate(random, "162.159.198.2"),
        Candidate(random, "162.159.197.1", zeroTrust = true),
        Candidate(random, "162.159.197.3", zeroTrust = true)
    )

    /** A name under .com that is no site: 12 random letters. */
    fun randomName(rng: java.util.Random = SecureRandom()): String =
        (1..12).map { 'a' + rng.nextInt(26) }.joinToString("") + ".com"

    /**
     * The consumer candidate to use: one that reached a pinned MASQUE certificate first, else the first the edge answered
     * (ServerHello or alert), in candidate order, leaving out names that already failed in Aether here ([failed]). Pure.
     */
    fun choose(results: List<Result>, order: List<Candidate>, failed: Set<String> = emptySet()): Candidate? {
        val usable = order.filter { c -> !c.zeroTrust && labelOf(c.sni, order) !in failed }
        fun outcome(c: Candidate) = results.firstOrNull { it.candidate == c }?.outcome
        return usable.firstOrNull { outcome(it) == Outcome.PINNED } ?: usable.firstOrNull { outcome(it)?.reached == true }
    }

    /** Names kept by name (Smart SNI's pool, Build 181 Phase 5); anything else is a drawn random name. */
    val FIXED: Set<String> = com.example.vpn.smartsni.SmartSni.POOL.toSet()

    /** [RANDOM] for a drawn random name, else the name itself. Pure. */
    @Suppress("UNUSED_PARAMETER")
    fun labelOf(sni: String, order: List<Candidate> = emptyList()): String = if (sni in FIXED) sni else RANDOM

    /** One log line per probe: SNI, masked endpoint (no full address in logs), outcome, time. Pure. */
    fun describe(r: Result): String = "SNI ${r.candidate.sni} @ ${CleanH2PeerMemory.masked(r.candidate.endpoint)}" +
        (if (r.candidate.zeroTrust) " (Zero Trust range)" else "") + " · ${r.outcome.label} · ${r.ms} ms" +
        r.detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()

    /** True when [spkiSha256Hex] is one of the MASQUE pins. Pure. */
    fun pinned(spkiSha256Hex: String): Boolean = MASQUE_PINS.any {
        MessageDigest.isEqual(it.toByteArray(), spkiSha256Hex.lowercase().toByteArray())
    }

    /** Installed by the VPN service: the probe measures the phone's network, never this app's own tunnel. */
    @Volatile var socketProtector: ((Socket) -> Boolean)? = null

    /** Replaced in tests. */
    @Volatile var handshake: (Candidate, Int) -> Result = ::probe

    // ---- per-network memory (BuiltinMethodMemory's store) -------------------------------------------------------

    private fun key(network: String) = "aetherMasqueSni#$network"
    private fun failedKey(network: String) = "aetherMasqueSniFailed#$network"

    /** Names (or [RANDOM]) that failed in Aether on [network] within [MEMORY_MS]. */
    fun failed(network: String?, now: Long = System.currentTimeMillis()): Set<String> {
        if (network.isNullOrBlank()) return emptySet()
        return ownFailed(network, now) + com.example.vpn.smartsni.SmartSni.cooling(network, now).filter { it in FIXED }
    }

    private fun ownFailed(network: String, now: Long): Set<String> {
        val saved = runCatching { BuiltinMethodMemory.read(failedKey(network)) }.getOrNull() ?: return emptySet()
        val at = saved.substringAfter('@', "").toLongOrNull() ?: return emptySet()
        if (now - at !in 0 until MEMORY_MS) return emptySet()
        return saved.substringBefore('@').split(',').filter { it.isNotBlank() }.toSet()
    }

    /** Aether failed with [sni] picked here: forget the pick, and skip the name next time. A random name counts as [RANDOM]. */
    fun recordFailure(network: String?, sni: String, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank()) return
        val all = ownFailed(network, now) + (if (sni in FIXED) sni else RANDOM)
        // Smart SNI rotation: a named pick goes on the 6 h cooldown and the next ranked name is used.
        if (sni in FIXED) com.example.vpn.smartsni.SmartSni.rotate(network, sni, now)
        runCatching {
            BuiltinMethodMemory.write(key(network), null)
            BuiltinMethodMemory.write(failedKey(network), all.joinToString(",") + "@$now")
        }
    }

    /** Aether carried traffic with [sni]: keep it for this network and clear the failures. */
    fun recordSuccess(network: String?, sni: String, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank()) return
        remember(network, sni, now)
        sessionSni = network to sni
        if (sni in FIXED) com.example.vpn.smartsni.SmartSni.recordSuccess(network, sni, now)
        runCatching { BuiltinMethodMemory.write(failedKey(network), null) }
    }

    /** The network and name of the MASQUE session that last carried verified traffic (Smart SNI mid-session rotation). */
    @Volatile var sessionSni: Pair<String, String>? = null

    /**
     * A running Aether session on [network] stopped carrying traffic (Smart failover moves on): its picked name goes
     * on Smart SNI's cooldown there and the next start uses the next name. Returns the name rotated away, or null.
     */
    fun sessionLost(network: String?, now: Long = System.currentTimeMillis()): String? {
        val (net, sni) = sessionSni ?: return null
        if (network.isNullOrBlank() || net != network) return null
        sessionSni = null
        recordFailure(network, sni, now)
        return sni
    }

    /** The remembered name for [network] (still fresh), or null. */
    fun remembered(network: String?, now: Long = System.currentTimeMillis()): String? {
        if (network.isNullOrBlank()) return null
        val saved = runCatching { BuiltinMethodMemory.read(key(network)) }.getOrNull() ?: return null
        val sni = saved.substringBefore('@')
        val at = saved.substringAfter('@', "").toLongOrNull() ?: return null
        return sni.takeIf { it.isNotBlank() && now - at in 0 until MEMORY_MS && validName(it) }
    }

    fun remember(network: String?, sni: String?, now: Long = System.currentTimeMillis()) {
        if (network.isNullOrBlank()) return
        runCatching { BuiltinMethodMemory.write(key(network), sni?.let { "$it@$now" }) }
    }

    /** A domain name Aether's `--masque-sni` accepts: letters, digits, hyphens, dots; not an address. Pure. */
    fun validName(name: String): Boolean = name.length in 4..253 &&
        Regex("^(?=.*[a-z])([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$").matches(name)

    /**
     * Probes every candidate at once and waits at most [timeoutMs]. Each result goes to [log]. Returns the chosen
     * name, or null when the edge answered none. A name is remembered for the network only once Aether carried
     * traffic with it ([recordSuccess]).
     */
    fun precheck(network: String?, timeoutMs: Long = TIMEOUT_MS, log: (String) -> Unit = {}): String? {
        // Build 181 Phase 5: a manual name wins; Smart SNI off leaves Aether's own default name.
        com.example.vpn.smartsni.SmartSniSettings.manualSni.takeIf { it.isNotBlank() }?.let {
            log("SNI pre-check: using the manual name $it (Settings → Smart SNI)"); return it
        }
        if (!com.example.vpn.smartsni.SmartSniSettings.smartSni) { log("SNI pre-check: Smart SNI is off; Aether keeps its default name"); return null }
        remembered(network)?.let { log("SNI pre-check: using $it, which carried traffic here before"); return it }
        val order = candidates(learned = com.example.vpn.smartsni.SmartSni.ranked(network))
        val started = System.currentTimeMillis()
        // Smart SNI: 3 probes at a time, all within [timeoutMs].
        val results = com.example.vpn.smartsni.SmartSni.bounded(order, com.example.vpn.smartsni.SmartSni.PARALLEL, timeoutMs,
            task = { c, left -> handshake(c, left) },
            missed = { c, late -> Result(c, Outcome.DROPPED, System.currentTimeMillis() - started,
                if (late) "no answer in ${timeoutMs / 1000}s" else "not probed: time budget") })
        results.forEach { log(describe(it)) }
        val skipped = failed(network)
        if (skipped.isNotEmpty()) log("SNI pre-check: skipped (failed or cooling down here): ${skipped.joinToString()}")
        val pick = choose(results, order, skipped)
        log(pick?.let { "SNI pre-check: MASQUE will send ${it.sni} (edge answered; Aether checks the pins)" }
            ?: "SNI pre-check: no name got an answer from the edge; Aether keeps its default name")
        return pick?.sni
    }

    /**
     * A TLS 1.3 handshake to [c] with SNI [Candidate.sni]. Only a pinned MASQUE key completes it; any other certificate
     * is rejected by the trust manager (its key is logged), so no unpinned connection is ever used.
     */
    fun probe(c: Candidate, timeoutMs: Int): Result {
        val started = System.currentTimeMillis()
        fun done(o: Outcome, detail: String = "") = Result(c, o, System.currentTimeMillis() - started, detail)
        var seenKey: String? = null
        val trust = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                throw CertificateException("client certificates are not used")
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
                val spki = MessageDigest.getInstance("SHA-256").digest(leaf.publicKey.encoded)
                    .joinToString("") { "%02x".format(it) }
                seenKey = spki
                if (!pinned(spki)) throw CertificateException("certificate key is not a MASQUE pin")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val raw = Socket()
        return try {
            socketProtector?.let { protect ->
                if (!com.example.vpn.protectTcpSocket(raw, protect)) return done(Outcome.ERROR, "socket protection failed")
            }
            try {
                raw.connect(InetSocketAddress(c.host, c.port), minOf(timeoutMs, 5_000))
            } catch (e: Exception) {
                return done(Outcome.TCP, e.javaClass.simpleName)
            }
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
            val ssl = context.socketFactory.createSocket(raw, c.sni, c.port, true) as SSLSocket
            ssl.soTimeout = timeoutMs
            ssl.sslParameters = ssl.sslParameters.apply {
                serverNames = listOf(SNIHostName(c.sni))
                // TLS 1.3 like MASQUE where the phone has it (Android 10+); older phones use their default.
                if ("TLSv1.3" in ssl.supportedProtocols) protocols = arrayOf("TLSv1.3")
                if (android.os.Build.VERSION.SDK_INT >= 29) applicationProtocols = arrayOf("h2")
            }
            try {
                ssl.startHandshake()
                done(Outcome.PINNED)
            } catch (e: SSLHandshakeException) {
                val key = seenKey
                when {
                    key != null -> done(Outcome.OTHER_CERT, "key ${key.take(16)}…")
                    e.message.orEmpty().contains("alert", ignoreCase = true) -> done(Outcome.ALERT, e.message.orEmpty().take(80))
                    else -> done(Outcome.RESET, e.message.orEmpty().take(80))
                }
            } catch (_: SocketTimeoutException) {
                done(Outcome.DROPPED)
            } catch (e: java.net.SocketException) {
                done(Outcome.RESET, e.message.orEmpty().take(80))
            } finally {
                runCatching { ssl.close() }
            }
        } catch (e: Exception) {
            done(Outcome.ERROR, e.javaClass.simpleName)
        } finally {
            runCatching { raw.close() }
        }
    }
}
