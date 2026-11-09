package com.example.vpn.sidecar

import org.json.JSONObject
import java.net.Inet6Address
import java.net.InetAddress

/** Values shared by the editor, saved built-in and native launch. No shell parsing. */
data class AetherSettings(
    val scan: String = "balanced",
    val noize: String = "auto",
    val endpoint: String = "",
    val apiAddress: String = "",
    val apiHost: String = "",
    val fragmentSize: String = "",
    val fragmentDelay: String = "",
    val ech: Boolean = false,
    val apiSni: String = "",
    val fingerprint: String = "chrome",
    val alias: String = "",
    val exitNode: String = "",
    val exitFinalMask: String = "",
    val exitDialMode: String = "",
    val tor: String = "off",
    val psiphon: String = "off",
    val apiRoute: String = "auto"
) {
    fun problem(carrier: String? = null): String? = when {
        apiRoute !in apiRoutes -> "Unsupported account API route"
        scan !in scans -> "Unsupported scan mode"
        noize !in noises -> "Unsupported obfuscation"
        endpoint.isNotBlank() && !socketAddress(endpoint) -> "Endpoint must be an IP and port, for example 162.159.192.1:2408 or [2606:4700::1]:443"
        apiAddress.isNotBlank() && apiAddress !in apiHosts && !ip(apiAddress) -> "API address must be an IP or a supported Cloudflare API name"
        apiHost.isNotBlank() && apiHost !in apiHosts -> "Choose a supported Cloudflare API host"
        fingerprint !in AetherTlsProfiles.names -> "Unsupported TLS fingerprint"
        apiSni.isNotBlank() && apiSni !in apiHosts -> "Choose a supported API TLS name"
        apiSni.isNotBlank() && apiHost.isNotBlank() && apiSni != apiHost -> "Cloudflare requires API SNI and host to match"
        tor !in relayModes || psiphon !in relayModes -> "Unsupported Tor or Psiphon mode"
        carrier in listOf("wireguard", "wiw") && (tor == "reverse" || psiphon == "reverse") -> "Reverse carriers require MASQUE over HTTP/2"
        tor == "reverse" && psiphon == "reverse" -> "Choose one reverse carrier"
        (tor == "only" && psiphon != "off") || (psiphon == "only" && tor != "off") -> "A standalone carrier runs on its own"
        exitFinalMask.length > 32768 || (exitFinalMask.isNotBlank() && runCatching { JSONObject(exitFinalMask) }.isFailure) -> "finalMask must be a JSON object"
        exitDialMode.length > 512 || exitDialMode.any { it.isISOControl() } -> "Invalid dialMode"
        alias.length > 120 -> "Alias is too long"
        !range(fragmentSize, 1..16384) -> "Fragment size must be 1–16384 bytes or an ascending range"
        !range(fragmentDelay, 0..1000) -> "Fragment delay must be 0–1000 ms or an ascending range"
        else -> null
    }

    val endpointAddress: String get() = if (endpoint.startsWith("[")) endpoint.substringAfter('[').substringBefore(']')
        else endpoint.substringBeforeLast(':', endpoint)
    val endpointPort: String get() = endpoint.substringAfterLast(':', "").takeIf { endpoint.contains(':') }.orEmpty()

    fun withEndpoint(address: String = endpointAddress, port: String = endpointPort): AetherSettings {
        val host = address.trim().removePrefix("[").removeSuffix("]")
        val combined = if (host.isBlank() && port.isBlank()) "" else
            (if (host.contains(':')) "[$host]" else host) + ":" + port.trim()
        return copy(endpoint = combined)
    }

    fun write(json: JSONObject): JSONObject = json.put("scan", scan).put("noize", noize)
        .put("endpoint", endpoint.trim()).put("apiAddress", apiAddress.trim())
        .put("apiHost", apiHost).put("fragmentSize", fragmentSize.trim())
        .put("fragmentDelay", fragmentDelay.trim()).put("ech", ech)
        .put("apiSni", apiSni).put("fingerprint", fingerprint).put("alias", alias.trim())
        .put("exitNode", exitNode).put("exitFinalMask", exitFinalMask).put("exitDialMode", exitDialMode.trim())
        .put("tor", tor).put("psiphon", psiphon).put("apiRoute", apiRoute)

    fun arguments(carrier: String, fragment: Boolean): List<String> {
        require(problem() == null) { problem().orEmpty() }
        return buildList {
            if (noize != "auto" && carrier !in listOf("h2", "gool", "mim")) addAll(listOf("--noize", noize))
            if (endpoint.isNotBlank()) {
                val flag = when (carrier) {
                    "h2", "gool" -> "--h2-peer"
                    "wiw" -> "--wiw-outer"
                    "mim" -> "--mim-outer"
                    else -> "--peer"
                }
                addAll(listOf(flag, endpoint.trim()))
            }
            if (apiAddress.isNotBlank()) addAll(listOf("--api-address", apiAddress.trim()))
            if (apiHost.isNotBlank()) {
                // The account API's HTTP host and authenticated TLS name must agree.
                addAll(listOf("--api-host", apiHost))
            }
            if (apiSni.isNotBlank() || apiHost.isNotBlank()) {
                addAll(listOf("--api-sni", apiSni.ifBlank { apiHost }))
                if (apiHost.isBlank()) addAll(listOf("--api-host", apiSni))
            }
            val masque = carrier !in listOf("wireguard", "wiw")
            if (fragment && carrier in listOf("h2", "gool", "mim")) {
                add("--fragment")
                if (fragmentSize.isNotBlank()) addAll(listOf("--fragment-size", fragmentSize.trim()))
                if (fragmentDelay.isNotBlank()) addAll(listOf("--fragment-delay", fragmentDelay.trim()))
            }
            if (ech && masque) addAll(listOf("--ech", "auto"))
            if (masque) addAll(AetherTlsProfiles.arguments(fingerprint))
            if (endpoint.isBlank() && carrier in listOf("wiw", "mim")) add(if (carrier == "wiw") "--wiw-scan" else "--mim-scan")
        }
    }

    companion object {
        val apiRoutes = listOf("auto", "native", "oob")
        val relayModes = listOf("off", "chain", "reverse", "only")
        val keyKinds = listOf("all", "wg", "masque", "gool-classic", "mim", "gool")
        val scans = listOf("turbo", "balanced", "thorough", "verified", "ironclad")
        val noises = listOf("auto", "off", "light", "firewall", "balanced", "gfw", "aggressive")
        val apiHosts = listOf("", "api.devices.cloudflare.com", "api.cloudflareclient.com")
        fun read(json: JSONObject) = AetherSettings(
            json.optString("scan", "balanced"), json.optString("noize", "auto"),
            json.optString("endpoint"), json.optString("apiAddress"), json.optString("apiHost"),
            json.optString("fragmentSize"), json.optString("fragmentDelay"), json.optBoolean("ech"),
            json.optString("apiSni"), json.optString("fingerprint", "chrome"), json.optString("alias"),
            json.optString("exitNode"), json.optString("exitFinalMask"), json.optString("exitDialMode"),
            json.optString("tor", "off"), json.optString("psiphon", "off"), json.optString("apiRoute", "auto"))

        private fun range(value: String, bounds: IntRange): Boolean {
            if (value.isBlank()) return true
            val parts = value.trim().split('-')
            if (parts.size !in 1..2) return false
            val numbers = parts.map { part ->
                if (part.isEmpty() || !part.all { it in '0'..'9' }) return false
                part.toIntOrNull()?.takeIf { it in bounds } ?: return false
            }
            return numbers.first() <= numbers.last()
        }
        private fun ip(value: String): Boolean {
            if (value.contains(':')) return value.matches(Regex("[0-9a-fA-F:]+")) &&
                runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
            val parts = value.split('.')
            return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.length <= 3 &&
                p.all { it in '0'..'9' } && (p.toIntOrNull() ?: 256) in 0..255 }
        }
        private fun socketAddress(value: String): Boolean {
            val text = value.trim()
            val host = if (text.startsWith("[")) text.substringAfter('[').substringBefore(']')
                else text.substringBeforeLast(':', "")
            if (text.startsWith("[") && !text.matches(Regex("\\[[0-9a-fA-F:]+\\]:[0-9]{1,5}"))) return false
            if (!text.startsWith("[") && host.contains(':')) return false
            val portText = text.substringAfterLast(':', "")
            if (portText.length !in 1..5 || !portText.all { it in '0'..'9' }) return false
            val port = portText.toIntOrNull()
            return ip(host) && port != null && port in 1..65535
        }
    }
}
