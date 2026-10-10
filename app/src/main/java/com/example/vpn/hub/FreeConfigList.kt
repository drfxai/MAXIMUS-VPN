package com.example.vpn.hub

import com.example.data.model.VlessProfile
import com.example.vpn.engine.RuntimeCapabilities
import com.example.vpn.subscription.SubscriptionSources

/**
 * The project's free config list: built every few hours from public GitHub lists by
 * `tools/aggregator` (workflow `free-configs.yml`), signed, and published on the `free-configs`
 * branch. The app reads it as a built-in subscription; GitHub's CDN mirrors (jsDelivr, Statically,
 * Githack) carry it where raw.githubusercontent.com is blocked.
 *
 * Every copy, from any address, is accepted only when `manifest.json` next to it carries a valid
 * signature from the key built into the app and names the list's SHA-256. A mirror that serves an old
 * or changed file is refused and the next one is tried.
 */
object FreeConfigList {
    const val NAME = "MAXIMUS Free"
    const val OWNER = "drfxai"
    const val REPO = "MAXIMUS-VPN"
    const val BRANCH = "free-configs"
    const val FILE = "free.txt"
    const val URL = "https://raw.githubusercontent.com/$OWNER/$REPO/$BRANCH/$FILE"

    /** True when this build can check the list's signature, so offering the list makes sense. */
    fun available(key: String = HubManifest.PUBLIC_KEY_DER_BASE64): Boolean = key.isNotBlank()

    /** True when [url] is the list, at GitHub or at one of its CDN copies. */
    fun isList(url: String): Boolean = SubscriptionSources.gitHubFile(url)?.let {
        it.owner.equals(OWNER, ignoreCase = true) && it.repo.equals(REPO, ignoreCase = true) &&
            it.ref == BRANCH && it.path == FILE
    } ?: false

    /**
     * Downloads the list at [url] with [get], together with the manifest and signature beside it, and
     * returns the list only when both checks pass; throws [HubManifest.Refused] otherwise.
     */
    fun download(url: String, get: (String) -> String, key: String = HubManifest.PUBLIC_KEY_DER_BASE64): String {
        val base = url.substringBeforeLast('/')
        val manifest = HubManifest.verify(
            get("$base/manifest.json").toByteArray(Charsets.UTF_8),
            get("$base/manifest.sig"),
            key
        )
        val list = get(url)
        if (!HubManifest.matches(manifest, FILE, list.toByteArray(Charsets.UTF_8))) {
            throw HubManifest.Refused("The configuration list does not match its signed manifest")
        }
        return list
    }

    /** The rule file for Iran intelligence, published next to the list and named in the same manifest. */
    const val INTEL_FILE = "intel.json"

    /**
     * The Iran intelligence rules published beside the list at [url], only when the signed manifest names
     * [INTEL_FILE] and its hash matches; null when the manifest names none. Throws [HubManifest.Refused]
     * when the signature or the hash does not match.
     */
    fun downloadIntel(url: String, get: (String) -> String, key: String = HubManifest.PUBLIC_KEY_DER_BASE64): String? =
        downloadSigned(url, INTEL_FILE, get, key)

    /** The probe-target manifest published beside the list (see ProbeManifest), named in the same signed manifest. */
    const val PROBES_FILE = "probes.json"

    /**
     * A file [name] published beside the list at [url], only when the signed manifest names it and its hash
     * matches; null when the manifest names none. Throws [HubManifest.Refused] when the signature or the hash
     * does not match.
     */
    fun downloadSigned(url: String, name: String, get: (String) -> String, key: String = HubManifest.PUBLIC_KEY_DER_BASE64): String? {
        val base = url.substringBeforeLast('/')
        val manifest = HubManifest.verify(get("$base/manifest.json").toByteArray(Charsets.UTF_8), get("$base/manifest.sig"), key)
        if (!manifest.sha256.containsKey(name)) return null
        val body = get("$base/$name")
        if (!HubManifest.matches(manifest, name, body.toByteArray(Charsets.UTF_8))) {
            throw HubManifest.Refused("$name does not match its signed manifest")
        }
        return body
    }

    private val COUNTRY_PREFIX = Regex("^([A-Z]{2}) \u00B7 ")

    /** The country code the aggregator puts in front of a name ("DE · VLESS 12"), or null. */
    fun countryOf(name: String): String? = COUNTRY_PREFIX.find(name)?.groupValues?.get(1)

    /** The sites a server opened when the list was built, by the tag after its name ("DE · VLESS 12 · YT TG X"). */
    val SITE_TAGS = listOf("YT", "TG", "X")

    /** Which of [SITE_TAGS] the aggregator found the server reaching, from its name; empty when none is named. */
    fun sitesOf(name: String): Set<String> {
        val last = name.substringAfterLast(" \u00B7 ", "").trim()
        val words = last.split(' ').filter { it.isNotEmpty() }
        return if (words.isNotEmpty() && words.all { it in SITE_TAGS }) words.toSet() else emptySet()
    }

    /**
     * The most servers the app takes from the list. The published list is already limited to 30 servers
     * that carried a real request; this holds even when a list from elsewhere is longer, so hundreds of
     * servers can never be saved, tested and drawn at once.
     */
    const val MAX_CONFIGS = 30

    /** A config from the list, ready to save: the first [MAX_CONFIGS] usable ones, with their country recorded. */
    fun prepare(profiles: List<VlessProfile>): List<VlessProfile> =
        profiles.filter(::usable).take(MAX_CONFIGS).map { it.copy(countryCode = countryOf(it.name) ?: it.countryCode) }

    /**
     * Saved servers from an earlier list that the new list no longer has. Favourites and the server
     * in use stay, so a refresh never pulls the connection out from under the user.
     */
    fun stale(saved: List<VlessProfile>, fresh: List<VlessProfile>, keepId: String?): List<VlessProfile> {
        val current = fresh.map { it.effectiveFingerprint }.toSet()
        return saved.filter { it.effectiveFingerprint !in current && !it.isFavorite && it.id != keepId }
    }

    /**
     * Saved servers beyond [MAX_CONFIGS], for installs that already hold more from an earlier, longer
     * list. The fastest tested servers stay, then untested ones; favourites and the server in use are
     * never in the result.
     */
    fun surplus(saved: List<VlessProfile>, keepId: String?): List<VlessProfile> =
        saved.sortedWith(
            compareBy<VlessProfile>({ !(it.isFavorite || it.id == keepId) }, { it.lastLatencyMs == null }, { it.lastLatencyMs ?: Long.MAX_VALUE })
        ).drop(MAX_CONFIGS).filter { !it.isFavorite && it.id != keepId }

    /** A config from the list is kept only when it is encrypted, checks certificates and an engine here runs it. */
    fun usable(profile: VlessProfile): Boolean =
        ConfigValidationPipeline.securityProblem(profile) == null && RuntimeCapabilities.unsupportedReason(profile) == null
}
