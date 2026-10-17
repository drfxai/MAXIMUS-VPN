package com.example.panels.cloudflare

import java.net.URLEncoder

/**
 * Cloudflare's own token template links, and the check for a copied token. Pure, so it is tested on the JVM.
 *
 * A template link opens Cloudflare's Create token page with the name, the permissions and the resources
 * already filled in. Cloudflare does not let any app create a token by itself: the user still taps
 * Continue to summary and Create Token on Cloudflare's page. The permission keys are the ones Cloudflare
 * documents for template links ("Account owned token template" in the API docs).
 */
object TokenTemplate {
    /** One permission group of a template: Cloudflare's [key], the access [type], and the chip text. */
    data class Permission(val key: String, val type: String, val label: String)

    const val PAGE = "https://dash.cloudflare.com/profile/api-tokens"

    /** Smart domains: list the zones and edit their DNS records. Nothing else. */
    val SMART_DOMAINS = listOf(
        Permission("zone", "read", "Zone · Read"),
        Permission("dns", "edit", "DNS · Edit")
    )
    const val SMART_DOMAINS_NAME = "Maximus smart domains"

    /** The BPB Worker panel: upload the Worker, make its KV namespace, read the account. */
    val WORKER = listOf(
        Permission("workers_scripts", "edit", "Workers Scripts · Edit"),
        Permission("workers_kv_storage", "edit", "Workers KV · Edit"),
        Permission("account_settings", "read", "Account settings · Read")
    )
    const val WORKER_NAME = "Maximus-BPB-Worker"

    /** `[{"key":"zone","type":"read"},…]`, the value of permissionGroupKeys before encoding. */
    fun permissionJson(permissions: List<Permission>): String =
        permissions.joinToString(",", "[", "]") { """{"key":"${it.key}","type":"${it.type}"}""" }

    /** The template link for a user token on all the user's accounts and zones (accountId=*, zoneId=all). */
    fun url(permissions: List<Permission>, name: String): String =
        "$PAGE?permissionGroupKeys=${encode(permissionJson(permissions))}&accountId=%2A&zoneId=all&name=${encode(name)}"

    /** Percent-encoding for a query value, with %20 for spaces (URLEncoder alone writes +). */
    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private val TOKEN = Regex("^[A-Za-z0-9_-]{40}$")

    /** True when [text] has the shape of a Cloudflare API token: 40 characters of A-Z, a-z, 0-9, _ and -. */
    fun looksLikeToken(text: String?): Boolean = text != null && TOKEN.matches(text.trim())

    /** "••••Xy9z": enough to recognise a copied token without showing it. */
    fun masked(token: String): String = "••••" + token.trim().takeLast(4)
}
