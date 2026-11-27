package com.example.panels.cloudflare.relay

import android.content.Context
import com.example.vpn.connections.CloudflareRelayRuntime
import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/**
 * Build 181 Phase 7: the relay's lifecycle on the user's own Cloudflare account. Blocking; call from a background
 * dispatcher. Every step reports to [log] in plain words without the key, the id or the path.
 */
class CloudflareRelayManager(private val context: Context, private val random: Random = SecureRandom().asKotlinRandom()) {
    private val store = CloudflareRelayStore(context)

    sealed class Outcome {
        data class Active(val relay: CloudflareRelay.Relay, val reachable: Boolean) : Outcome()
        data class Failed(val checklist: CloudflareRelay.Checklist, val message: String) : Outcome()
    }

    fun current(): CloudflareRelay.Relay? = store.relay()

    private fun script(): String =
        CloudflareRelay.verifiedScript(context.assets.open(CloudflareRelay.WORKER_ASSET).use { it.readBytes() })

    /**
     * Step 3: checks the key (valid, account, Workers access), then deploys the relay with a random name, id and path,
     * turns on its workers.dev address, stores key and relay encrypted, and probes it once. Nothing is stored when a
     * check fails; a Worker uploaded before a later step failed is deleted again.
     */
    fun connect(token: String, log: (String) -> Unit): Outcome {
        val kind = CloudflareRelay.tokenKind(token)
        if (kind == CloudflareRelay.TokenKind.GLOBAL_KEY) return Outcome.Failed(CloudflareRelay.Checklist(valid = false),
            "This is the Global API Key, which can do everything on the account. Create a limited key with the button instead.")
        if (kind == CloudflareRelay.TokenKind.ACCOUNT) return Outcome.Failed(CloudflareRelay.Checklist(valid = false),
            "This is an account-owned token. Create the key with the button, which makes a user token with only the relay's permissions.")
        if (kind == CloudflareRelay.TokenKind.NONE) return Outcome.Failed(CloudflareRelay.Checklist(valid = false),
            "This does not look like a Cloudflare API key.")
        val api = CloudflareRelayApi(token)
        var check = CloudflareRelay.Checklist()
        try {
            log("Checking the key")
            if (!api.verify()) return Outcome.Failed(check.copy(valid = false), "The key is not valid or has expired.")
            check = check.copy(valid = true)
            val accounts = api.accounts()
            val account = accounts.firstOrNull()
                ?: return Outcome.Failed(check.copy(account = false), "The key cannot see a Cloudflare account.")
            check = check.copy(account = true)
            if (!api.canReadWorkers(account.id)) return Outcome.Failed(check.copy(workers = false), "This key is missing a permission.")
            val subdomain = try {
                api.workersSubdomain(account.id) { CloudflareRelay.randomName(random) }
            } catch (e: CloudflareRelayApi.ApiException) {
                if (e.notAllowed) return Outcome.Failed(check.copy(workers = false), "This key is missing a permission.") else throw e
            }
            val relay = deploy(api, account, subdomain, null, log)
                ?: return Outcome.Failed(check.copy(workers = false), "This key is missing a permission.")
            store.saveToken(token)
            store.saveRelay(relay)
            CloudflareRelayRuntime.relay = relay
            log("Checking the new relay")
            val reachable = runCatching { CloudflareRelayApi.probe(relay.host, relay.path, relay.uuid) }.getOrDefault(false)
            log(if (reachable) "Relay answers" else "Relay deployed; a new address can take a few minutes to reach every Cloudflare edge")
            return Outcome.Active(relay, reachable)
        } catch (e: CloudflareRelayApi.ApiException) {
            return Outcome.Failed(if (e.notAllowed) check.copy(workers = check.workers ?: false) else check, e.message ?: "Cloudflare refused the request.")
        } catch (e: Exception) {
            return Outcome.Failed(check, (e.message ?: "Could not reach Cloudflare.") + " Check the connection and try again.")
        }
    }

    /** Uploads + enables a new relay; null when Cloudflare refused the upload for lack of permission. */
    private fun deploy(api: CloudflareRelayApi, account: CloudflareRelayApi.Account, subdomain: String,
                       base: CloudflareRelay.Relay?, log: (String) -> Unit): CloudflareRelay.Relay? {
        val next = if (base == null) {
            val secrets = CloudflareRelay.newSecrets(random)
            CloudflareRelay.Relay(account.id, account.name, CloudflareRelay.randomName(random), subdomain,
                secrets.uuid, secrets.path, System.currentTimeMillis())
        } else CloudflareRelay.rotate(base, random, System.currentTimeMillis()).next
        log("Deploying relay ${next.scriptName}")
        try {
            api.upload(account.id, next.scriptName, script(), CloudflareRelay.Secrets(next.uuid, next.path))
        } catch (e: CloudflareRelayApi.ApiException) {
            if (e.notAllowed) return null else throw e
        }
        try {
            api.enableWorkersDev(account.id, next.scriptName)
            if (next.customHost != null && next.zoneId != null) {
                log("Attaching ${next.customHost}")
                api.attachDomain(account.id, next.customHost, next.zoneId, next.scriptName)
            }
        } catch (e: Exception) {
            runCatching { api.delete(account.id, next.scriptName) }
            throw e
        }
        return next
    }

    /** Rotate address: deploy under a new name (same account, same zone if any), then remove the old Worker. */
    fun rotate(log: (String) -> Unit): CloudflareRelay.Relay {
        val old = store.relay() ?: error("No relay to rotate")
        val api = CloudflareRelayApi(store.token())
        val account = CloudflareRelayApi.Account(old.accountId, old.accountName)
        val next = deploy(api, account, old.subdomain, old, log) ?: error("The key can no longer edit Workers. Create a new key.")
        store.saveRelay(next)
        CloudflareRelayRuntime.relay = next
        log("Removing the old relay")
        runCatching { api.delete(old.accountId, old.scriptName) }
            .onFailure { log("The old relay could not be removed now; delete ${old.scriptName} in the Cloudflare dashboard") }
        return next
    }

    /** Disconnect: deletes the Worker in Cloudflare, then the key and the relay from this phone. */
    fun disconnect(log: (String) -> Unit) {
        val relay = store.relay()
        if (relay != null) {
            log("Deleting the relay in Cloudflare")
            CloudflareRelayApi(store.token()).delete(relay.accountId, relay.scriptName)
        }
        store.clear()
        CloudflareRelayRuntime.relay = null
        CloudflareRelayRuntime.quota = null
    }

    /** The user's zones (only with the domain key), for "Use my domain". */
    fun zones(): List<CloudflareRelayApi.Zone> = CloudflareRelayApi(store.token()).zones()

    /** Puts the relay on "<name>.<zone>" too and makes that its address (often better in Iran than workers.dev). */
    fun useDomain(zone: CloudflareRelayApi.Zone, log: (String) -> Unit): CloudflareRelay.Relay {
        val relay = store.relay() ?: error("No relay")
        val host = CloudflareRelay.customHost(relay.scriptName, zone.name)
        log("Attaching $host")
        CloudflareRelayApi(store.token()).attachDomain(relay.accountId, host, zone.id, relay.scriptName)
        val next = relay.copy(customHost = host, zoneId = zone.id)
        store.saveRelay(next)
        CloudflareRelayRuntime.relay = next
        return next
    }

    fun setSmartUse(on: Boolean): CloudflareRelay.Relay? {
        val next = store.relay()?.copy(smartUse = on) ?: return null
        store.saveRelay(next)
        CloudflareRelayRuntime.relay = next
        return next
    }

    /** Today's requests from Workers analytics when the key allows it, else the phone's own count (an estimate). */
    fun refreshQuota(now: Long = System.currentTimeMillis()): CloudflareRelay.Quota? {
        val relay = store.relay() ?: return null
        val day = CloudflareRelay.utcDay(now)
        val q = CloudflareRelayApi(store.token()).requestsToday(relay.accountId, day)
            ?.let { CloudflareRelay.Quota(it, day, estimate = false) } ?: store.localToday(now)
        store.saveQuota(q)
        CloudflareRelayRuntime.quota = q
        return q
    }

    companion object {
        /** App start: the relay and the last quota into the VPN side, and the local counter hook. */
        fun load(context: Context) {
            val store = CloudflareRelayStore(context)
            CloudflareRelayRuntime.relay = store.relay()
            CloudflareRelayRuntime.quota = store.quota()
            CloudflareRelayRuntime.onSession = { store.countLocal() }
        }
    }
}
