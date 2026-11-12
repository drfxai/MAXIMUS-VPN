package com.example.update

import android.content.Context
import com.example.RayApplication
import com.example.panels.CleanIpOptimizer
import com.example.vpn.connectivity.EndpointScoringEngine
import com.example.vpn.smart.NetworkKey

/**
 * Retest clean edges for the selected origin only. Never call a TCP-only result "clean"
 * and never bind a previously measured edge to a different CDN hostname without another test.
 */
object CleanIpMaintenance {
    data class Summary(val tested: Int, val validated: Int, val network: String)

    suspend fun refresh(context: Context, onProgress: (Int, Int) -> Unit = { _, _ -> }): Summary {
        val app = RayApplication.instance
        val selected = app.settingsRepository.getSettings().selectedProfileId
        val profile = app.serverRepository.getAllProfilesOnce().firstOrNull { it.id == selected }
            ?: error("Select a CDN-compatible node in Nodes before updating clean IPs.")
        check(CleanIpOptimizer.canRevive(profile)) {
            "Selected node must use secure WebSocket or HTTPUpgrade via Cloudflare."
        }
        val network = NetworkKey.current(context)
        val preferred = app.endpointScores.remembered(network)
        var tested = 0
        val results = CleanIpOptimizer().scan(
            p = profile,
            targetPort = profile.port,
            enableSpeedTest = false,
            candidateCount = 40,
            preferred = preferred,
            enough = 6,
            assertNetwork = {
                check(NetworkKey.current(context) == network) { "Network changed; discarded scan results." }
            }
        ) {
            tested = it.tested
            onProgress(it.tested, it.totalCandidates)
        }
        check(NetworkKey.current(context) == network) { "Network changed; discarded scan results." }
        val trusted = results.filter { it.originVerified }
        val timestamp = System.currentTimeMillis()
        app.endpointScores.record(trusted.map {
            EndpointScoringEngine.Measurement(it.ip, network, timestamp, true, it.medianMs)
        })
        return Summary(tested, trusted.size, network)
    }
}
