package com.example.vpn.smart

import com.example.data.model.ScoringProfile
import com.example.data.model.ServerCategory
import com.example.data.model.VlessProfile

object SmartConnect {

    data class SmartSelection(
        val profile: VlessProfile,
        val reasonPing: String,
        val reasonDownload: String,
        val reasonStability: String,
        val reasonPacketLoss: String,
        val overallScore: Double,
        val description: String
    )

    /**
     * The healthy, supported nodes in the order the Optimal Node agent would try them: best first.
     * The agent connects to the first and, if it is disrupted or slow, moves down this list before it
     * reaches for anything heavier (clean addresses, the free list, LAB recovery).
     */
    fun rankTopNodes(
        profiles: List<VlessProfile>,
        scoringProfile: ScoringProfile = ScoringProfile.BALANCED,
        count: Int = 3
    ): List<SmartSelection> {
        if (profiles.isEmpty() || count <= 0) return emptyList()

        // Only nodes the core can actually run.
        val supportedProfiles = profiles.filter { com.example.vpn.engine.RuntimeCapabilities.unsupportedReason(it) == null }
        val healthyProfiles = supportedProfiles.filter {
            it.category != ServerCategory.OFFLINE &&
                it.packetLoss < 40.0 &&
                (it.lastLatencyMs == null || it.lastLatencyMs > 0)
        }
        val pool = if (healthyProfiles.isNotEmpty()) healthyProfiles else supportedProfiles

        // Favorite bonus, then overallScore, stability, lower latency. maxWith's comparator read in reverse
        // gives the descending order the agent wants.
        val ordered = pool.sortedWith(
            compareByDescending<VlessProfile> { if (it.isFavorite) 1 else 0 }
                .thenByDescending { it.overallScore }
                .thenByDescending { it.stability }
                .thenBy { it.lastLatencyMs ?: 9999L }
        )
        return ordered.take(count).map { describe(it, scoringProfile) }
    }

    /**
     * Analyzes all available profiles and selects the optimal healthy node for the active scoring profile.
     */
    fun selectBestNode(profiles: List<VlessProfile>, scoringProfile: ScoringProfile = ScoringProfile.BALANCED): SmartSelection? =
        rankTopNodes(profiles, scoringProfile, count = 1).firstOrNull()

    /** The human-readable rating lines for one node. */
    private fun describe(best: VlessProfile, scoringProfile: ScoringProfile): SmartSelection {
        val pingStr = if (best.lastLatencyMs != null && best.lastLatencyMs > 0) "${best.lastLatencyMs} ms" else "Not measured"
        val dlStr = if (best.downloadMbps > 0) "%.1f Mbps".format(best.downloadMbps) else "Not measured"
        val lossStr = "%.1f%%".format(best.packetLoss)

        val pingRating = when {
            best.lastLatencyMs != null && best.lastLatencyMs < 60 -> "Excellent"
            best.lastLatencyMs != null && best.lastLatencyMs < 150 -> "Very Good"
            best.lastLatencyMs != null && best.lastLatencyMs < 300 -> "Good"
            else -> "Acceptable"
        }

        val dlRating = when {
            best.downloadMbps >= 100.0 -> "Ultra High"
            best.downloadMbps >= 40.0 -> "Excellent"
            best.downloadMbps >= 15.0 -> "Good"
            else -> "Standard"
        }

        val stabRating = when {
            best.stability >= 95.0 -> "${best.stability.toInt()}%"
            best.stability >= 85.0 -> "Very High (${best.stability.toInt()}%)"
            else -> "Stable (${best.stability.toInt()}%)"
        }

        return SmartSelection(
            profile = best,
            reasonPing = "$pingRating ($pingStr)",
            reasonDownload = "$dlRating ($dlStr)",
            reasonStability = stabRating,
            reasonPacketLoss = lossStr,
            overallScore = best.overallScore,
            description = "Selected ${best.name} based on ${scoringProfile.title} profile rating."
        )
    }
}
