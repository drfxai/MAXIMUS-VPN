package com.example.vpn.unlock

import com.example.vpn.BundledResolvers
import java.io.InputStream

/**
 * The Unlock DNS server list: anti-sanction "smart DNS" resolvers that answer sanctioned services (Gemini,
 * AI Studio, OpenAI, ...) with their own relay addresses and everything else normally.
 *
 * The list ships in the APK ([ASSET]) and can be replaced without an app update by the hash-verified resolver
 * pack of an official release ([PACK_ENTRY], see ResolverPackUpdater). A pack without that entry (every pack
 * published before Unlock mode) leaves the bundled list in use. These servers are never used by the DNS-tunnel
 * resolver scanner or the resolver parts: they only serve Unlock mode.
 */
object UnlockDnsList {
    /** Bundled default, one IPv4 per line ('#' starts a comment). */
    const val ASSET = "unlock/dns.txt"
    /** The same list inside MaximusResolverPack-<tag>.zip. */
    const val PACK_ENTRY = "unlock_dns.txt"
    /** No list needs more; a longer one is rejected rather than cut, so a bad pack never half-applies. */
    const val MAX_SERVERS = 32
    const val MAX_BYTES = 4 * 1024
    /** Custom servers the user may add on top of the list. */
    const val MAX_CUSTOM = 8

    /** Opens the verified pack copy, when an installed pack carries one (set by RayApplication). */
    @Volatile var updated: (() -> InputStream?)? = null
    /** Opens the bundled asset (set by RayApplication; tests leave it null). */
    @Volatile var bundled: (() -> InputStream?)? = null

    data class Loaded(val servers: List<String>, val fromPack: Boolean)

    /**
     * Lenient parse for a list already accepted once (bundled or installed): comments and blanks are skipped,
     * anything that is not a public IPv4 is dropped, duplicates removed, at most [MAX_SERVERS].
     */
    fun parse(text: String): List<String> = text.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() && BundledResolvers.publicIpv4(it) }
        .distinct()
        .take(MAX_SERVERS)
        .toList()

    /**
     * Strict check for a list arriving in a resolver pack: every entry must be a public IPv4 and there must be
     * 1..[MAX_SERVERS] of them. Null when the list must be refused as a whole.
     */
    fun validatePack(text: String): List<String>? {
        if (text.length > MAX_BYTES) return null
        val entries = text.lineSequence().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }.toList()
        if (entries.isEmpty() || entries.size > MAX_SERVERS) return null
        if (!entries.all(BundledResolvers::publicIpv4)) return null
        return entries.distinct()
    }

    /** The pack list when one is installed and usable, else the bundled list, else [FALLBACK]. */
    fun load(): Loaded {
        read(updated)?.let { if (it.isNotEmpty()) return Loaded(it, fromPack = true) }
        read(bundled)?.let { if (it.isNotEmpty()) return Loaded(it, fromPack = false) }
        return Loaded(FALLBACK, fromPack = false)
    }

    private fun read(open: (() -> InputStream?)?): List<String>? = try {
        open?.invoke()?.use { input ->
            val bytes = input.readNBytesCompat(MAX_BYTES + 1)
            if (bytes.size > MAX_BYTES) null else parse(String(bytes, Charsets.UTF_8))
        }
    } catch (_: java.io.IOException) {
        null
    }

    private fun InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (out.size() < limit) {
            val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    sealed class CustomResult {
        data class Added(val ip: String) : CustomResult()
        object Invalid : CustomResult()
        object Duplicate : CustomResult()
        object Full : CustomResult()
    }

    /** Validates a custom server the user typed: a public IPv4 literal, not already listed, within [MAX_CUSTOM]. */
    fun validateCustom(input: String, listed: List<String>, custom: List<String>): CustomResult {
        val ip = input.trim().removeSuffix(":53")
        if (!BundledResolvers.publicIpv4(ip)) return CustomResult.Invalid
        // "010.1.1.1" passes the regex; keep one canonical spelling so duplicates are caught.
        val canonical = ip.split('.').joinToString(".") { it.toInt().toString() }
        if (canonical in listed || canonical in custom) return CustomResult.Duplicate
        if (custom.size >= MAX_CUSTOM) return CustomResult.Full
        return CustomResult.Added(canonical)
    }

    /** Used only when the APK asset cannot be read (never expected); mirrors assets/unlock/dns.txt. */
    internal val FALLBACK = listOf("143.20.64.55", "193.233.112.67", "45.13.237.213", "5.175.161.3")
}
