package com.example.vpn.smart

import com.example.data.model.VlessProfile
import com.example.xray.RealDelayProbe

/** A fast TCP edge is only a candidate. Two real requests through the unchanged config verify a path. */
object CleanPathRace {
    fun select(
        profile: VlessProfile,
        addresses: List<String>,
        probe: (List<VlessProfile>) -> List<RealDelayProbe.Outcome>,
        cancelled: () -> Boolean = { false }
    ): ServerRace.Winner? {
        if (profile.allowInsecure || !profile.security.equals("tls", true)) return null
        val paths = addresses.distinct().take(12).map { RouteRecipe.pinEndpoint(profile, it) }
        // Bound each native batch, but do not let four stale cached addresses hide fresh candidates.
        for (batch in paths.chunked(4)) {
            if (cancelled()) return null
            val first = probe(batch)
            if (cancelled()) return null
            val candidates = batch.zip(first).mapNotNull { (path, result) ->
                (result as? RealDelayProbe.Outcome.Delay)?.let { path to it.latencyMs }
            }
            if (candidates.isEmpty()) continue
            val second = probe(candidates.map { it.first })
            if (cancelled()) return null
            val winner = candidates.zip(second).mapNotNull { (candidate, result) ->
                (result as? RealDelayProbe.Outcome.Delay)?.let {
                    ServerRace.Winner(candidate.first, profile, (candidate.second + it.latencyMs) / 2)
                }
            }.minByOrNull { it.latencyMs }
            if (winner != null) return winner
        }
        return null
    }
}
