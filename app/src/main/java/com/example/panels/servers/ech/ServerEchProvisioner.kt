package com.example.panels.servers.ech

import com.example.panels.XuiApiClient
import org.json.JSONArray
import org.json.JSONObject

/** The 3X-UI panel calls ECH provisioning needs ([XuiApiClient] in the app, a fake in unit tests). */
interface EchPanelApi {
    fun listInbounds(): JSONArray
    /** (echServerKeys, echConfigList) made by the server's own Xray. */
    fun newEchCert(outer: String): Pair<String, String>
    fun updateInbound(id: Int, inbound: JSONObject)
    fun inbound(id: Int): JSONObject?
    /** "running", "stop", "error", or "" when the panel does not say. */
    fun xrayState(): String
    fun restartXray()
    fun links(): List<String>
}

class XuiEchApi(private val api: XuiApiClient) : EchPanelApi {
    override fun listInbounds() = api.listInbounds()
    override fun newEchCert(outer: String) = api.newEchCert(outer)
    override fun updateInbound(id: Int, inbound: JSONObject) = api.updateInbound(id, inbound)
    override fun inbound(id: Int) = api.inbound(id)
    override fun xrayState() = api.xrayState()
    override fun restartXray() = api.restartXray()
    override fun links() = api.allLinks()
}

/** No TLS inbound on the server can carry ECH; the caller may offer to create one. */
class NoEchInboundException(message: String) : IllegalStateException(message)

/**
 * Build 181 (Phase 6): turns ECH on, rotates its key, or turns it off on a server's 3X-UI TLS inbounds.
 *
 * Every change: the inbounds as they are now are kept first ([backup], the caller stores them encrypted; a database
 * copy on the server is made beforehand over SSH by the caller when it can sign in), then each inbound is changed and
 * read back, then Xray restarts and must report running. Any failure puts every changed inbound back as it was and
 * restarts Xray again, then reports what failed. Log lines name ports and the outer name only, never keys.
 */
class ServerEchProvisioner(
    private val api: EchPanelApi,
    /** Stores the inbounds before a change (JSON array), or clears the copy (null) once the change is confirmed. */
    private val backup: (String?) -> Unit,
    /** The certificate pin an inbound's clients need (the self-signed panel certificate), or "". */
    private val pinFor: (JSONObject) -> String = { "" },
    /** Whether a share link from the panel is for [host]:[port]. */
    private val linkFor: (link: String, host: String, port: Int) -> Boolean = ::defaultLinkMatch,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = {}
) {
    /**
     * Turns ECH on with outer name [outer] on every eligible TLS inbound (or rotates it: the inbound's current key set
     * is kept beside the new one, [ServerEch.MAX_KEY_SETS]). Returns the record the phone keeps.
     */
    fun enable(host: String, outer: String, rotateDays: Int): ServerEch.Record {
        ServerEch.outerProblem(outer)?.let { throw IllegalArgumentException(it) }
        val name = outer.trim().lowercase()
        val inbounds = ServerEch.eligible(api.listInbounds())
        if (inbounds.isEmpty()) throw NoEchInboundException(
            "No TLS inbound on this server can carry ECH (REALITY and Hysteria2 cannot). Create a TLS inbound first.")
        val (keys, list) = api.newEchCert(name)
        ServerEch.checkGenerated(keys, list, name)?.let { error("ECH was not turned on: $it. Nothing on the server changed.") }
        change(inbounds) { inb -> ServerEch.withEch(inb, ServerEch.combine(keys, ServerEch.currentServerKeys(inb)), list) }
        val confirmed = runCatching { ServerEch.eligible(api.listInbounds()) }.getOrDefault(inbounds)
        val links = runCatching { api.links() }.getOrDefault(emptyList())
        val kept = confirmed.filter { ServerEch.storedConfigList(it) == list }.ifEmpty { inbounds }
        log("Server ECH: on for ${kept.size} TLS inbound(s), ports ${kept.joinToString { it.optInt("port").toString() }}, outer name $name")
        return ServerEch.Record(true, name, list, now(), rotateDays, kept.map { inb ->
            val port = inb.optInt("port")
            ServerEch.Inbound(inb.optInt("id"), port, ServerEch.innerName(inb, host), pinFor(inb),
                links.firstOrNull { linkFor(it, host, port) }.orEmpty())
        })
    }

    /** Removes ECH from every inbound that serves it. */
    fun disable() {
        val inbounds = ServerEch.eligible(api.listInbounds()).filter { ServerEch.currentServerKeys(it) != null || ServerEch.storedConfigList(it) != null }
        if (inbounds.isEmpty()) { log("Server ECH: no inbound serves ECH"); return }
        change(inbounds) { inb -> ServerEch.withEch(inb, null, null) }
        log("Server ECH: off on ports ${inbounds.joinToString { it.optInt("port").toString() }}")
    }

    private fun change(inbounds: List<JSONObject>, edit: (JSONObject) -> JSONObject) {
        backup(JSONArray(inbounds.map { JSONObject(it.toString()) }).toString())
        val changed = mutableListOf<JSONObject>()
        try {
            for (inb in inbounds) {
                val id = inb.optInt("id")
                val next = edit(inb)
                api.updateInbound(id, next)
                changed += inb
                val stored = api.inbound(id) ?: error("inbound on port ${inb.optInt("port")} disappeared")
                check(ServerEch.storedConfigList(stored) == ServerEch.storedConfigList(next) &&
                    (ServerEch.currentServerKeys(stored) != null) == (ServerEch.currentServerKeys(next) != null)) {
                    "the panel did not store the ECH settings on port ${inb.optInt("port")}"
                }
            }
            api.restartXray()
            check(waitRunning()) { "Xray did not come back after the change" }
        } catch (e: Exception) {
            val restored = rollback(changed)
            val why = com.example.panels.servers.ServerLogSanitizer.redact(e.message ?: e.javaClass.simpleName)
            throw IllegalStateException(if (restored) "ECH change failed ($why). The previous inbound settings were restored."
                else "ECH change failed ($why) and restoring the previous settings also failed. Restore the 3X-UI backup made before the change.", e)
        }
        backup(null)
    }

    /** Puts [changed] back as they were; true when every one was restored and Xray runs. */
    private fun rollback(changed: List<JSONObject>): Boolean {
        var ok = true
        for (inb in changed.asReversed()) {
            ok = runCatching { api.updateInbound(inb.optInt("id"), inb) }.isSuccess && ok
        }
        if (changed.isNotEmpty()) ok = runCatching { api.restartXray() }.isSuccess && ok
        ok = ok && waitRunning()
        log("Server ECH: " + if (ok) "change rolled back" else "rollback incomplete")
        return ok
    }

    /** Xray reports running within ~12 s ("" = the panel does not report state: accepted). */
    private fun waitRunning(): Boolean {
        repeat(6) { i ->
            val s = runCatching { api.xrayState() }.getOrDefault("")
            if (s == "running") return true
            if (s.isBlank() && i >= 1) return true
            sleep(2_000)
        }
        return false
    }

    companion object {
        /** vless:// and trojan:// links name host:port after '@'; vmess:// is base64 JSON with "add" and "port". */
        fun defaultLinkMatch(link: String, host: String, port: Int): Boolean {
            val h = host.trim().lowercase().removePrefix("[").removeSuffix("]")
            val l = link.trim()
            if (l.startsWith("vmess://", true)) {
                val json = runCatching { String(java.util.Base64.getDecoder().decode(l.substring(8).trim())) }
                    .recoverCatching { String(java.util.Base64.getUrlDecoder().decode(l.substring(8).trim())) }.getOrNull() ?: return false
                val o = runCatching { JSONObject(json) }.getOrNull() ?: return false
                return o.optString("add").lowercase().removePrefix("[").removeSuffix("]") == h && o.optString("port") == port.toString()
            }
            if (!l.startsWith("vless://", true) && !l.startsWith("trojan://", true)) return false
            val authority = l.substringAfter("://").substringAfter('@').substringBefore('?').substringBefore('#').substringBefore('/')
            val p = authority.substringAfterLast(':').toIntOrNull() ?: return false
            val a = authority.substringBeforeLast(':').lowercase().removePrefix("[").removeSuffix("]")
            return a == h && p == port
        }
    }
}
