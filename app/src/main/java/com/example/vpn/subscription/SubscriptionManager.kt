package com.example.vpn.subscription

import com.example.core.SecretRedactor
import com.example.data.model.SubscriptionInfo
import com.example.data.repository.ServerRepository
import com.example.data.repository.SubscriptionRepository
import com.example.vpn.engine.UniversalImportEngine
import com.example.vpn.hub.FreeConfigList
import com.example.vpn.routing.RoutingEngine
import com.example.xray.XrayLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.URI
import java.util.concurrent.TimeUnit

class SubscriptionManager(
    private val subscriptionRepository: SubscriptionRepository,
    private val serverRepository: ServerRepository,
    /** Last good copy of each subscription; null keeps none. */
    private val snapshots: SubscriptionSnapshots? = null,
    /** Replaces the HTTP download (tests and the censorship simulator). */
    private val download: ((String) -> String)? = null,
    private val staggerMs: Long = SubscriptionFetcher.STAGGER_MS
) {
    companion object {
        private const val MAX_SAFE_REDIRECTS = 5

        internal fun validateRedirectTarget(base: HttpUrl, location: String): HttpUrl {
            val target = base.resolve(location)
                ?: throw SecurityException("SSRF blocked: Invalid subscription redirect target")
            if (!isValidSubscriptionUrl(target.toString())) {
                throw SecurityException("SSRF blocked: Subscription redirect target is restricted")
            }
            return target
        }

        fun isValidSubscriptionUrl(url: String): Boolean {
            val trimmed = url.trim()
            return try {
                val uri = URI(trimmed)
                val scheme = uri.scheme?.lowercase() ?: return false
                // Strict HTTPS only
                if (scheme != "https") return false
                val host = uri.host ?: return false
                if (isBlockedHost(host)) return false
                // Literal addresses are checked here; names are checked on every lookup by the client's
                // DNS (see resilientDns), not resolved here: a censor's poisoned answer must not make
                // a valid subscription look invalid.
                val literal = RoutingEngine.parseIpv4(host) != null || host.contains(':')
                if (literal && isRestrictedIp(java.net.InetAddress.getByName(host.trim('[', ']')))) return false
                true
            } catch (_: Exception) {
                false
            }
        }

        fun isRestrictedIp(inetAddress: java.net.InetAddress): Boolean {
            if (inetAddress.isLoopbackAddress) return true
            if (inetAddress.isAnyLocalAddress) return true
            if (inetAddress.isLinkLocalAddress) return true
            if (inetAddress.isSiteLocalAddress) return true
            if (inetAddress.isMulticastAddress) return true

            val raw = inetAddress.address
            if (raw.size == 4) {
                val b0 = raw[0].toInt() and 0xFF
                val b1 = raw[1].toInt() and 0xFF
                // 100.64.0.0/10 Carrier Grade NAT
                if (b0 == 100 && (b1 in 64..127)) return true
                // 169.254.0.0/16 Link-local / Cloud metadata (169.254.169.254)
                if (b0 == 169 && b1 == 254) return true
                // 198.18.0.0/15
                if (b0 == 198 && (b1 in 18..19)) return true
                // 0.0.0.0/8
                if (b0 == 0) return true
                // 192.0.0.0/24 IETF protocol assignments
                if (b0 == 192 && b1 == 0 && (raw[2].toInt() and 0xFF) == 0) return true
                // 240.0.0.0/4 reserved, including 255.255.255.255
                if (b0 >= 240) return true
            } else if (raw.size == 16) {
                val b0 = raw[0].toInt() and 0xFF
                // Unique Local Address fc00::/7 (fc00... or fd00...)
                if ((b0 and 0xFE) == 0xFC) return true
                // Link-local fe80::/10
                if (b0 == 0xFE && ((raw[1].toInt() and 0xC0) == 0x80)) return true
                // IPv4-mapped IPv6 ::ffff:a.b.c.d
                if (raw.sliceArray(0..9).all { it == 0.toByte() } && raw[10] == 0xFF.toByte() && raw[11] == 0xFF.toByte()) {
                    val v4 = raw.sliceArray(12..15)
                    val v4Addr = java.net.InetAddress.getByAddress(v4)
                    return isRestrictedIp(v4Addr)
                }
                // NAT64 64:ff9b::/96 carries an IPv4 address in its last four bytes
                val nat64 = byteArrayOf(0, 0x64, 0xff.toByte(), 0x9b.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)
                if (raw.sliceArray(0..11).contentEquals(nat64)) {
                    return isRestrictedIp(java.net.InetAddress.getByAddress(raw.sliceArray(12..15)))
                }
                // 6to4 2002::/16 carries an IPv4 address in bytes 2-5
                if (b0 == 0x20 && raw[1] == 0x02.toByte()) {
                    return isRestrictedIp(java.net.InetAddress.getByAddress(raw.sliceArray(2..5)))
                }
            }
            return false
        }

        fun isBlockedHost(host: String): Boolean {
            val lower = host.trim().lowercase()
            if (lower == "localhost" || lower == "127.0.0.1" || lower == "::1" || lower == "0.0.0.0" || lower == "::") return true
            if (lower == "169.254.169.254" || lower.startsWith("169.254.")) return true // AWS/Cloud metadata & link-local
            if (lower == "100.100.100.200") return true // Alibaba cloud metadata
            if (lower == "metadata.google.internal") return true // GCP metadata

            val parsedIp = RoutingEngine.parseIpv4(lower)
            if (parsedIp != null && (RoutingEngine.isLanAddress(parsedIp) || RoutingEngine.isLoopbackOrBroadcast(parsedIp))) {
                return true
            }
            return false
        }
    }

    /**
     * The network's DNS first; when it fails, is silent or answers with a block-page address, the
     * DoH resolvers (EndpointResolver). Every answer is checked against restricted ranges.
     */
    private val resilientDns = object : okhttp3.Dns {
        override fun lookup(hostname: String): List<java.net.InetAddress> {
            if (isBlockedHost(hostname)) {
                throw SecurityException("SSRF blocked: Hostname '$hostname' is in restricted host list.")
            }
            val result = com.example.vpn.EndpointResolver.resolve(
                hostname,
                system = { okhttp3.Dns.SYSTEM.lookup(it) },
                open = { it.openConnection() as java.net.HttpURLConnection }
            )
            val address = java.net.InetAddress.getByName(result.address)
            if (isRestrictedIp(address)) {
                throw SecurityException("DNS for '$hostname' answered a restricted address ($address); blocked or rebinding")
            }
            if (result.viaDoh) XrayLogManager.i("SUBSCRIPTION", "Resolved $hostname through DoH (network DNS blocked).")
            return listOf(address)
        }
    }

    private val safeRedirectInterceptor = Interceptor { chain ->
        var request = chain.request()
        var response = chain.proceed(request)
        var redirects = 0
        while (response.isRedirect) {
            if (redirects++ >= MAX_SAFE_REDIRECTS) {
                response.close()
                throw java.io.IOException("Subscription redirected too many times")
            }
            val location = response.header("Location") ?: return@Interceptor response
            val target = try {
                validateRedirectTarget(response.request.url, location)
            } catch (e: Exception) {
                response.close()
                throw e
            }
            request = response.request.newBuilder().url(target).build()
            response.close()
            response = chain.proceed(request)
        }
        response
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .dns(resilientDns)
        // A system HTTP proxy would resolve the host itself and skip the address checks above.
        .proxy(java.net.Proxy.NO_PROXY)
        // Validate redirect destinations before opening their sockets.
        .addInterceptor(safeRedirectInterceptor)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    data class SyncResult(
        val subscriptionId: String,
        val totalFound: Int,
        val addedCount: Int,
        val duplicateCount: Int,
        val isSuccess: Boolean,
        val errorMessage: String? = null,
        /** The address that answered, when it was a mirror rather than the subscription's own. */
        val viaMirror: String? = null,
        /** True when every source failed and the servers came from the offline copy. */
        val fromOfflineCopy: Boolean = false
    )

    /** Downloads one address through the SSRF-safe client, resolving through DoH when DNS is blocked. */
    private fun httpDownload(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Maximus-VPN/1.0 (Android; Linux)")
            .header("Accept", "*/*")
            .build()
        client.newCall(request).execute().use { res ->
            if (!res.isSuccessful) throw java.io.IOException("HTTP ${res.code}")
            val body = res.body ?: throw java.io.IOException("empty response")
            // Protect mobile users with strict 5MB response limit
            val maxBytes = 5 * 1024 * 1024
            val inputStream = body.byteStream()
            val buffer = ByteArray(8192)
            val out = ByteArrayOutputStream()
            var total = 0
            while (true) {
                val read = inputStream.read(buffer)
                if (read == -1) break
                total += read
                if (total > maxBytes) throw java.io.IOException("Subscription payload exceeded safety limit (5MB).")
                out.write(buffer, 0, read)
            }
            return out.toString(Charsets.UTF_8.name())
        }
    }

    private fun parse(payload: String, subscription: SubscriptionInfo) = UniversalImportEngine.importText(
        rawText = payload,
        sourceFileName = subscription.name,
        sourceSubscriptionUrl = subscription.url
    ).let { result ->
        // Public configs are hostile input: from the free list only what is safe and runnable here is kept.
        if (FreeConfigList.isList(subscription.url)) result.copy(validProfiles = FreeConfigList.prepare(result.validProfiles))
        else result
    }

    /**
     * Synchronizes a subscription from its address, its mirrors or the CDN copies of a GitHub file,
     * whichever answers first with configurations. When all of them fail, the servers of the last good
     * copy are kept (and restored if they were deleted).
     */
    suspend fun syncSubscription(subscription: SubscriptionInfo): SyncResult = withContext(Dispatchers.IO) {
        val sanitizedUrl = SecretRedactor.redact(subscription.url)
        XrayLogManager.i("SUBSCRIPTION", "Syncing subscription '${subscription.name}' from $sanitizedUrl...")

        if (!isValidSubscriptionUrl(subscription.url)) {
            val errMsg = "Invalid or restricted subscription URL scheme/host."
            XrayLogManager.e("SUBSCRIPTION", "Sync failed for '${subscription.name}': $errMsg")
            subscriptionRepository.updateSyncStatus(subscription.id, subscription.nodeCount, errMsg)
            return@withContext SyncResult(subscription.id, 0, 0, 0, false, errMsg)
        }

        val candidates = SubscriptionSources.candidates(subscription.url, subscription.mirrors)
            .filter { isValidSubscriptionUrl(it) }
        val fetcher = SubscriptionFetcher(
            download = (download ?: ::httpDownload).let { get ->
                // The free list is taken from any address only with a valid signature beside it.
                if (FreeConfigList.isList(subscription.url)) { url: String -> FreeConfigList.download(url, get) } else get
            },
            count = { parse(it, subscription).validProfiles.size },
            staggerMs = staggerMs
        )
        try {
            when (val outcome = fetcher.fetch(candidates)) {
                is SubscriptionFetcher.Outcome.Fetched -> {
                    val importResult = parse(outcome.payload, subscription)
                    if (FreeConfigList.isList(subscription.url)) pruneFreeList(subscription, importResult.validProfiles)
                    val (inserted, duplicates) = serverRepository.insertAllWithDeduplication(importResult.validProfiles)
                    snapshots?.runCatching { save(subscription.id, outcome.url, outcome.payload) }
                    val viaMirror = outcome.url.takeIf { it != subscription.url }
                    outcome.failures.forEach { (url, why) ->
                        XrayLogManager.w("SUBSCRIPTION", "Source ${SecretRedactor.redact(url)} failed: ${SecretRedactor.redact(why)}")
                    }
                    subscriptionRepository.updateSyncStatus(subscription.id, nodeCountOf(subscription), error = null)
                    XrayLogManager.i(
                        "SUBSCRIPTION",
                        "Subscription '${subscription.name}' sync complete${viaMirror?.let { " via ${SecretRedactor.redact(it)}" }.orEmpty()}: " +
                            "${importResult.configurationsFound} found, ${inserted.size} added, ${duplicates.size} duplicates."
                    )
                    SyncResult(subscription.id, importResult.configurationsFound, inserted.size, duplicates.size, true, viaMirror = viaMirror)
                }
                is SubscriptionFetcher.Outcome.AllFailed -> {
                    val reason = outcome.failures.firstOrNull()?.second ?: "no source to try"
                    val tried = outcome.failures.count { it.first.startsWith("http") }
                    val snapshot = snapshots?.load(subscription.id)
                    if (snapshot == null) {
                        val errMsg = if (tried > 1) "All $tried sources failed. First: $reason" else reason
                        XrayLogManager.e("SUBSCRIPTION", "Sync failed for '${subscription.name}': ${SecretRedactor.redact(errMsg)}")
                        subscriptionRepository.updateSyncStatus(subscription.id, subscription.nodeCount, errMsg)
                        return@withContext SyncResult(subscription.id, 0, 0, 0, false, errMsg)
                    }
                    val importResult = parse(snapshot.payload, subscription)
                    val (inserted, duplicates) = serverRepository.insertAllWithDeduplication(importResult.validProfiles)
                    val day = java.text.SimpleDateFormat("d MMM", java.util.Locale.US).format(java.util.Date(snapshot.savedAt))
                    val errMsg = "Offline copy from $day in use. Every source failed ($reason)."
                    XrayLogManager.w("SUBSCRIPTION", "'${subscription.name}': ${SecretRedactor.redact(errMsg)} ${inserted.size} servers restored.")
                    subscriptionRepository.updateSyncStatus(subscription.id, nodeCountOf(subscription), errMsg)
                    SyncResult(subscription.id, importResult.configurationsFound, inserted.size, duplicates.size, false, errMsg, fromOfflineCopy = true)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val errMsg = e.localizedMessage ?: "Network connection failure"
            XrayLogManager.e("SUBSCRIPTION", "Error syncing '${subscription.name}': $errMsg")
            subscriptionRepository.updateSyncStatus(subscription.id, subscription.nodeCount, errMsg)
            SyncResult(subscription.id, 0, 0, 0, false, errMsg)
        }
    }

    /** The free list is replaced on every update; servers it dropped are removed (see [FreeConfigList.stale]). */
    private suspend fun pruneFreeList(subscription: SubscriptionInfo, fresh: List<com.example.data.model.VlessProfile>) {
        val saved = serverRepository.getAllProfilesOnce().filter {
            it.sourceSubscription == subscription.url || it.subscriptionUrl == subscription.url
        }
        val keep = runCatching { com.example.RayApplication.instance.settingsRepository.getSettings().selectedProfileId }.getOrNull()
        FreeConfigList.stale(saved, fresh, keep).forEach { serverRepository.delete(it.id) }
    }

    private suspend fun nodeCountOf(subscription: SubscriptionInfo) = serverRepository.getAllProfilesOnce().count {
        it.sourceSubscription == subscription.url || it.subscriptionUrl == subscription.url
    }

    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Refreshes every auto-refresh subscription that is due (older than its interval, or failed last
     * time). Called once a connection is up, so a user with one working server gets fresh servers
     * through it even when the subscription's own address is blocked on their network.
     */
    suspend fun refreshDue(now: Long = System.currentTimeMillis()): List<SyncResult> {
        if (!refreshing.compareAndSet(false, true)) return emptyList()
        try {
            val due = subscriptionRepository.getAllOnce().filter {
                it.autoRefresh && it.url.isNotBlank() &&
                    (it.lastError != null || now - it.lastUpdated >= it.refreshIntervalMinutes * 60_000L)
            }
            return due.map { syncSubscription(it) }
        } finally {
            refreshing.set(false)
        }
    }

    /**
     * Adds a new subscription and immediately triggers initial sync. [url] may hold several addresses
     * (by line, space or comma): the first is the subscription, the rest are its mirrors.
     */
    suspend fun addAndSyncSubscription(name: String, url: String): SyncResult {
        val (primary, mirrors) = SubscriptionSources.parseInput(url)
        if (!isValidSubscriptionUrl(primary)) {
            return SyncResult("", 0, 0, 0, false, "Invalid subscription URL")
        }
        val existing = subscriptionRepository.getSubscriptionByUrl(primary)
        val sub = existing?.copy(mirrors = (existing.mirrors + mirrors.filter { isValidSubscriptionUrl(it) }).distinct()) ?: SubscriptionInfo(
            name = name.ifBlank { "Subscription" },
            url = primary,
            autoRefresh = true,
            lastUpdated = 0L,
            nodeCount = 0,
            mirrors = mirrors.filter { isValidSubscriptionUrl(it) }
        )
        subscriptionRepository.insertOrUpdate(sub)
        return syncSubscription(sub)
    }

    suspend fun deleteSubscriptionAndNodes(subscription: SubscriptionInfo) {
        serverRepository.deleteBySubscription(subscription.url)
        subscriptionRepository.delete(subscription.id)
        snapshots?.delete(subscription.id)
        XrayLogManager.i("SUBSCRIPTION", "Deleted subscription '${subscription.name}' and its associated nodes.")
    }
}
