package com.example.panels.cloudflare

import com.example.panels.cloudflare.SmartDomains.Action
import com.example.panels.cloudflare.SmartDomains.Use
import org.json.JSONArray
import org.json.JSONObject

/**
 * The names the user made through the Smart domains screen, kept on the phone (no secrets in them),
 * and the plain-words text the screen shows for them. Pure, so it is tested on the JVM.
 */
object SmartNames {
    /** One record the app made. [port] is null for a DNS tunnel name server, which has no port of its own. */
    data class Saved(
        val zoneId: String,
        val zone: String,
        val name: String,
        val type: String,
        val address: String,
        val port: Int?,
        val use: Use,
        val proxied: Boolean
    ) {
        fun record() = SmartDomains.Record(null, name, type, address, proxied)
    }

    /** The label prefix for each use: cdn-, r- (direct) and ns-. */
    fun prefix(use: Use) = when (use) {
        Use.CDN -> "cdn"
        Use.DIRECT -> "r"
        Use.DNS_TUNNEL -> "ns"
    }

    /** The short chip title for each use. */
    fun chip(use: Use) = when (use) {
        Use.CDN -> "Through Cloudflare"
        Use.DIRECT -> "Direct"
        Use.DNS_TUNNEL -> "DNS tunnel"
    }

    /** "Through Cloudflare · server address hidden · port 443", "Direct · publishes 203.0.113.24 · port 8443". */
    fun detail(n: Saved): String = listOfNotNull(
        when (n.use) {
            Use.CDN -> "Through Cloudflare"
            Use.DIRECT -> "Direct"
            Use.DNS_TUNNEL -> "DNS tunnel name server"
        },
        if (n.proxied) "server address hidden" else "publishes ${n.address}",
        n.port?.let { "port $it" }
    ).joinToString(" · ")

    fun badge(proxied: Boolean) = if (proxied) "Proxied" else "DNS only"

    /** The line under the New name form. */
    fun hint(use: Use): String = when (use) {
        Use.CDN -> "Proxied through Cloudflare. Ports Cloudflare carries: ${SmartDomains.CDN_HTTPS_PORTS.sorted().joinToString()}."
        Use.DIRECT -> "DNS only. This name publishes the server's address."
        Use.DNS_TUNNEL -> "DNS only. Point the DNS tunnel's NS record at this name."
    }

    /** "Active", "Pending": the zone status as Cloudflare reports it, in sentence case. */
    fun zoneStatus(status: String): String = status.trim().ifEmpty { "unknown" }.lowercase().replaceFirstChar { it.uppercase() }

    /** A problem with the server address, or null. Only IPv4 and IPv6 addresses are accepted. */
    fun addressProblem(address: String): String? {
        val a = address.trim()
        if (a.isEmpty()) return "Enter the server's IP address"
        val v6Chars = a.contains(':') && a.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        val ok = runCatching { SmartDomains.desired("x.invalid", a, Use.DIRECT) }.isSuccess && (!a.contains(':') || v6Chars)
        return if (ok) null else "Use an IPv4 or IPv6 address, like 203.0.113.24"
    }

    /** The port typed in the form, or null when it is not a number from 1 to 65535. */
    fun parsePort(text: String): Int? = text.trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** A problem with the port field for [use], or null. A DNS tunnel name has no port. */
    fun portFieldProblem(use: Use, text: String): String? {
        if (use == Use.DNS_TUNNEL) return null
        val port = parsePort(text) ?: return "Enter a port from 1 to 65535"
        return SmartDomains.portProblem(use, port)
    }

    /** Outcome of a create, in plain words. A conflict changes nothing and is shown with [conflict] instead. */
    fun outcome(name: String, action: Action): String = when (action) {
        is Action.Create -> "Created $name."
        is Action.Update -> "$name already pointed at this server; ${action.change}."
        is Action.Unchanged -> "$name is already set up. Nothing was changed."
        is Action.Conflict -> conflict(name, action.why)
    }

    /** "Already points at 198.51.100.7. Nothing was changed. Pick another name or change it in Cloudflare yourself." */
    fun conflict(name: String, why: String): String {
        val reason = why.substringBefore("; it was left as it is").removePrefix("$name ").trim()
            .replaceFirstChar { it.uppercase() }.ifEmpty { "This name is used by another record" }
        return "$reason. Nothing was changed. Pick another name or change it in Cloudflare yourself."
    }

    /** Adds [n], replacing an earlier entry for the same name and record type. */
    fun upsert(list: List<Saved>, n: Saved): List<Saved> = list.filterNot { it.name == n.name && it.type == n.type } + n

    fun toJson(list: List<Saved>): String = JSONArray().apply {
        list.forEach {
            put(JSONObject().put("zoneId", it.zoneId).put("zone", it.zone).put("name", it.name).put("type", it.type)
                .put("address", it.address).put("port", it.port ?: 0).put("use", it.use.name).put("proxied", it.proxied))
        }
    }.toString()

    /** Reads what [toJson] wrote; entries that do not parse are skipped. */
    fun fromJson(raw: String): List<Saved> = runCatching {
        val arr = JSONArray(raw)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                Saved(
                    zoneId = o.getString("zoneId"),
                    zone = o.getString("zone"),
                    name = o.getString("name"),
                    type = o.getString("type"),
                    address = o.getString("address"),
                    port = o.optInt("port", 0).takeIf { it > 0 },
                    use = Use.valueOf(o.getString("use")),
                    proxied = o.optBoolean("proxied")
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())
}
