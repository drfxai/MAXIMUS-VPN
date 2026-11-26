package com.example.vpn.connectivity

import org.json.JSONObject
import java.security.SecureRandom

/**
 * Tells the two Iranian firewall types apart by how they treat a few TLS handshakes to Cloudflare, so Wi-Fi, fixed
 * ISPs and regions where the network code names one operator but traffic crosses the other's firewall get the right
 * recipes. Pure: the probes themselves run in [FirewallProbe]. Every rule here is a heuristic from community reports
 * (patterniha, 2026-11), not yet checked on a phone, and it only replaces the network-code guess when confident.
 *
 * What the reports describe:
 * - Irancell-type: a Chrome-shaped ClientHello to Cloudflare is cut, while a small, non-browser hello (the
 *   "semi-python" cipher list, no GREASE) split across TLS records gets through. The same on IPv4 and IPv6.
 * - Hamrah-e-Aval-type (MCI): Cloudflare on IPv4 is a server-name whitelist (a name not on it is cut however the
 *   hello is shaped, and split hellos are cut outright), while Cloudflare on IPv6 is a blacklist (the same
 *   unlisted name passes).
 *
 * Three handshakes, all to Cloudflare with one unlisted `*.workers.dev` name, all at once:
 * 1. IPv4, Chrome-shaped hello ([Probe.V4_CHROME]);
 * 2. IPv4, small semi-python hello split into TLS records and TCP writes ([Probe.V4_SPLIT]);
 * 3. IPv6, Chrome-shaped hello ([Probe.V6_CHROME]), only when the link has a global IPv6 address.
 *
 * | 1 | 2 | 3 | verdict |
 * |---|---|---|---|
 * | cut | answered | not answered / not run | Irancell-type (confident) |
 * | cut | cut | answered | MCI-type (confident) |
 * | answered | any | any | unknown: no Iranian Cloudflare filter seen |
 * | anything else | | | unknown |
 *
 * Each confident row holds at least one answered handshake on the same phone at the same moment, so a dead link or a
 * Cloudflare outage cannot produce one. "Cut" needs the TCP connection to have opened first; a connection that never
 * opens says nothing about the hello and makes the row unknown.
 */
object FirewallBehaviour {
    enum class Probe(val key: String) { V4_CHROME("v4_chrome"), V4_SPLIT("v4_split"), V6_CHROME("v6_chrome") }

    enum class Outcome(val key: String) {
        /** The server sent a TLS record back (ServerHello, HelloRetryRequest or an alert): nothing cut the hello. */
        ANSWERED("answered"),
        /** TCP opened, then a reset, an early close, silence until the time limit, or non-TLS bytes (a block page). */
        CUT("cut"),
        /** TCP never opened: says nothing about the hello. */
        UNREACHABLE("unreachable"),
        /** Not attempted (no IPv6, socket not allowed, no physical network). */
        NOT_RUN("not_run");

        companion object { fun of(key: String?): Outcome = entries.firstOrNull { it.key == key } ?: NOT_RUN }
    }

    enum class Verdict(val key: String) {
        IRANCELL_LIKE("irancell_like"), MCI_LIKE("mci_like"), UNKNOWN("unknown");

        /** The firewall these recipes are tuned for, or null for [UNKNOWN]. */
        val firewall: NetworkFirewalls.Firewall?
            get() = when (this) {
                IRANCELL_LIKE -> NetworkFirewalls.Firewall.IRANCELL
                MCI_LIKE -> NetworkFirewalls.Firewall.MCI
                UNKNOWN -> null
            }

        companion object { fun of(key: String?): Verdict = entries.firstOrNull { it.key == key } ?: UNKNOWN }
    }

    data class Classification(val verdict: Verdict, val confident: Boolean, val reason: String)

    /** The verdict for one round of the three probes. Pure. */
    fun classify(outcomes: Map<Probe, Outcome>): Classification {
        val chrome4 = outcomes[Probe.V4_CHROME] ?: Outcome.NOT_RUN
        val split4 = outcomes[Probe.V4_SPLIT] ?: Outcome.NOT_RUN
        val chrome6 = outcomes[Probe.V6_CHROME] ?: Outcome.NOT_RUN
        return when {
            chrome4 == Outcome.CUT && split4 == Outcome.ANSWERED && chrome6 != Outcome.ANSWERED ->
                Classification(Verdict.IRANCELL_LIKE, true, "Chrome hello cut on Cloudflare IPv4; a split small hello passed")
            chrome4 == Outcome.CUT && split4 == Outcome.CUT && chrome6 == Outcome.ANSWERED ->
                Classification(Verdict.MCI_LIKE, true, "Unlisted name cut on Cloudflare IPv4 in every shape; passed on IPv6")
            chrome4 == Outcome.ANSWERED ->
                Classification(Verdict.UNKNOWN, false, "Chrome hello passed on Cloudflare IPv4: neither firewall pattern")
            chrome4 == Outcome.CUT && split4 == Outcome.ANSWERED ->
                Classification(Verdict.UNKNOWN, false, "Split hello passed on IPv4 but Chrome also passed on IPv6: mixed")
            chrome4 == Outcome.CUT && split4 == Outcome.CUT ->
                Classification(Verdict.UNKNOWN, false, "Everything cut on IPv4 and no IPv6 answer: cannot tell")
            else -> Classification(Verdict.UNKNOWN, false, "Not enough handshakes ran to tell")
        }
    }

    /** How long a probe result stays usable on the same network. */
    const val TTL_MS = 24L * 60 * 60 * 1000

    /**
     * One probe round on one network. [identity] is the [com.example.vpn.smart.NetworkIdentity] key (tells Wi-Fi
     * networks apart), [coarse] the [com.example.vpn.smart.NetworkKey] ("wifi", "cell:43235") other code passes.
     */
    data class Entry(
        val identity: String,
        val coarse: String,
        val measuredAt: Long,
        val verdict: Verdict,
        val confident: Boolean,
        val reason: String,
        val outcomes: Map<Probe, Outcome>
    ) {
        fun fresh(now: Long): Boolean = now - measuredAt in 0 until TTL_MS

        /** Whether [networkKey], in either key form, names this entry's network. */
        fun matches(networkKey: String?): Boolean = networkKey != null && (networkKey == identity || networkKey == coarse)

        fun summary(): String = Probe.entries.joinToString(" ") { "${it.key}=${(outcomes[it] ?: Outcome.NOT_RUN).key}" }

        fun toJson(): JSONObject = JSONObject()
            .put("identity", identity).put("coarse", coarse).put("at", measuredAt)
            .put("verdict", verdict.key).put("confident", confident).put("reason", reason)
            .put("outcomes", JSONObject().also { o -> outcomes.forEach { (p, r) -> o.put(p.key, r.key) } })

        companion object {
            fun fromJson(o: JSONObject): Entry? = runCatching {
                val outs = o.optJSONObject("outcomes")
                Entry(
                    identity = o.getString("identity"), coarse = o.getString("coarse"), measuredAt = o.getLong("at"),
                    verdict = Verdict.of(o.optString("verdict")), confident = o.optBoolean("confident", false),
                    reason = o.optString("reason"),
                    outcomes = Probe.entries.associateWith { Outcome.of(outs?.optString(it.key)) }
                )
            }.getOrNull()
        }
    }

    /**
     * The latest probe round, kept for one network at a time. Moving to another network forgets it ([onNetwork]),
     * so a result never follows the phone to a different Wi-Fi that shares the coarse key "wifi".
     */
    class Store(private val load: () -> String?, private val save: (String) -> Unit, private val clock: () -> Long = System::currentTimeMillis) {
        @Volatile private var entry: Entry? = runCatching { load()?.let { Entry.fromJson(JSONObject(it)) } }.getOrNull()

        val latest: Entry? get() = entry

        fun record(e: Entry) {
            entry = e
            runCatching { save(e.toJson().toString()) }
        }

        /** The phone is now on [identity]: a result measured on another network is dropped. */
        fun onNetwork(identity: String?) {
            val e = entry ?: return
            if (identity == null || identity != e.identity) {
                entry = null
                runCatching { save("") }
            }
        }

        /** Whether [identity] needs a new probe round: none yet, another network, or older than [TTL_MS]. */
        fun due(identity: String): Boolean = entry?.let { it.identity != identity || !it.fresh(clock()) } ?: true

        /** The firewall the probes found for [networkKey], only when the round was confident and is still fresh. */
        fun confident(networkKey: String?): NetworkFirewalls.Firewall? =
            entry?.takeIf { it.confident && it.matches(networkKey) && it.fresh(clock()) }?.verdict?.firewall
    }

    // ---- ClientHello bytes ----

    /** One unlisted name on Cloudflare: a random `*.workers.dev` label (Cloudflare answers any of them). */
    fun probeServerName(random: SecureRandom = SecureRandom()): String {
        val letters = "abcdefghijklmnopqrstuvwxyz"
        val label = (0 until 10).map { letters[random.nextInt(letters.length)] }.joinToString("")
        return "$label.workers.dev"
    }

    private const val GREASE_BASE = 0x0a0a

    private fun grease(random: SecureRandom): Int = GREASE_BASE + 0x1010 * random.nextInt(16)

    private class Buf {
        private val out = java.io.ByteArrayOutputStream()
        fun u8(v: Int) = apply { out.write(v and 0xff) }
        fun u16(v: Int) = apply { u8(v ushr 8); u8(v) }
        fun u24(v: Int) = apply { u8(v ushr 16); u8(v ushr 8); u8(v) }
        fun bytes(b: ByteArray) = apply { out.write(b, 0, b.size) }
        fun vec8(b: ByteArray) = apply { u8(b.size); bytes(b) }
        fun vec16(b: ByteArray) = apply { u16(b.size); bytes(b) }
        fun done(): ByteArray = out.toByteArray()
    }

    private fun ext(type: Int, body: ByteArray): ByteArray = Buf().u16(type).vec16(body).done()
    private fun u16s(vararg v: Int): ByteArray = Buf().also { b -> v.forEach { b.u16(it) } }.done()
    private fun rnd(n: Int, random: SecureRandom) = ByteArray(n).also { random.nextBytes(it) }

    private fun sni(name: String): ByteArray {
        val host = name.toByteArray(Charsets.US_ASCII)
        return Buf().vec16(Buf().u8(0).vec16(host).done()).done()
    }

    private fun alpn(vararg protocols: String): ByteArray =
        Buf().vec16(Buf().also { b -> protocols.forEach { b.vec8(it.toByteArray(Charsets.US_ASCII)) } }.done()).done()

    /** A well-formed ML-KEM-768 encapsulation key: 768 coefficients below q = 3329, packed 12 bits each, then rho. */
    internal fun mlKem768Key(random: SecureRandom): ByteArray {
        val b = Buf()
        repeat(384) {
            val a = random.nextInt(3329); val c = random.nextInt(3329)
            b.u8(a); b.u8((a ushr 8) or ((c and 0x0f) shl 4)); b.u8(c ushr 4)
        }
        b.bytes(rnd(32, random))
        return b.done()
    }

    private fun handshake(cipherSuites: ByteArray, extensions: List<ByteArray>, random: SecureRandom): ByteArray {
        val body = Buf().u16(0x0303).bytes(rnd(32, random)).vec8(rnd(32, random)).vec16(cipherSuites)
            .vec8(byteArrayOf(0)).vec16(extensions.fold(ByteArray(0)) { a, e -> a + e }).done()
        return Buf().u8(1).u24(body.size).bytes(body).done()
    }

    /** [handshake] in TLS records: the first carries [firstRecord] bytes (all when 0), the second the rest. */
    internal fun records(handshake: ByteArray, firstRecord: Int = 0): List<ByteArray> {
        val cut = if (firstRecord in 1 until handshake.size) firstRecord else handshake.size
        return listOf(handshake.copyOfRange(0, cut), handshake.copyOfRange(cut, handshake.size)).filter { it.isNotEmpty() }
            .map { Buf().u8(22).u16(0x0301).vec16(it).done() }
    }

    /**
     * A Chrome-shaped ClientHello (Chrome 131+): GREASE, the post-quantum X25519MLKEM768 key share, ALPS, compressed
     * certificates, an ECH GREASE extension; about 1.8 KB, shuffled extension order like Chrome. One TLS record.
     */
    fun chromeHello(serverName: String, random: SecureRandom = SecureRandom()): ByteArray {
        val g1 = grease(random); val g2 = grease(random); val gExt1 = grease(random)
        // Chrome never repeats the GREASE extension type: the last one differs from the first.
        val gExt2 = GREASE_BASE + 0x1010 * (((gExt1 - GREASE_BASE) / 0x1010 + 1 + random.nextInt(15)) % 16)
        val suites = u16s(g1, 0x1301, 0x1302, 0x1303, 0xc02b, 0xc02f, 0xc02c, 0xc030, 0xcca9, 0xcca8, 0xc013, 0xc014,
            0x009c, 0x009d, 0x002f, 0x0035)
        val keyShare = Buf().vec16(
            Buf().u16(g2).vec16(byteArrayOf(0))
                .u16(0x11ec).vec16(mlKem768Key(random) + rnd(32, random))
                .u16(0x001d).vec16(rnd(32, random)).done()
        ).done()
        val echGrease = Buf().u8(0).u16(0x0001).u16(0x0001).u8(random.nextInt(256)).vec16(rnd(32, random))
            .vec16(rnd(144 + 32 * random.nextInt(4), random)).done()
        val middle = mutableListOf(
            ext(0x0000, sni(serverName)),
            ext(0x0017, ByteArray(0)),
            ext(0xff01, byteArrayOf(0)),
            ext(0x000a, Buf().vec16(u16s(g2, 0x11ec, 0x001d, 0x0017, 0x0018)).done()),
            ext(0x000b, byteArrayOf(1, 0)),
            ext(0x0023, ByteArray(0)),
            ext(0x0010, alpn("h2", "http/1.1")),
            ext(0x0005, byteArrayOf(1, 0, 0, 0, 0)),
            ext(0x000d, Buf().vec16(u16s(0x0403, 0x0804, 0x0401, 0x0503, 0x0805, 0x0501, 0x0806, 0x0601)).done()),
            ext(0x0012, ByteArray(0)),
            ext(0x0033, keyShare),
            ext(0x002d, byteArrayOf(1, 1)),
            ext(0x002b, Buf().vec8(u16s(grease(random), 0x0304, 0x0303)).done()),
            ext(0x001b, byteArrayOf(2, 0, 2)),
            ext(0x44cd, Buf().vec16(Buf().vec8("h2".toByteArray()).done()).done()),
            ext(0xfe0d, echGrease)
        )
        middle.shuffle(random)
        val extensions = listOf(ext(gExt1, ByteArray(0))) + middle + listOf(ext(gExt2, byteArrayOf(0)))
        return records(handshake(suites, extensions, random)).single()
    }

    /**
     * A small Go-style ClientHello with the semi-python cipher list ([com.example.panels.BpbFix.CIPHER_SUITES]): no
     * GREASE, X25519 only, ALPN http/1.1, about 250 bytes. Split into two TLS records, the first [firstRecord] bytes
     * long (patterniha's Serverless fragA uses 6 to 32), which the caller sends as separate TCP writes.
     */
    fun splitSmallHello(serverName: String, firstRecord: Int = 6, random: SecureRandom = SecureRandom()): List<ByteArray> {
        val suites = u16s(0x1302, 0x1303, 0x1301, 0xc02c, 0xc030, 0xc02b, 0xc02f, 0xcca9, 0xcca8, 0xc00a, 0xc014, 0xc023, 0xc027)
        val extensions = listOf(
            ext(0x0000, sni(serverName)),
            ext(0x0005, byteArrayOf(1, 0, 0, 0, 0)),
            ext(0x000a, Buf().vec16(u16s(0x001d, 0x0017, 0x0018)).done()),
            ext(0x000b, byteArrayOf(1, 0)),
            ext(0x000d, Buf().vec16(u16s(0x0804, 0x0403, 0x0805, 0x0503, 0x0806, 0x0603, 0x0401, 0x0501, 0x0601)).done()),
            ext(0x0010, alpn("http/1.1")),
            ext(0x0017, ByteArray(0)),
            ext(0xff01, byteArrayOf(0)),
            ext(0x002b, Buf().vec8(u16s(0x0304, 0x0303)).done()),
            ext(0x002d, byteArrayOf(1, 1)),
            ext(0x0033, Buf().vec16(Buf().u16(0x001d).vec16(rnd(32, random)).done()).done())
        )
        return records(handshake(suites, extensions, random), firstRecord)
    }

    /**
     * What the first bytes back from the server say. A TLS handshake or alert record from a TLS 1.x server is an
     * answer; anything else (an HTTP block page, garbage) counts as cut. Null: too few bytes to tell yet.
     */
    fun readReply(head: ByteArray, length: Int): Outcome? {
        if (length <= 0) return null
        val type = head[0].toInt() and 0xff
        if (type != 22 && type != 21) return Outcome.CUT
        if (length < 3) return null
        return if ((head[1].toInt() and 0xff) == 3 && (head[2].toInt() and 0xff) in 1..4) Outcome.ANSWERED else Outcome.CUT
    }
}
