package com.example.vpn.subscription

import java.net.URI

/**
 * Where a subscription can be fetched from. A subscription is never tied to one domain: the user can
 * paste mirrors next to the address, and a file hosted on GitHub is also reachable through the CDNs
 * that serve GitHub content (jsDelivr's four networks, Statically, Githack), so blocking
 * raw.githubusercontent.com alone does not cut users off.
 */
object SubscriptionSources {

    /** Splits what the user pasted (one or more addresses, by line, space or comma) into address and mirrors. */
    fun parseInput(text: String): Pair<String, List<String>> {
        val urls = text.split(Regex("[\\s,]+"))
            .map { it.trim() }
            .filter { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }
            .distinct()
        return (urls.firstOrNull() ?: text.trim()) to urls.drop(1)
    }

    /** The address first, then the user's mirrors, then the CDN copies of a GitHub-hosted file. */
    fun candidates(primary: String, mirrors: List<String> = emptyList()): List<String> =
        (listOf(primary) + mirrors + mirrors.flatMap { derivedMirrors(it) } + derivedMirrors(primary))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

    /** Other addresses that serve the same GitHub file, or an empty list for anything else. */
    fun derivedMirrors(url: String): List<String> {
        val file = gitHubFile(url) ?: return emptyList()
        val (owner, repo, ref, path) = file
        return listOf(
            "https://raw.githubusercontent.com/$owner/$repo/$ref/$path",
            "https://cdn.jsdelivr.net/gh/$owner/$repo@$ref/$path",
            "https://fastly.jsdelivr.net/gh/$owner/$repo@$ref/$path",
            "https://gcore.jsdelivr.net/gh/$owner/$repo@$ref/$path",
            "https://testingcf.jsdelivr.net/gh/$owner/$repo@$ref/$path",
            "https://cdn.statically.io/gh/$owner/$repo/$ref/$path",
            "https://raw.githack.com/$owner/$repo/$ref/$path"
        ).filter { !it.equals(url.trim(), ignoreCase = false) }
    }

    internal data class GitHubFile(val owner: String, val repo: String, val ref: String, val path: String)

    internal fun gitHubFile(url: String): GitHubFile? {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() != "https" || uri.rawQuery != null) return null
        val host = uri.host?.lowercase() ?: return null
        val parts = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }
        fun file(owner: String, repo: String, rest: List<String>): GitHubFile? {
            // raw.githubusercontent.com/<owner>/<repo>/refs/heads/<branch>/<path>
            val (ref, path) = if (rest.size >= 4 && rest[0] == "refs" && rest[1] in setOf("heads", "tags")) {
                rest[2] to rest.drop(3)
            } else if (rest.size >= 2) {
                rest[0] to rest.drop(1)
            } else return null
            return GitHubFile(owner, repo, ref, path.joinToString("/"))
        }
        return when {
            host == "raw.githubusercontent.com" && parts.size >= 4 -> file(parts[0], parts[1], parts.drop(2))
            host == "github.com" && parts.size >= 5 && parts[2] in setOf("raw", "blob") -> file(parts[0], parts[1], parts.drop(3))
            host.endsWith("jsdelivr.net") && parts.size >= 4 && parts[0] == "gh" && '@' in parts[2] -> {
                val repo = parts[2].substringBefore('@')
                val ref = parts[2].substringAfter('@')
                if (ref.isEmpty()) null else GitHubFile(parts[1], repo, ref, parts.drop(3).joinToString("/"))
            }
            host == "cdn.statically.io" && parts.size >= 5 && parts[0] == "gh" -> file(parts[1], parts[2], parts.drop(3))
            host == "raw.githack.com" && parts.size >= 4 -> file(parts[0], parts[1], parts.drop(2))
            else -> null
        }
    }
}
