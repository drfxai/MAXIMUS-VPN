package com.example.vpn.unlock

import com.example.vpn.dns.DnsMessageValidator
import com.example.vpn.dns.ResolverScanner
import java.io.ByteArrayOutputStream
import java.net.InetAddress

/**
 * Anti-censorship query shapes for Unlock mode (optional, off by default).
 *
 * Each shape rewrites only the bytes of the DNS question Unlock mode already forwards, so a DPI box that parses
 * DNS the textbook way skips it while a tolerant resolver still answers it. The shapes are the three DNS rows
 * that bypassed the Iranian DNS censor in "CircumVolve: Automated Discovery of Censorship Evasion Strategies Using
 * Large Language Models" (Zohaib, Sippe, Sheffey, Wu, Wustrow, Houmansadr; ACM CCS 2026; Table 2,
 * https://gfw.report/publications/ccs26/en/). They are re-implemented from the paper's descriptions (no code or
 * data copied). Results were measured in a lab against one vantage point: real networks differ, and a resolver
 * may refuse any shape (FORMERR or silence), which is why every shape is tried per server and remembered per
 * network only when it really answered.
 */
enum class QueryShape(val key: String) {
    /** Table 2 "TC / Z Bit": truncation bit and the reserved Z bit both set in the question. */
    TC_Z("tc_z"),
    /** Z bit only (resolvers such as Quad9 refuse a question with TC set). */
    Z("z"),
    /** TC bit only. */
    TC("tc"),
    /** Table 2 "ANCOUNT/ARCOUNT/NSCOUNT > 0": the header claims one answer record that is not sent. */
    COUNT_CLAIM("count"),
    /** Table 2 "Deep Pointer Chain": the name is reached through more than 18 compression-pointer jumps. */
    POINTER_CHAIN("chain");

    companion object {
        fun of(key: String?): QueryShape? = entries.firstOrNull { it.key == key }
    }
}

object UnlockDnsShaping {
    /** Pointer links between the question's pointer and the literal name: 1 + 20 = 21 jumps (> 18). */
    const val CHAIN_LINKS = 20
    /** First link sits right after the question (12 header + 2 pointer + 4 type/class). */
    const val CHAIN_START = 18
    /** One shaped attempt over UDP (TCP only when the answer is truncated); silence means "not this shape". */
    const val ATTEMPT_TIMEOUT_MS = 1_000
    /** All shaped retries of one app question together; the app keeps its plain answer when none works. */
    const val RETRY_BUDGET_MS = 2_500L
    /** All shaped health checks of one server together (adds to the check's deadline only when shaping is on). */
    const val CHECK_BUDGET_MS = 5_000L

    /** The single question of an app's DNS query (name in uncompressed wire form, terminating zero included). */
    class Question(val header: ByteArray, val name: ByteArray, val type: Int, val klass: Int)

    private fun u16(b: ByteArray, p: Int) = ((b[p].toInt() and 0xff) shl 8) or (b[p + 1].toInt() and 0xff)

    /** A standard query (QR=0, opcode 0) with exactly one uncompressed question; null for anything else. */
    fun question(message: ByteArray): Question? {
        if (message.size < 17 || message.size > UnlockDnsWire.MAX_MESSAGE) return null
        if (message[2].toInt() and 0xF8 != 0) return null
        if (u16(message, 4) != 1) return null
        var p = 12
        var wire = 0
        while (true) {
            if (p >= message.size) return null
            val l = message[p].toInt() and 0xff
            if (l == 0) { p += 1; break }
            if (l > 63) return null
            wire += l + 1
            if (wire > 254) return null
            p += 1 + l
        }
        if (p + 4 > message.size) return null
        return Question(message.copyOf(12), message.copyOfRange(12, p), u16(message, p), u16(message, p + 2))
    }

    private fun pointer(offset: Int) = byteArrayOf((0xC0 or (offset shr 8)).toByte(), offset.toByte())

    /**
     * [query] rewritten with [shape]: the same ID, RD and AD/CD bits and the same single question; additional
     * (EDNS) records are dropped. Null when [query] is not a plain single-question query.
     */
    fun shape(query: ByteArray, shape: QueryShape): ByteArray? {
        val q = question(query) ?: return null
        val header = ByteArray(12)
        header[0] = q.header[0]
        header[1] = q.header[1]
        header[2] = (q.header[2].toInt() and 0x01).toByte()
        header[3] = (q.header[3].toInt() and 0x30).toByte()
        header[5] = 1
        val tail = byteArrayOf((q.type shr 8).toByte(), q.type.toByte(), (q.klass shr 8).toByte(), q.klass.toByte())
        when (shape) {
            QueryShape.TC_Z -> { header[2] = (header[2].toInt() or 0x02).toByte(); header[3] = (header[3].toInt() or 0x40).toByte() }
            QueryShape.Z -> header[3] = (header[3].toInt() or 0x40).toByte()
            QueryShape.TC -> header[2] = (header[2].toInt() or 0x02).toByte()
            QueryShape.COUNT_CLAIM -> header[7] = 1
            QueryShape.POINTER_CHAIN -> {
                val out = ByteArrayOutputStream()
                out.write(header)
                out.write(pointer(CHAIN_START))
                out.write(tail)
                for (i in 1..CHAIN_LINKS) out.write(pointer(CHAIN_START + 2 * i))
                out.write(q.name)
                return out.toByteArray()
            }
        }
        return header + q.name + tail
    }

    private fun lower(b: Byte): Int = (b.toInt() and 0xff).let { if (it in 65..90) it + 32 else it }

    /**
     * The resolver's [response] to a shaped question, turned into a valid answer to the app's [original]
     * question: same ID, the app's own question bytes (and letter case) in place of the echoed one, reserved Z
     * bit cleared. The resolver must echo exactly one uncompressed question equal to the original (that keeps
     * every compression offset in the answer valid). Null when it does not, or when it answered FORMERR /
     * NOTIMP (it refused the shape), or when the result is not a well-formed answer to [original].
     */
    fun restore(original: ByteArray, response: ByteArray): ByteArray? {
        val q = question(original) ?: return null
        val end = 12 + q.name.size + 4
        if (response.size < end || response.size > UnlockDnsWire.MAX_MESSAGE) return null
        if (response[0] != q.header[0] || response[1] != q.header[1]) return null
        if (response[2].toInt() and 0x80 == 0 || response[2].toInt() and 0x78 != 0) return null
        if (u16(response, 4) != 1) return null
        val rcode = response[3].toInt() and 0x0f
        if (rcode == 1 || rcode == 4) return null
        for (i in q.name.indices) if (lower(response[12 + i]) != lower(q.name[i])) return null
        val p = 12 + q.name.size
        if (u16(response, p) != q.type || u16(response, p + 2) != q.klass) return null
        val out = response.copyOf()
        System.arraycopy(q.name, 0, out, 12, q.name.size)
        out[3] = (out[3].toInt() and 0x40.inv()).toByte()
        return out.takeIf { DnsMessageValidator.isResponseTo(original, it, it.size) }
    }

    /** True when an A/AAAA answer to [query] carries a filter or bogon address (Iran's 10.10.34.x block page). */
    fun poisoned(query: ByteArray, response: ByteArray): Boolean =
        DnsMessageValidator.addressAnswers(query, response, response.size).any { bytes ->
            runCatching { ResolverScanner.poisoned(InetAddress.getByAddress(bytes).hostAddress ?: "") }.getOrDefault(false)
        }
}

/**
 * Which shape worked for which server on which network. An [Entry.always] shape came from a health check the
 * plain question failed (every question to that server is shaped first); otherwise it came from a filtered app
 * answer at runtime (plain first, this shape first among the retries). Kept small and persisted as one string.
 */
class ShapeBook(private val maxEntries: Int = MAX_ENTRIES) {
    data class Entry(val shape: QueryShape, val always: Boolean)

    private val map = LinkedHashMap<String, Entry>(16, 0.75f, true)
    /** Told the encoded book after every change (UnlockDns saves it in SharedPreferences). */
    @Volatile var onChange: ((String) -> Unit)? = null

    @Synchronized fun get(network: String, server: String): Entry? = map[key(network, server)]

    fun put(network: String, server: String, entry: Entry) {
        val encoded = synchronized(this) {
            val k = key(network, server)
            if (map[k] == entry) return
            map[k] = entry
            while (map.size > maxEntries) map.remove(map.keys.first())
            encodeLocked()
        }
        onChange?.invoke(encoded)
    }

    fun remove(network: String, server: String) {
        val encoded = synchronized(this) {
            if (map.remove(key(network, server)) == null) return
            encodeLocked()
        }
        onChange?.invoke(encoded)
    }

    @Synchronized fun encode(): String = encodeLocked()

    private fun encodeLocked(): String =
        map.entries.joinToString(",") { (k, e) -> "$k=${e.shape.key}/${if (e.always) "A" else "P"}" }

    /** Replaces the book with [text] (from [encode]); malformed entries are skipped. */
    @Synchronized fun decode(text: String?) {
        map.clear()
        text.orEmpty().split(',').forEach { item ->
            val k = item.substringBefore('=', "")
            val v = item.substringAfter('=', "")
            val shape = QueryShape.of(v.substringBefore('/'))
            val flag = v.substringAfter('/', "")
            if (KEY.matches(k) && shape != null && (flag == "A" || flag == "P")) map[k] = Entry(shape, flag == "A")
        }
        while (map.size > maxEntries) map.remove(map.keys.first())
    }

    companion object {
        const val MAX_ENTRIES = 64
        private val KEY = Regex("[A-Za-z0-9:._-]{1,64}\\|(?:[0-9]{1,3}\\.){3}[0-9]{1,3}")
        fun key(network: String, server: String) = network.filter { it.isLetterOrDigit() || it in ":._-" }.take(64).ifEmpty { "unknown" } + "|" + server
    }
}

/**
 * Answers one app question from an Unlock server, with optional query shaping: plain first (or the server's
 * health-check shape first), and when the plain answer is filtered (block-page / bogon address, or another
 * address answered for the server) each shape in turn, within [UnlockDnsShaping.RETRY_BUDGET_MS]. The first shape
 * whose answer is clean is remembered for this network + server. When none works the app gets what plain gave.
 */
class ShapedForwarder(
    private val transport: Transport,
    private val book: ShapeBook,
    private val network: () -> String,
    private val clock: () -> Long = System::currentTimeMillis
) {
    interface Transport {
        fun plain(server: String, query: ByteArray, timeoutMs: Int): UnlockDnsWire.Exchange?
        fun shaped(server: String, query: ByteArray, shape: QueryShape, timeoutMs: Int): UnlockDnsWire.Exchange?
    }

    private fun clean(query: ByteArray, e: UnlockDnsWire.Exchange) = !e.intercepted && !UnlockDnsShaping.poisoned(query, e.response)

    fun resolve(server: String, query: ByteArray, shaping: Boolean, timeoutMs: Int): UnlockDnsWire.Exchange? {
        if (!shaping || UnlockDnsShaping.question(query) == null) return transport.plain(server, query, timeoutMs)?.takeIf { !it.intercepted }
        val net = network()
        val known = book.get(net, server)
        var tried: QueryShape? = null
        if (known?.always == true) {
            tried = known.shape
            transport.shaped(server, query, known.shape, UnlockDnsShaping.ATTEMPT_TIMEOUT_MS)?.let { if (clean(query, it)) return it }
        }
        val plain = transport.plain(server, query, timeoutMs) ?: return null
        if (clean(query, plain)) return plain
        val started = clock()
        val order = listOfNotNull(known?.shape) + QueryShape.entries.filter { it != known?.shape }
        for (shape in order) {
            if (shape == tried) continue
            if (clock() - started >= UnlockDnsShaping.RETRY_BUDGET_MS) break
            val answer = transport.shaped(server, query, shape, UnlockDnsShaping.ATTEMPT_TIMEOUT_MS) ?: continue
            if (clean(query, answer)) {
                book.put(net, server, ShapeBook.Entry(shape, always = known?.always == true))
                return answer
            }
        }
        return plain.takeIf { !it.intercepted }
    }
}

/** [ShapedForwarder.Transport] on the real wire ([UnlockDnsWire]). */
class WireTransport(private val protector: UnlockDnsWire.Protector) : ShapedForwarder.Transport {
    override fun plain(server: String, query: ByteArray, timeoutMs: Int) = UnlockDnsWire.exchange(server, query, timeoutMs, protector)
    override fun shaped(server: String, query: ByteArray, shape: QueryShape, timeoutMs: Int) =
        UnlockDnsWire.exchangeShaped(server, query, shape, timeoutMs, protector)
}
