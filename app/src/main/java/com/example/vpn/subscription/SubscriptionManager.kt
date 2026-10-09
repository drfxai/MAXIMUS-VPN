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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
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
    private val staggerMs: Long = SubscriptionFetcher.STAGGER_MS,
    /** The user's "Free configs" setting: while it is off the free list is never downloaded. */
    private val freeListEnabled: () -> Boolean = {
        runCatching { com.example.RayApplication.instance.settingsRepository.getSettings().freeConfigsEnabled }.getOrDefault(true)
    },
    /** The free configs that carried traffic here (never deleted by a refresh); null keeps none. */
    private val lastKnownGood: () -> com.example.vpn.hub.LastKnownGoodPool? = {
        runCatching { com.example.RayApplication.instance.lastKnownGood }.getOrNull()
    },
    /** The profiles a refresh must not take away: the one the VPN uses and the selected one. */
    private val inUseIds: () -> Set<String> = {
        setOfNotNull(
            com.example.vpn.VpnController.connectionState.value.activeProfile?.id,
            runCatching { com.example.RayApplication.instance.settingsRepository.getSettings().selectedProfileId }.getOrNull()
        )
    },
    /** The key the free list's signature is checked against (tests pass their own). */
    private val freeListKey: String = com.example.vpn.hub.HubManifest.PUBLIC_KEY_DER_BASE64,
    /** Free configs kept only because they were in use when the list dropped them, by id. */
    private val retainedStore: com.example.vpn.hub.RetainedFreeConfigs? = runCatching {
        com.example.RayApplication.instance.retainedFreeConfigs
    }.getOrNull(),
    /** Receives the Iran intelligence rules published with the free list, after their signature check. */
    private val intelSink: ((String) -> Unit)? = runCatching {
        com.example.RayApplication.instance.iranIntel.let { store -> { json: String -> store.replace(json) } }
    }.getOrNull(),
    /** The official subscriptions' signing key (empty: they are accepted unsigned, as before). */
    private val officialKey: String = OfficialSigning.PUBLIC_KEY,
    /** The highest signed sequence accepted per official subscription (anti-rollback). */
    private val officialSequences: OfficialSequences = runCatching {
        OfficialSequences.Prefs(com.example.RayApplication.instance.getSharedPreferences("official_sequences", android.content.Context.MODE_PRIVATE))
    }.getOrElse { OfficialSequences.InMemory() },
    /** Independent copies of the official subscriptions (base addresses). */
    private val officialMirrorBases: String = com.example.BuildConfig.OFFICIAL_MIRRORS
) {
    companion object {
        private const val MAX_SAFE_REDIRECTS = 5

        /** The sync result while the user has the free list turned off. */
        const val FREE_OFF = "Free configs are off in Settings"

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

        /**
         * A placeholder from the tunnel's FakeDNS pool (XrayConfigBuilder.applyFakeDns: 198.18.0.0/15,
         * fc00::/18). While the VPN runs the phone's DNS answers with these; they are not the server's
         * address, so the lookup falls through to DNS-over-HTTPS instead of failing as "restricted".
         */
        internal fun isFakeDnsAddress(address: java.net.InetAddress): Boolean {
            val raw = address.address
            return when (raw.size) {
                4 -> (raw[0].toInt() and 0xFF) == 198 && (raw[1].toInt() and 0xFF) in 18..19
                16 -> (raw[0].toInt() and 0xFF) == 0xFC && (raw[1].toInt() and 0xC0) == 0
                else -> false
            }
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
                system = { okhttp3.Dns.SYSTEM.lookup(it).filterNot(::isFakeDnsAddress) },
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
        val fromOfflineCopy: Boolean = false,
        /** Free list only: configs before the refresh, removed, retained (protected or in use) and kept. */
        val poolBefore: Int = 0,
        val removedCount: Int = 0,
        val retainedCount: Int = 0,
        val keptCount: Int = 0,
        val finishedAt: Long = System.currentTimeMillis()
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

        if (FreeConfigList.isList(subscription.url) && !freeListEnabled()) {
            XrayLogManager.i("SUBSCRIPTION", "Free configs are off in Settings; '${subscription.name}' was not downloaded.")
            return@withContext SyncResult(subscription.id, 0, 0, 0, false, FREE_OFF)
        }

        val isFree = FreeConfigList.isList(subscription.url)
        if (isFree) _freeProgress.value = FreeRefreshProgress(FreeRefreshProgress.Stage.DOWNLOADING)
        // A signed official subscription can come from any independent copy: the envelope verifies on its own.
        val signedOfficial = OfficialSigning.isOfficial(subscription.url) && OfficialSigning.enforced(officialKey)
        val officialId = OfficialSigning.idFor(subscription.url)
        val extraMirrors = if (signedOfficial) OfficialSubscriptions.mirrorsFor(subscription.url, officialMirrorBases) else emptyList()
        val candidates = SubscriptionSources.candidates(subscription.url, (subscription.mirrors + extraMirrors).distinct())
            .filter { isValidSubscriptionUrl(it) }
        val verifiedSequence = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val fetcher = SubscriptionFetcher(
            download = (download ?: ::httpDownload).let { get ->
                when {
                    // The free list is taken from any address only with a valid signature beside it.
                    FreeConfigList.isList(subscription.url) -> { url: String -> FreeConfigList.download(url, get, freeListKey) }
                    signedOfficial -> { url: String ->
                        OfficialSigning.download(url, get, officialId, officialSequences.last(officialId), officialKey)
                            .also { verifiedSequence[url] = it.sequence }.payload
                    }
                    else -> get
                }
            },
            count = { parse(it, subscription).validProfiles.size },
            staggerMs = staggerMs
        )
        try {
            when (val outcome = fetcher.fetch(candidates)) {
                is SubscriptionFetcher.Outcome.Fetched -> {
                    val importResult = parse(outcome.payload, subscription)
                    if (isFree) {
                        // Downloaded and its signature checked (the download refuses unsigned lists).
                        _freeProgress.value = FreeRefreshProgress(FreeRefreshProgress.Stage.SWAPPING,
                            candidates = importResult.configurationsFound, valid = importResult.validProfiles.size)
                        return@withContext swapFreeList(subscription, importResult, outcome)
                    }
                    if (signedOfficial) {
                        return@withContext replaceOfficial(subscription, importResult, outcome, officialId, verifiedSequence[outcome.url])
                    }
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
                    if (isFree) return@withContext keepFreeListAfterFailure(subscription, snapshot, outcome, reason, tried)
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
        } finally {
            if (isFree) _freeProgress.value = null
        }
    }

    /**
     * A verified official list replaces that subscription's servers in one transaction: servers the admin
     * removed go, new ones are added, the rest keep their test results. The server in use, the selected
     * one and favourites are never removed. The sequence is recorded only after the swap succeeded.
     */
    private suspend fun replaceOfficial(
        subscription: SubscriptionInfo,
        importResult: com.example.data.model.UniversalImportResult,
        outcome: SubscriptionFetcher.Outcome.Fetched,
        officialId: String,
        sequence: Long?
    ): SyncResult {
        val all = serverRepository.getAllProfilesOnce()
        val saved = serverRepository.getProfilesBySubscription(subscription.url).first()
        val plan = OfficialSwap.plan(saved, importResult.validProfiles, all, inUseIds())
        serverRepository.replaceAtomically(plan.delete.map { it.id }, plan.insert)
        sequence?.let { officialSequences.record(officialId, it) }
        snapshots?.runCatching { save(subscription.id, outcome.url, outcome.payload) }
        val viaMirror = outcome.url.takeIf { it != subscription.url }
        outcome.failures.forEach { (url, why) ->
            XrayLogManager.w("SUBSCRIPTION", "Source ${SecretRedactor.redact(url)} failed: ${SecretRedactor.redact(why)}")
        }
        subscriptionRepository.updateSyncStatus(subscription.id, nodeCountOf(subscription), error = null)
        XrayLogManager.i("SUBSCRIPTION", "Signed subscription '${subscription.name}' verified${viaMirror?.let { " via ${SecretRedactor.redact(it)}" }.orEmpty()}: " +
            "${plan.insert.size} added, ${plan.delete.size} removed, ${plan.kept} kept, ${plan.retained} kept because in use or favourite.")
        return SyncResult(subscription.id, importResult.configurationsFound, plan.insert.size, 0, true, viaMirror = viaMirror)
    }

    private val _freeProgress = kotlinx.coroutines.flow.MutableStateFlow<FreeRefreshProgress?>(null)
    /** Where a running free list refresh is; null when none runs. */
    val freeProgress: kotlinx.coroutines.flow.StateFlow<FreeRefreshProgress?> = _freeProgress

    /**
     * Every source of the free list failed. Configs already on the phone stay exactly as they are (nothing
     * is re-added from the offline copy, so deleted ones do not come back and the list never passes 30).
     * Only when the phone has no free configs at all is the offline copy restored, through the same capped,
     * atomic swap as a normal refresh.
     */
    private suspend fun keepFreeListAfterFailure(
        subscription: SubscriptionInfo,
        snapshot: SubscriptionSnapshots.Snapshot?,
        outcome: SubscriptionFetcher.Outcome.AllFailed,
        reason: String,
        tried: Int
    ): SyncResult {
        val why = if (tried > 1) "All $tried sources failed. First: $reason" else reason
        val saved = serverRepository.getAllProfilesOnce().count {
            it.sourceSubscription == subscription.url || it.subscriptionUrl == subscription.url
        }
        if (saved > 0 || snapshot == null) {
            // The screen shows "Refresh failed — existing verified configurations retained." with this reason.
            XrayLogManager.w("SUBSCRIPTION", "'${subscription.name}' refresh failed; existing verified configurations retained " +
                "($saved unchanged): ${SecretRedactor.redact(why)}")
            subscriptionRepository.updateSyncStatus(subscription.id, nodeCountOf(subscription), why)
            return SyncResult(subscription.id, 0, 0, 0, false, why, poolBefore = saved, keptCount = saved)
        }
        val restored = swapFreeList(subscription, parse(snapshot.payload, subscription),
            SubscriptionFetcher.Outcome.Fetched(snapshot.sourceUrl, snapshot.payload, 0, outcome.failures))
        val day = java.text.SimpleDateFormat("d MMM", java.util.Locale.US).format(java.util.Date(snapshot.savedAt))
        val errMsg = "Offline copy from $day in use. Every source failed ($reason)."
        subscriptionRepository.updateSyncStatus(subscription.id, nodeCountOf(subscription), errMsg)
        return restored.copy(isSuccess = false, errorMessage = errMsg, fromOfflineCopy = true, viaMirror = null)
    }

    /**
     * Replaces the free list with the verified new one in one database transaction (see
     * [com.example.vpn.hub.FreeListSwap]): configs the new list dropped are removed, except the
     * last-known-good pool's, favourites and the one in use, which stay as "retained". The list is
     * never cleared first, so the screen never shows zero servers and the VPN never loses its config.
     */
    private suspend fun swapFreeList(
        subscription: SubscriptionInfo,
        importResult: com.example.data.model.UniversalImportResult,
        outcome: SubscriptionFetcher.Outcome.Fetched
    ): SyncResult = freeListLock.withLock {
        val saved = serverRepository.getAllProfilesOnce().filter {
            it.sourceSubscription == subscription.url || it.subscriptionUrl == subscription.url
        }
        // The pool's configs and the ones in use (the VPN's and the selected one) are all protected.
        val plan = com.example.vpn.hub.FreeListSwap.plan(
            saved = saved,
            fresh = importResult.validProfiles,
            protectedIds = lastKnownGood()?.protectedIds().orEmpty() + inUseIds(),
            activeId = null,
            limit = FreeConfigList.MAX_CONFIGS
        )
        serverRepository.replaceAtomically(plan.delete.map { it.id }, plan.insert)
        retainedStore?.update(retained = plan.retained.map { it.id }, gone = plan.delete.map { it.id })
        runCatching {
            com.example.RayApplication.instance.freeConfigEvidence.forget(
                plan.delete.map { it.effectiveFingerprint }.filter { fp -> plan.kept.none { it.effectiveFingerprint == fp } })
        }
        snapshots?.runCatching { save(subscription.id, outcome.url, outcome.payload) }
        // Rules beside the list, accepted only when the same signed manifest names them.
        val sink = intelSink
        if (sink != null && outcome.configs > 0) {
            val get: (String) -> String = download ?: { url -> httpDownload(url) }
            try {
                FreeConfigList.downloadIntel(outcome.url, get, freeListKey)?.let { sink(it) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                XrayLogManager.w("SUBSCRIPTION", "Intelligence rules not taken: ${SecretRedactor.redact(e.message.orEmpty())}")
            }
        }
        val viaMirror = outcome.url.takeIf { it != subscription.url }
        outcome.failures.forEach { (url, why) ->
            XrayLogManager.w("SUBSCRIPTION", "Source ${SecretRedactor.redact(url)} failed: ${SecretRedactor.redact(why)}")
        }
        subscriptionRepository.updateSyncStatus(subscription.id, nodeCountOf(subscription), error = null)
        XrayLogManager.i(
            "SUBSCRIPTION",
            "Free list '${subscription.name}' replaced${viaMirror?.let { " via ${SecretRedactor.redact(it)}" }.orEmpty()}: " +
                "${importResult.configurationsFound} candidates, ${plan.insert.size} added, ${plan.delete.size} removed, " +
                "${plan.kept.size} kept, ${plan.retained.size} retained (last-known-good or in use); ${saved.size} before."
        )
        SyncResult(
            subscription.id, importResult.configurationsFound, plan.insert.size,
            importResult.validProfiles.size - plan.insert.size, true, viaMirror = viaMirror,
            poolBefore = saved.size, removedCount = plan.delete.size, retainedCount = plan.retained.size, keptCount = plan.kept.size
        )
    }

    /**
     * Deletes every free config and nothing else (VIP, imported, panel and private configs stay). The
     * one the VPN is using leaves the list too, but the running session keeps its own copy in memory and
     * is not interrupted. Test metadata for the deleted configs goes; diagnostic history stays.
     */
    suspend fun deleteAllFree(): Int = withContext(Dispatchers.IO) { freeListLock.withLock {
        val saved = serverRepository.getProfilesBySubscription(FreeConfigList.URL).first()
        val ids = saved.map { it.id }
        serverRepository.replaceAtomically(ids, emptyList())
        val active = com.example.vpn.VpnController.connectionState.value.activeProfile
        lastKnownGood()?.clear(keepFingerprint = null)
        retainedStore?.update(retained = emptyList(), gone = ids)
        runCatching { com.example.RayApplication.instance.freeConfigEvidence.forget(saved.map { it.effectiveFingerprint }) }
        val activeFree = active != null && saved.any { it.id == active.id }
        XrayLogManager.i("SUBSCRIPTION", "Deleted ${ids.size} free configs" +
            if (activeFree) "; the connected one keeps running until disconnect." else ".")
        ids.size
    } }

    /** Deletes one free config (the running session, if it uses it, is not interrupted). */
    suspend fun deleteFree(profile: com.example.data.model.VlessProfile) = withContext(Dispatchers.IO) { freeListLock.withLock {
        serverRepository.replaceAtomically(listOf(profile.id), emptyList())
        // The user removed it on purpose: it leaves the last-known-good pool too.
        lastKnownGood()?.dropDead({ it == profile.effectiveFingerprint }, activeFingerprint = null)
        runCatching { com.example.RayApplication.instance.freeConfigEvidence.forget(listOf(profile.effectiveFingerprint)) }
        retainedStore?.update(retained = emptyList(), gone = listOf(profile.id))
    } }

    /**
     * After the VPN disconnects: free configs kept only because they were in use when a refresh dropped
     * them are removed now, unless the last-known-good pool or a favourite still holds them.
     */
    suspend fun releaseRetained(): Int = withContext(Dispatchers.IO) { freeListLock.withLock {
        val store = retainedStore ?: return@withLock 0
        val keep = lastKnownGood()?.protectedIds().orEmpty() + inUseIds()
        val ids = store.ids().filter { it !in keep }
        val favourites = ids.mapNotNull { serverRepository.getProfileById(it) }.filter { it.isFavorite }.map { it.id }.toSet()
        val gone = ids.filter { it !in favourites }
        if (gone.isNotEmpty()) serverRepository.replaceAtomically(gone, emptyList())
        store.update(retained = emptyList(), gone = ids)
        if (gone.isNotEmpty()) XrayLogManager.i("SUBSCRIPTION", "Removed ${gone.size} retained free configs after disconnect.")
        gone.size
    } }

    /** One change to the free list at a time: a refresh can never re-add what a delete just removed mid-way. */
    private val freeListLock = kotlinx.coroutines.sync.Mutex()

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
                it.autoRefresh && it.url.isNotBlank() && !(FreeConfigList.isList(it.url) && !freeListEnabled()) &&
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

    /**
     * Turns the free list on or off. Off removes its saved servers (except [keepId], the server in use, so
     * the connection is never pulled away) and stops every download; on downloads it again.
     */
    suspend fun setFreeList(enabled: Boolean, keepId: String?): SyncResult? = withContext(Dispatchers.IO) {
        if (!enabled) {
            val saved = serverRepository.getProfilesBySubscription(FreeConfigList.URL).first()
            saved.filter { it.id != keepId }.forEach { serverRepository.delete(it.id) }
            XrayLogManager.i("SUBSCRIPTION", "Free configs turned off: ${saved.count { it.id != keepId }} free servers removed.")
            return@withContext null
        }
        val sub = subscriptionRepository.getSubscriptionByUrl(FreeConfigList.URL)
        if (sub != null) syncSubscription(sub) else addAndSyncSubscription(FreeConfigList.NAME, FreeConfigList.URL)
    }

    suspend fun deleteSubscriptionAndNodes(subscription: SubscriptionInfo) {
        serverRepository.deleteBySubscription(subscription.url)
        subscriptionRepository.delete(subscription.id)
        snapshots?.delete(subscription.id)
        XrayLogManager.i("SUBSCRIPTION", "Deleted subscription '${subscription.name}' and its associated nodes.")
    }
}
