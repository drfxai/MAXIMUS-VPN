package com.example.vpn.smart

import com.example.data.model.VlessProfile
import com.example.panels.servers.ManagedNodeRecord
import com.example.vpn.sidecar.PsiphonSidecar

/**
 * Explicit trust boundary for Smart Connect: a managed node is not "available" merely because a
 * server entry or systemd unit exists. Manual imports preserve their existing selection behavior.
 */
object ManagedNodeEligibility {
    const val MAX_EVIDENCE_AGE_MS = 10 * 60_000L

    fun allowed(profile: VlessProfile, managed: List<ManagedNodeRecord>,
                now: Long = System.currentTimeMillis(), networkKey: String? = null): Boolean {
        val matching = managed.filter { it.profileId == profile.id }
        val isPrivatePsiphon = PsiphonSidecar.privateId(profile).isNotBlank()
        if (matching.isEmpty()) return !isPrivatePsiphon  // private entries fail closed
        if (matching.size != 1) return false
        val node = matching.single()
        return node.state == ManagedNodeRecord.State.AVAILABLE &&
            (networkKey == null || networkKey.isNotBlank() && node.networkKey == networkKey) &&
            node.updatedAt > 0 && now >= node.updatedAt && now - node.updatedAt <= MAX_EVIDENCE_AGE_MS
    }

    fun verifiedCount(profiles: List<VlessProfile>, managed: List<ManagedNodeRecord>,
                      now: Long = System.currentTimeMillis(), networkKey: String? = null): Int =
        profiles.count { p -> managed.any { it.profileId == p.id } &&
            allowed(p, managed, now, networkKey) }
}
