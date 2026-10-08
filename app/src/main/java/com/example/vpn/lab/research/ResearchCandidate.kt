package com.example.vpn.lab.research

import com.example.vpn.lab.CoreCapabilityRegistry
import org.json.JSONArray
import org.json.JSONObject

/**
 * An idea found in a public source (spec sections 36-38). It is text and never runs by itself: it can only lead
 * to a LAB experiment through the same allowlist and security gate, and is VERIFIED only after real requests on
 * this phone. Ideas expire, so an old release note does not linger as news.
 */
data class ResearchCandidate(
    val id: String,
    val title: String,
    val summary: String,
    val source: String,
    val sourceUrl: String,
    val state: State,
    val capabilities: List<String>,
    val compatibility: String,
    val createdAt: Long,
    val expiresAt: Long,
    /** "AI summary" or "release notes": who wrote [summary]. */
    val summaryBy: String = "release notes"
) {
    enum class State(val title: String) {
        DISCOVERED("Discovered"), REVIEWED("Reviewed"), COMPATIBLE("Compatible"), EXPERIMENTAL("Experimental"),
        VERIFIED("Verified"), REJECTED("Rejected"), EXPIRED("Expired")
    }

    fun toJson(): JSONObject = JSONObject().put("id", id).put("t", title).put("s", summary).put("src", source).put("u", sourceUrl)
        .put("st", state.name).put("cap", JSONArray(capabilities)).put("c", compatibility).put("at", createdAt).put("x", expiresAt).put("by", summaryBy)

    companion object {
        fun fromJson(o: JSONObject): ResearchCandidate? = runCatching {
            ResearchCandidate(o.getString("id"), o.optString("t"), o.optString("s"), o.optString("src"), o.optString("u"), State.valueOf(o.optString("st")),
                o.optJSONArray("cap")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(), o.optString("c"), o.optLong("at"), o.optLong("x"),
                o.optString("by", "release notes"))
        }.getOrNull()
    }
}

/** One public release, as read from an allowlisted source. */
data class SourceRelease(val tag: String, val name: String, val notes: String, val url: String, val publishedAt: Long)

/** A read-only source of release notes. Implementations fetch text only; nothing is downloaded or executed. */
fun interface ResearchSource {
    fun releases(): List<SourceRelease>
}

/**
 * Turns releases into research candidates and checks them against what Maximus supports (CoreCapabilityRegistry).
 * Deterministic: keywords in the notes map to capability ids; the compatibility text says what is usable now.
 */
class ResearchPipeline(private val coreVersion: String = CoreCapabilityRegistry.CORE, private val ttlMs: Long = 30L * 24 * 3_600_000L) {
    private val keywords = mapOf(
        "ech" to "ech", "encrypted client hello" to "ech", "xhttp" to "xhttp", "splithttp" to "xhttp", "fragment" to "fragment",
        "finalmask" to "fragment", "fingerprint" to "tls-fingerprint", "utls" to "tls-fingerprint", "h3" to "h3", "quic" to "h3",
        "masque" to "masque", "warp" to "warp", "hysteria" to "hysteria2", "wireguard" to "wireguard", "tuic" to "tuic",
        "mlkem" to "vless-encryption", "ml-kem" to "vless-encryption", "vless encryption" to "vless-encryption", "ipv6" to "ipv6"
    )

    fun capabilitiesIn(text: String): List<String> {
        val t = text.lowercase()
        return keywords.filterKeys { Regex("\\b" + Regex.escape(it) + "\\b").containsMatchIn(t) }.values.distinct().sorted()
    }

    fun candidates(sourceName: String, releases: List<SourceRelease>, now: Long): List<ResearchCandidate> = releases.map { r ->
        val caps = capabilitiesIn(r.name + "\n" + r.notes)
        val current = coreVersion.contains(r.tag.removePrefix("v"))
        val compat = when {
            current -> "This is the core Maximus already bundles."
            caps.isEmpty() -> "No transport change Maximus tracks; needs a reviewed core update to use."
            else -> caps.joinToString("; ") { id ->
                val c = CoreCapabilityRegistry.byId(id)
                "${c?.title ?: id}: " + if (c?.usable == true) "supported now" else "not supported by the bundled core"
            } + ". New core features need a reviewed core update before any experiment."
        }
        ResearchCandidate(
            id = "RES-" + (sourceName + r.tag).hashCode().toUInt().toString(16).uppercase().take(6),
            title = "${sourceName.substringAfter('/')} ${r.tag}" + if (r.name.isNotBlank() && r.name != r.tag) " · ${r.name.take(60)}" else "",
            summary = r.notes.lineSequence().map { it.trim().trimStart('-', '*', '#', ' ') }.filter { it.length > 8 }.take(3).joinToString(" ").take(400),
            source = "GitHub releases ($sourceName)", sourceUrl = r.url,
            state = if (current) ResearchCandidate.State.REVIEWED else ResearchCandidate.State.DISCOVERED,
            capabilities = caps, compatibility = compat, createdAt = minOf(now, r.publishedAt.takeIf { it > 0 } ?: now), expiresAt = now + ttlMs
        )
    }

    /** Moves a candidate forward only on deterministic evidence; nothing becomes VERIFIED here (only the LAB can). */
    fun review(c: ResearchCandidate, now: Long): ResearchCandidate = when {
        c.state == ResearchCandidate.State.VERIFIED || c.state == ResearchCandidate.State.REJECTED -> c
        now >= c.expiresAt -> c.copy(state = ResearchCandidate.State.EXPIRED)
        c.capabilities.isNotEmpty() && c.capabilities.all { CoreCapabilityRegistry.byId(it)?.usable == true } -> c.copy(state = ResearchCandidate.State.COMPATIBLE)
        c.capabilities.any { CoreCapabilityRegistry.byId(it)?.runtime == CoreCapabilityRegistry.Support.NO } && c.capabilities.none { CoreCapabilityRegistry.byId(it)?.usable == true } ->
            c.copy(state = ResearchCandidate.State.REJECTED, compatibility = c.compatibility + " Rejected: the bundled core cannot run it.")
        else -> c.copy(state = ResearchCandidate.State.REVIEWED)
    }
}

/** Research candidates on this phone, as JSON behind load/save (the app's store pattern). Bounded. */
class ResearchStore(private val load: () -> String?, private val save: (String) -> Unit) {
    private var items: MutableList<ResearchCandidate>? = null
    private var checkedAt: Long = 0

    @Synchronized
    private fun list(): MutableList<ResearchCandidate> = items ?: runCatching {
        val o = JSONObject(load() ?: "{}")
        checkedAt = o.optLong("checked")
        val a = o.optJSONArray("items") ?: JSONArray()
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(ResearchCandidate::fromJson) }.toMutableList()
    }.getOrDefault(mutableListOf()).also { items = it }

    @Synchronized fun all(): List<ResearchCandidate> = list().sortedByDescending { it.createdAt }
    @Synchronized fun lastChecked(): Long { list(); return checkedAt }

    /** Adds new candidates and keeps the state of ones already known (a refresh never resets a review). */
    @Synchronized
    fun merge(found: List<ResearchCandidate>, now: Long): List<ResearchCandidate> {
        val l = list()
        val added = found.filter { f -> l.none { it.id == f.id } }
        l += added
        l.sortByDescending { it.createdAt }
        while (l.size > MAX) l.removeAt(l.lastIndex)
        checkedAt = now
        persist()
        return added
    }

    @Synchronized
    fun update(c: ResearchCandidate) {
        val l = list()
        val i = l.indexOfFirst { it.id == c.id }
        if (i >= 0) { l[i] = c; persist() }
    }

    private fun persist() = save(JSONObject().put("checked", checkedAt).put("items", JSONArray().apply { list().forEach { put(it.toJson()) } }).toString())

    companion object { const val MAX = 30 }
}
