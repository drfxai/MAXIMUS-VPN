package com.example.vpn.connections

import android.content.Context

/**
 * Whether the app already knows that Cloudflare is filtered on the phone's current network (a fresh cf=0 reading, or
 * WARP's data plane held here after a gateway scan failed with Cloudflare filtered). Build 178 field report: Aether
 * picks ran 1-3 minute scans while this was known; the picker now says so before the user starts one, and the run
 * itself gets one short check ([ConnectionEngineRunner.CF_FILTERED_MANUAL_BUDGET_MS]). A pick still runs only Aether.
 */
object AetherCloudflareNotice {
    fun knownFiltered(context: Context, now: Long = System.currentTimeMillis()): Boolean = runCatching {
        val key = com.example.vpn.smart.NetworkIdentity.current(context)
        com.example.vpn.smart.SmartConnectPlan.cloudflareBlocked(
            com.example.vpn.smart.NetworkCapabilityDetector.last, key, now) ||
            WarpDataPlaneHold.blocked(BuiltinMethodMemory.network(), now) ||
            // Build 179 (N11): the same memory the run itself reads, so the picker never promises a check that is skipped.
            CloudflareFilterMemory.knownAt(key, now) != null
    }.getOrDefault(false)

    /** Picker rows the notice applies to: Aether itself and every WARP transport or nested Aether method. Pure. */
    fun appliesTo(method: BuiltinConnections.ConfigFreeMethod): Boolean =
        method != BuiltinConnections.ConfigFreeMethod.AETHER_LANTERN &&
            (method == BuiltinConnections.ConfigFreeMethod.AETHER_WARP || method.aetherTransport != null)
}
