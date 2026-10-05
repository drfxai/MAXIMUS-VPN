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

    private val COUNTRY_PREFIX = Regex("^([A-Z]{2}) \u00B7 ")

    /** The country code the aggregator puts in front of a name ("DE · VLESS 12"), or null. */
    fun countryOf(name: String): String? = COUNTRY_PREFIX.find(name)?.groupValues?.get(1)

    /** A config from the list, ready to save: only usable ones, with their country recorded. */
    fun prepare(profiles: List<VlessProfile>): List<VlessProfile> =
        profiles.filter(::usable).map { it.copy(countryCode = countryOf(it.name) ?: it.countryCode) }

    /**
     * Saved servers from an earlier list that the new list no longer has. Favourites and the server
     * in use stay, so a refresh never pulls the connection out from under the user.
     */
    fun stale(saved: List<VlessProfile>, fresh: List<VlessProfile>, keepId: String?): List<VlessProfile> {
        val current = fresh.map { it.effectiveFingerprint }.toSet()
        return saved.filter { it.effectiveFingerprint !in current && !it.isFavorite && it.id != keepId }
    }

    /** A config from the list is kept only when it is encrypted, checks certificates and an engine here runs it. */
    fun usable(profile: VlessProfile): Boolean =
        ConfigValidationPipeline.securityProblem(profile) == null && RuntimeCapabilities.unsupportedReason(profile) == null
}
