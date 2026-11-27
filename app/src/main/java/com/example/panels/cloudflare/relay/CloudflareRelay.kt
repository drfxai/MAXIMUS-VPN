package com.example.panels.cloudflare.relay

import com.example.data.model.VlessProfile
import com.example.panels.cloudflare.TokenTemplate
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlin.random.Random

/**
 * Build 181 Phase 7: "Connect Cloudflare account". The user's own free relay: a small Worker (VLESS over WebSocket,
 * vendored at assets/cf-relay/worker.js, SHA-256 pinned below) deployed on the user's own Cloudflare account with a
 * random name and a random id and path per user. Everything here is pure, so it is unit-tested on the JVM; the HTTP
 * calls are in [CloudflareRelayApi], the storage in [CloudflareRelayStore], the flow in [CloudflareRelayManager].
 *
 * Token: a user API token made from Cloudflare's own template link ([tokenUrl]); the user taps Create on Cloudflare's
 * page. Permission keys verified against developers.cloudflare.com/fundamentals/api/how-to/account-owned-token-template
 * (user-token links take short keys only; unknown keys are dropped silently, cloudflare-docs issue #29825: workers_kv /
 * zone_dns were wrong, workers_kv_storage / dns work). Least privilege: Workers Scripts edit (upload, enable workers.dev,
 * delete), Account settings read (the account and its name), Account analytics read (today's request count for the free
 * quota). Only with "I have a domain": Zone read (find the zone) and Workers Routes edit (attach the custom domain).
 */
object CloudflareRelay {
    // ---- the key -------------------------------------------------------------------------------------------------

    val RELAY_PERMISSIONS = listOf(
        TokenTemplate.Permission("workers_scripts", "edit", "Workers Scripts · Edit"),
        TokenTemplate.Permission("account_settings", "read", "Account settings · Read"),
        TokenTemplate.Permission("account_analytics", "read", "Account analytics · Read")
    )
    val DOMAIN_PERMISSIONS = RELAY_PERMISSIONS + listOf(
        TokenTemplate.Permission("zone", "read", "Zone · Read"),
        TokenTemplate.Permission("workers_routes", "edit", "Workers Routes · Edit")
    )
    const val TOKEN_NAME = "Maximus Relay"
    const val DASHBOARD_URL = "https://dash.cloudflare.com/"

    /** Cloudflare's Create token page with the relay's permissions filled in ([withDomain]: also the custom-domain ones). */
    fun tokenUrl(withDomain: Boolean): String =
        TokenTemplate.url(if (withDomain) DOMAIN_PERMISSIONS else RELAY_PERMISSIONS, TOKEN_NAME)

    enum class TokenKind { USER, LEGACY, ACCOUNT, GLOBAL_KEY, NONE }

    private val USER_TOKEN = Regex("^cfut_[A-Za-z0-9]{40}[0-9a-fA-F]{8}$")
    private val ACCOUNT_TOKEN = Regex("^cfat_[A-Za-z0-9]{40}[0-9a-fA-F]{8}$")
    private val GLOBAL_KEY = Regex("^(cfk_[A-Za-z0-9]{40}[0-9a-fA-F]{8}|[0-9a-f]{37,45})$")
    private val LEGACY_TOKEN = Regex("^[A-Za-z0-9_-]{40}$")

    /**
     * What [text] is: a user API token in Cloudflare's scannable format (cfut_ + 40 + 8-hex checksum, 2026+) or the
     * older 40-character one; an account token or the Global API Key are recognised so the app can refuse them (the
     * global key can do everything; account tokens are not what the template link makes). Pure.
     */
    fun tokenKind(text: String?): TokenKind {
        val t = text?.trim().orEmpty()
        return when {
            USER_TOKEN.matches(t) -> TokenKind.USER
            ACCOUNT_TOKEN.matches(t) -> TokenKind.ACCOUNT
            GLOBAL_KEY.matches(t) && !LEGACY_TOKEN.matches(t) -> TokenKind.GLOBAL_KEY
            LEGACY_TOKEN.matches(t) -> TokenKind.LEGACY
            else -> TokenKind.NONE
        }
    }

    fun usableToken(text: String?): Boolean = tokenKind(text).let { it == TokenKind.USER || it == TokenKind.LEGACY }

    /**
     * Whether the screen may look at the clipboard now: only when the app came back to the foreground while step 3 is
     * waiting for a key (never on first open, never while active). Pure.
     */
    fun mayReadClipboard(phase: Phase, resumedFromBackground: Boolean): Boolean =
        resumedFromBackground && (phase == Phase.KEY_PAGE || phase == Phase.FAILED)

    // ---- the Worker ----------------------------------------------------------------------------------------------

    const val WORKER_ASSET = "cf-relay/worker.js"
    /** SHA-256 of assets/cf-relay/worker.js, reviewed. A change to the script must be reviewed and re-pinned here. */
    const val WORKER_SHA256 = "633aad4b90d6bb3bc5889240122318498e2adda09f802fbc9f863dc0ff0f6695"
    /** Pinned Workers compatibility date (not "today"), so the runtime the script was reviewed on stays the same. */
    const val COMPATIBILITY_DATE = "2026-11-01"

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The script text when [bytes] is exactly the pinned script; throws otherwise (never uploads an unreviewed file). */
    fun verifiedScript(bytes: ByteArray): String {
        check(sha256Hex(bytes) == WORKER_SHA256) { "The relay script does not match its pinned SHA-256" }
        return String(bytes, Charsets.UTF_8)
    }

    /** The upload metadata: ES module, pinned compatibility date, the per-user id and path as secret bindings. */
    fun metadataJson(secrets: Secrets): String = JSONObject()
        .put("main_module", "worker.js")
        .put("compatibility_date", COMPATIBILITY_DATE)
        .put("bindings", JSONArray()
            .put(JSONObject().put("type", "secret_text").put("name", "RELAY_UUID").put("text", secrets.uuid))
            .put(JSONObject().put("type", "secret_text").put("name", "RELAY_PATH").put("text", secrets.path)))
        .toString()

    // ---- names and secrets ---------------------------------------------------------------------------------------

    // Plain, everyday words: the name shows on the wire (SNI) and in the user's dashboard. Nothing that says VPN.
    private val FIRST = listOf("quiet", "amber", "silver", "gentle", "bright", "calm", "golden", "misty", "rapid", "sunny",
        "little", "hidden", "lucky", "brave", "clever", "fresh", "humble", "jolly", "mellow", "noble", "proud", "rosy",
        "swift", "tidy", "vivid", "warm", "wild", "witty", "young", "zesty", "cosy", "dusty")
    private val SECOND = listOf("river", "forest", "meadow", "harbor", "garden", "valley", "canyon", "island", "lake",
        "maple", "orchard", "pebble", "prairie", "reef", "ridge", "shore", "spring", "summit", "willow", "breeze", "cloud",
        "comet", "dune", "ember", "field", "glade", "grove", "hill", "lagoon", "marsh", "moon", "oasis")

    private val NAME_PATTERN = Regex("^[a-z]+-[a-z]+-[0-9]{4}$")

    /** "quiet-river-4821". [random] should be a secure source in the app. */
    fun randomName(random: Random): String =
        "${FIRST[random.nextInt(FIRST.size)]}-${SECOND[random.nextInt(SECOND.size)]}-${1000 + random.nextInt(9000)}"

    fun isGeneratedName(name: String): Boolean = NAME_PATTERN.matches(name)

    /** Cloudflare Worker script names: lowercase letters, digits and dashes, ≤ 63, no dash at either end. Pure. */
    fun validScriptName(name: String): Boolean =
        name.length in 1..63 && name.matches(Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$"))

    data class Secrets(val uuid: String, val path: String)

    private const val PATH_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    const val PATH_LENGTH = 24

    fun newSecrets(random: Random): Secrets {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte() // version 4
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte() // IETF variant
        val hex = bytes.joinToString("") { "%02x".format(it) }
        val uuid = "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
        val path = (1..PATH_LENGTH).map { PATH_ALPHABET[random.nextInt(PATH_ALPHABET.length)] }.joinToString("")
        return Secrets(UUID.fromString(uuid).toString(), path)
    }

    // ---- the relay -----------------------------------------------------------------------------------------------

    /**
     * A deployed relay. [subdomain] is the account's "<name>.workers.dev"; [customHost] a name on the user's own zone
     * when attached. The id and path are secrets: the record is stored encrypted ([CloudflareRelayStore]) and never logged.
     */
    data class Relay(
        val accountId: String,
        val accountName: String,
        val scriptName: String,
        val subdomain: String,
        val uuid: String,
        val path: String,
        val createdAt: Long,
        val customHost: String? = null,
        val zoneId: String? = null,
        val smartUse: Boolean = true,
        val withDomainKey: Boolean = false
    ) {
        val workersHost: String get() = "$scriptName.$subdomain"
        val host: String get() = customHost ?: workersHost
        override fun toString(): String = "Relay($scriptName)" // never the id or path
    }

    fun toJson(r: Relay): String = JSONObject()
        .put("accountId", r.accountId).put("accountName", r.accountName).put("scriptName", r.scriptName)
        .put("subdomain", r.subdomain).put("uuid", r.uuid).put("path", r.path).put("createdAt", r.createdAt)
        .put("customHost", r.customHost ?: "").put("zoneId", r.zoneId ?: "").put("smartUse", r.smartUse)
        .put("withDomainKey", r.withDomainKey)
        .toString()

    fun fromJson(text: String?): Relay? = runCatching {
        val o = JSONObject(text ?: return null)
        Relay(
            accountId = o.getString("accountId"), accountName = o.optString("accountName"),
            scriptName = o.getString("scriptName"), subdomain = o.getString("subdomain"),
            uuid = o.getString("uuid"), path = o.getString("path"), createdAt = o.optLong("createdAt"),
            customHost = o.optString("customHost").takeIf { it.isNotBlank() },
            zoneId = o.optString("zoneId").takeIf { it.isNotBlank() },
            smartUse = o.optBoolean("smartUse", true), withDomainKey = o.optBoolean("withDomainKey", false)
        ).takeIf { validScriptName(it.scriptName) && it.path.length >= 16 && runCatching { UUID.fromString(it.uuid) }.isSuccess }
    }.getOrNull()

    /**
     * The connection to [r]: VLESS over WebSocket with verified TLS (the workers.dev / custom-domain certificate is
     * Cloudflare's own, checked against [Relay.host]; no insecure flag, no pinned bypass), through [cleanIp] when the
     * clean-IP scanner found a Cloudflare edge that works on this network. Early data (ed=2048) saves one round trip.
     */
    fun profile(r: Relay, cleanIp: String?): VlessProfile = VlessProfile(
        name = TITLE,
        address = cleanIp?.takeIf { it.isNotBlank() } ?: r.host,
        port = 443,
        uuid = r.uuid,
        transport = "ws",
        security = "tls",
        sni = r.host,
        host = r.host,
        path = "/${r.path}?ed=2048",
        fingerprint = "chrome",
        alpn = "http/1.1",
        allowInsecure = false
    )

    const val TITLE = "Cloudflare relay"

    // ---- Cloudflare API addresses (pure, so the URLs are tested) ----------------------------------------------------

    const val API = "https://api.cloudflare.com/client/v4"
    private val ACCOUNT_ID = Regex("^[a-f0-9]{32}$")

    fun validAccountId(id: String): Boolean = ACCOUNT_ID.matches(id)

    fun verifyUrl(): String = "$API/user/tokens/verify"
    fun accountsUrl(): String = "$API/accounts?per_page=50"
    fun subdomainUrl(accountId: String): String = "$API/accounts/${requireAccount(accountId)}/workers/subdomain"
    fun scriptsUrl(accountId: String): String = "$API/accounts/${requireAccount(accountId)}/workers/scripts"
    fun scriptUrl(accountId: String, name: String): String {
        require(validScriptName(name)) { "Invalid Worker name" }
        return "${scriptsUrl(accountId)}/$name"
    }
    fun scriptSubdomainUrl(accountId: String, name: String): String = "${scriptUrl(accountId, name)}/subdomain"
    fun deleteUrl(accountId: String, name: String): String = "${scriptUrl(accountId, name)}?force=true"
    fun domainsUrl(accountId: String): String = "$API/accounts/${requireAccount(accountId)}/workers/domains"
    fun zonesUrl(): String = "$API/zones?per_page=50&status=active"
    fun graphqlUrl(): String = "$API/graphql"

    private fun requireAccount(id: String): String {
        require(validAccountId(id)) { "Invalid Cloudflare account id" }
        return id
    }

    /** Today's Workers requests on the whole account (the free plan's daily limit is per account). */
    fun analyticsQuery(accountId: String, dayUtc: String): String {
        requireAccount(accountId)
        require(dayUtc.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$")))
        val q = "query { viewer { accounts(filter: {accountTag: \"$accountId\"}) { " +
            "workersInvocationsAdaptive(limit: 10000, filter: {date: \"$dayUtc\"}) { sum { requests } } } } }"
        return JSONObject().put("query", q).toString()
    }

    /** The request count from an analytics answer, or null (no permission, error, or an unexpected shape). */
    fun requestsFrom(answer: String): Long? = runCatching {
        val o = JSONObject(answer)
        val errors = o.optJSONArray("errors")
        if (errors != null && errors.length() > 0) return null
        val accounts = o.getJSONObject("data").getJSONObject("viewer").getJSONArray("accounts")
        if (accounts.length() == 0) return null
        val rows = accounts.getJSONObject(0).getJSONArray("workersInvocationsAdaptive")
        (0 until rows.length()).sumOf { rows.getJSONObject(it).optJSONObject("sum")?.optLong("requests") ?: 0L }
    }.getOrNull()

    // ---- rotation ------------------------------------------------------------------------------------------------

    data class Rotation(val next: Relay, val removeAfter: String)

    /**
     * "Rotate address": a new name, id and path on the same account (and a new label on the same zone when a custom
     * domain is used); the old Worker is removed only after the new one is up. Pure.
     */
    fun rotate(old: Relay, random: Random, now: Long): Rotation {
        var name = randomName(random)
        while (name == old.scriptName) name = randomName(random)
        val secrets = newSecrets(random)
        val zone = old.customHost?.let { zoneOf(it) }
        return Rotation(
            old.copy(scriptName = name, uuid = secrets.uuid, path = secrets.path, createdAt = now,
                customHost = zone?.let { "${name}.$it" }),
            removeAfter = old.scriptName
        )
    }

    /** "a.b.example.com" → "b.example.com" (the part after the first label). */
    fun zoneOf(host: String): String? = host.substringAfter('.', "").takeIf { it.contains('.') }

    /** A new name on the user's zone: "<generated name>.<zone>". */
    fun customHost(name: String, zone: String): String = "$name.${zone.trim().lowercase().trimEnd('.')}"

    // ---- free quota ----------------------------------------------------------------------------------------------

    /** Workers Free: 100,000 requests a day per account, reset at 00:00 UTC (developers.cloudflare.com/workers/platform/limits). */
    const val FREE_DAILY_REQUESTS = 100_000L
    const val WARN_FRACTION = 0.8
    const val SMART_STOP_FRACTION = 0.95

    /** [estimate]: counted on this phone (no analytics permission), labelled as an estimate in the UI. */
    data class Quota(val used: Long, val day: String, val estimate: Boolean, val limit: Long = FREE_DAILY_REQUESTS) {
        val fraction: Double get() = if (limit <= 0) 0.0 else used.toDouble() / limit
        val percent: Int get() = (fraction * 100).toInt().coerceIn(0, 100)
    }

    fun utcDay(now: Long): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(now))

    /** A quota of another day counts as zero (it reset at 00:00 UTC). */
    fun current(q: Quota?, now: Long): Quota? = q?.takeIf { it.day == utcDay(now) }

    fun warn(q: Quota?, now: Long): Boolean = (current(q, now)?.fraction ?: 0.0) >= WARN_FRACTION

    /** Smart Connect leaves the relay out near the limit, so it does not end on a relay that stops mid-day. */
    fun smartAllowed(q: Quota?, now: Long): Boolean = (current(q, now)?.fraction ?: 0.0) < SMART_STOP_FRACTION

    // ---- the screen's state machine ------------------------------------------------------------------------------

    /** Steps of Panels → Cloudflare → Cloudflare relay. */
    enum class Phase { START, OPENED, KEY_PAGE, VERIFYING, FAILED, DEPLOYING, ACTIVE, ROTATING, REMOVING }

    /** The checklist of mockup 4: null = not checked yet. */
    data class Checklist(val valid: Boolean? = null, val account: Boolean? = null, val workers: Boolean? = null) {
        val failed: Boolean get() = valid == false || account == false || workers == false
        val passed: Boolean get() = valid == true && account == true && workers == true
    }

    sealed class Event {
        object OpenedCloudflare : Event()
        object OpenedKeyPage : Event()
        object KeyEntered : Event()
        data class Checked(val checklist: Checklist) : Event()
        object Deployed : Event()
        data class Failed(val checklist: Checklist, val message: String) : Event()
        object Retry : Event()
        object RotateStarted : Event()
        object RotateDone : Event()
        object RemoveStarted : Event()
        object Removed : Event()
        data class ActionFailed(val message: String) : Event()
        object Restore : Event() // a relay is already stored
    }

    data class State(val phase: Phase = Phase.START, val checklist: Checklist = Checklist(), val message: String? = null)

    /** Pure transition. Events that do not apply in a phase leave the state as it is. */
    fun reduce(s: State, e: Event): State = when (e) {
        Event.Restore -> State(Phase.ACTIVE)
        Event.OpenedCloudflare -> if (s.phase == Phase.START) s.copy(phase = Phase.OPENED) else s
        Event.OpenedKeyPage -> if (s.phase in setOf(Phase.START, Phase.OPENED, Phase.FAILED)) State(Phase.KEY_PAGE) else s
        Event.KeyEntered -> if (s.phase in setOf(Phase.START, Phase.OPENED, Phase.KEY_PAGE, Phase.FAILED)) State(Phase.VERIFYING) else s
        is Event.Checked -> if (s.phase == Phase.VERIFYING) {
            if (e.checklist.failed) State(Phase.FAILED, e.checklist) else s.copy(phase = Phase.DEPLOYING, checklist = e.checklist)
        } else s
        Event.Deployed -> if (s.phase == Phase.DEPLOYING || s.phase == Phase.VERIFYING) State(Phase.ACTIVE) else s
        is Event.Failed -> if (s.phase == Phase.VERIFYING || s.phase == Phase.DEPLOYING) State(Phase.FAILED, e.checklist, e.message) else s
        Event.Retry -> if (s.phase == Phase.FAILED) State(Phase.VERIFYING, Checklist()) else s
        Event.RotateStarted -> if (s.phase == Phase.ACTIVE) s.copy(phase = Phase.ROTATING, message = null) else s
        Event.RotateDone -> if (s.phase == Phase.ROTATING) State(Phase.ACTIVE) else s
        Event.RemoveStarted -> if (s.phase == Phase.ACTIVE) s.copy(phase = Phase.REMOVING, message = null) else s
        Event.Removed -> if (s.phase == Phase.REMOVING) State(Phase.START) else s
        is Event.ActionFailed -> if (s.phase == Phase.ROTATING || s.phase == Phase.REMOVING) State(Phase.ACTIVE, message = e.message) else s
    }

    /** The step the three-step card highlights (1–3) for [phase]. */
    fun step(phase: Phase): Int = when (phase) {
        Phase.START -> 1
        Phase.OPENED -> 2
        else -> 3
    }

    /**
     * The one-time sheet after the first verified connection: only when the user has no server of their own and no
     * relay yet, and it was never shown. Pure.
     */
    fun shouldPrompt(alreadyShown: Boolean, connectedVerified: Boolean, hasOwnServer: Boolean, hasRelay: Boolean): Boolean =
        !alreadyShown && connectedVerified && !hasOwnServer && !hasRelay
}
