package com.example.vpn.connections

import android.content.Context
import android.content.SharedPreferences
import com.example.vpn.sidecar.ShirokhorshidSidecar
import org.json.JSONArray

/** The Shir o Khorshid choices from "Connect without a config", kept on the phone only. */
object ShirokhorshidSettings {
    const val PREFS = "shirokhorshid_settings"
    private const val MODE = "mode"
    private const val REGION = "region"
    private const val CDN = "cdn"
    private const val EDGES = "edges"
    private const val AVOID_IR = "avoidIranConduits"

    fun load(context: Context): ShirokhorshidSidecar.Options =
        read(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    fun save(context: Context, options: ShirokhorshidSidecar.Options) =
        write(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE), options)

    /** Invalid stored values fall back to the defaults instead of failing a connect. */
    fun read(prefs: SharedPreferences): ShirokhorshidSidecar.Options = runCatching {
        val edges = runCatching { JSONArray(prefs.getString(EDGES, "[]") ?: "[]") }.getOrDefault(JSONArray())
        ShirokhorshidSidecar.Options(
            mode = ShirokhorshidSidecar.Mode.of(prefs.getString(MODE, null)),
            region = prefs.getString(REGION, "").orEmpty().trim().uppercase(),
            cdnFronting = prefs.getBoolean(CDN, true),
            edges = (0 until edges.length()).map { edges.optString(it) }.filter { ShirokhorshidSidecar.validEdge(it) }
                .distinct().take(ShirokhorshidSidecar.MAX_EDGES),
            avoidIranConduits = prefs.getBoolean(AVOID_IR, true)
        ).let { if (it.problem() == null) it else it.copy(region = "") }
    }.getOrDefault(ShirokhorshidSidecar.Options())

    fun write(prefs: SharedPreferences, options: ShirokhorshidSidecar.Options) {
        prefs.edit()
            .putString(MODE, options.mode.key)
            .putString(REGION, options.region.trim().uppercase())
            .putBoolean(CDN, options.cdnFronting)
            .putString(EDGES, JSONArray(options.edges.filter { ShirokhorshidSidecar.validEdge(it) }.distinct()
                .take(ShirokhorshidSidecar.MAX_EDGES)).toString())
            .putBoolean(AVOID_IR, options.avoidIranConduits)
            .apply()
    }
}
