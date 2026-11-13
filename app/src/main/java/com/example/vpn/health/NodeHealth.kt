package com.example.vpn.health

import com.example.data.model.ServerTestStatus
import com.example.data.model.VlessProfile

/** Pure rules for "Ping all": how to batch the real-delay probes and which nodes may be cleaned up. */
object NodeHealth {
    /** RealDelayProbe runs up to five configs at a time; one batch per round keeps the phone responsive. */
    const val BATCH = 5

    fun batches(profiles: List<VlessProfile>, size: Int = BATCH): List<List<VlessProfile>> =
        profiles.chunked(size.coerceIn(1, 10))

    /**
     * Nodes the last "Ping all" proved dead here: a real request was tried and carried nothing (or the config
     * is invalid). Only nodes that came from a subscription or the free list, never a favourite, the selected
     * node, or a config the user added by hand; a node that was not measured (VPN on) is never counted.
     */
    fun unreachable(profiles: List<VlessProfile>, results: Map<String, ServerTestStatus>, selectedId: String?): List<VlessProfile> =
        profiles.filter { p ->
            val status = results[p.id]
            (status is ServerTestStatus.Unavailable || status is ServerTestStatus.InvalidConfig) &&
                !p.isFavorite && p.id != selectedId &&
                (!p.subscriptionUrl.isNullOrBlank() || !p.sourceSubscription.isNullOrBlank())
        }
}
