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
        val paths = addresses.distinct().take(4).map { RouteRecipe.pinEndpoint(profile, it) }
        if (paths.isEmpty() || cancelled()) return null
        val first = probe(paths)
        if (cancelled()) return null
        val candidates = paths.zip(first).mapNotNull { (path, result) ->
            (result as? RealDelayProbe.Outcome.Delay)?.let { path to it.latencyMs }
        }
        if (candidates.isEmpty()) return null
        val second = probe(candidates.map { it.first })
        if (cancelled()) return null
        return candidates.zip(second).mapNotNull { (candidate, result) ->
            (result as? RealDelayProbe.Outcome.Delay)?.let {
                ServerRace.Winner(candidate.first, profile, (candidate.second + it.latencyMs) / 2)
            }
        }.minByOrNull { it.latencyMs }
    }
}
