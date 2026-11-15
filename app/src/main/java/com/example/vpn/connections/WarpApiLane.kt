package com.example.vpn.connections

import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.AetherSettings
import org.json.JSONObject

/**
 * Aether's WARP account API lanes on Iranian mobile networks (Irancell, Hamrah-e-Aval).
 *
 * Field problem (Irancell, 2026-11-15): every WARP start failed with "WARP API registration failed (account
 * bootstrap or refresh timed out after 14s)" even with the ClientHello fragmented and the compatible TLS retry.
 * The separately built Aether executable (AGPL-3.0, scripts/connections/aether/maximus_api_lane.rs, adapted from
 * UAC SNI Spoofer's oob.rs/relay.rs) can race, for each account call, Aether's own call against an OOB/urgent-byte
 * ClientHello desync to api.cloudflareclient.com (TLS 1.2 without ALPN first, Cloudflare API addresses only) and,
 * when the user deployed one, their own Cloudflare Worker relay. Only the first finished TLS handshake sends the
 * request, so a registration is never sent twice. This object only decides when to switch that on and reads the
 * engine's fixed `MAXIMUS_AETHER_API_LANE:<label>` line; no protocol code lives in the app.
 */
object WarpApiLane {
    /** Extras key set by the session runner on Iranian mobile networks. */
    const val EXTRA = "apiOobLane"
    const val OOB_ENV = "AETHER_API_OOB"
    const val RELAY_ENV = "AETHER_API_RELAY"
    const val LINE_PREFIX = "MAXIMUS_AETHER_API_LANE:"

    private val LABEL = Regex("^[a-z0-9+/:@.\\-]{1,64}$")

    /**
     * Whether the runner switches the lanes on for this automatic Aether attempt: Irancell or Hamrah-e-Aval, and the
     * account calls go straight from the phone (no exit node, Tor or Psiphon carries them, and the user did not
     * force the Direct OOB bridge, which hands the calls to a proxy where the lanes cannot run).
     */
    fun applies(irancell: Boolean, mci: Boolean, options: AetherSettings): Boolean =
        (irancell || mci) && options.apiRoute != "oob" && options.exitNode.isBlank() &&
            options.tor == "off" && options.psiphon == "off"

    fun applies(irancell: Boolean, mci: Boolean, candidate: VlessProfile): Boolean =
        applies(irancell, mci, AetherSettings.read(ProfileExtras.read(candidate)))

    /** The candidate with the lanes switched on. Nothing else in it changes. */
    fun mark(candidate: VlessProfile): VlessProfile {
        val e = ProfileExtras.read(candidate)
        if (e.optBoolean(EXTRA)) return candidate
        return candidate.copy(extraSettings = JSONObject(e.toString()).put(EXTRA, true).toString())
    }

    fun enabled(extras: JSONObject): Boolean = extras.optBoolean(EXTRA)

    /**
     * Engine environment: the OOB lane when the runner switched it on, and the user's relay whenever one is set and
     * valid (the engine also checks it). A relay alone also turns the race on, on any network.
     */
    fun environment(extras: JSONObject, options: AetherSettings): Map<String, String> = buildMap {
        if (enabled(extras)) put(OOB_ENV, "1")
        val relay = options.apiRelay.trim()
        if (relay.isNotEmpty() && AetherSettings.validRelay(relay) && options.exitNode.isBlank() &&
            options.tor == "off" && options.psiphon == "off") put(RELAY_ENV, relay)
    }

    /** The lane the engine reported, from its fixed line only (labels never hold addresses or hosts). */
    fun lane(line: String): String? {
        val text = line.trim()
        if (!text.startsWith(LINE_PREFIX)) return null
        return text.removePrefix(LINE_PREFIX).takeIf { LABEL.matches(it) }
    }

    /** Log text for a reported lane. */
    fun describe(lane: String): String = when {
        lane == "none" -> "no account lane finished a TLS handshake (direct, OOB and relay all blocked)"
        lane == "relay-invalid" -> "the WARP API relay address was refused by the engine; relay lane off"
        lane == "direct" -> "account call went by Aether's own TLS (direct lane won)"
        lane.startsWith("oob:") -> "account call went by the OOB ClientHello lane " + lane.removePrefix("oob:")
        lane.startsWith("relay@") -> "account call went through your Cloudflare Worker relay (" + lane.removePrefix("relay@") + ")"
        else -> "account call lane: $lane"
    }

    /** One line for the session log when the runner switches the lanes on. */
    fun summary(options: AetherSettings): String {
        val relay = options.apiRelay.isNotBlank() && AetherSettings.validRelay(options.apiRelay)
        return "account API: Aether's own call raced against the OOB ClientHello lane (TLS 1.2 first, Cloudflare API addresses)" +
            (if (relay) " and your Worker relay" else "") + "; only the first finished handshake sends the request"
    }
}
