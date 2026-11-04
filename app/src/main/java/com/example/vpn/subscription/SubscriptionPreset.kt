package com.example.vpn.subscription

import com.example.data.model.EngineType
import com.example.data.model.VlessProfile
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.SingBoxSidecar

/** Subscription defaults never overwrite a per-node core choice, TLS settings or routing rules. */
data class SubscriptionPreset(val core: EngineType? = null, val excludedCountries: Set<String> = emptySet()) {
    init {
        require(core == null || core in setOf(EngineType.XRAY, EngineType.SING_BOX))
        require(excludedCountries.size <= 250 && excludedCountries.all { it.matches(Regex("[A-Z]{2}")) })
    }

    fun apply(profile: VlessProfile): VlessProfile {
        var p = profile
        var extras = ProfileExtras.read(p)
        if (core == null && excludedCountries.isEmpty() && !extras.has("subscriptionPresetCore") &&
            !extras.has(AUTO_EXCLUDED)) return profile
        if (!extras.optBoolean("coreLocked")) {
            val previous = extras.optString("subscriptionPresetCore")
            if (previous == p.engineType.name) {
                p = ProfileExtras.with(p.copy(engineType = EngineType.XRAY), "engine", null)
            }
            p = ProfileExtras.with(p, "subscriptionPresetCore", null)
            if (core == EngineType.SING_BOX && p.engineType in setOf(EngineType.AUTO, EngineType.XRAY) &&
                SingBoxSidecar.conversionProblem(p) == null) {
                p = SingBoxSidecar.select(p)
                p = ProfileExtras.with(p, "subscriptionPresetCore", EngineType.SING_BOX.name)
            }
        }
        extras = ProfileExtras.read(p)
        val excluded = p.countryCode?.uppercase()?.let { it in excludedCountries } == true
        val changed = extras.optBoolean(AUTO_EXCLUDED) != excluded
        if (changed) p = ProfileExtras.with(p, AUTO_EXCLUDED, if (excluded) true else null)
        return if (p == profile) profile else p.copy(canonicalFingerprint = "")
    }

    companion object {
        const val AUTO_EXCLUDED = "subscriptionAutoExcluded"
        fun autoSelectable(p: VlessProfile): Boolean =
            !com.example.vpn.sidecar.ServerlessSidecar.handles(p) && !ProfileExtras.read(p).optBoolean(AUTO_EXCLUDED)
    }
}
