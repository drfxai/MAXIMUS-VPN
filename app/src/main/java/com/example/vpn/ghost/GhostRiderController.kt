package com.example.vpn.ghost

import android.content.Context
import com.example.RayApplication
import com.example.vpn.VpnController
import com.example.vpn.safety.GhostRiderPath
import com.example.vpn.sidecar.ChainRunner
import com.example.vpn.sidecar.TorSidecar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The two Ghost Rider actions that change the running state. Kept out of the UI so the dashboard only
 * draws. Both are connectivity-safe: routing through Tor is an explicit choice (the default Connect
 * still uses the chosen server with Ghost Rider's hardening), and panic only stops and wipes — it never
 * leaves traffic flowing outside the tunnel.
 */
object GhostRiderController {
    /** Engines that can actually run on this device: Tor is bundled; Psiphon only when its config is present. */
    private fun availableEngines(context: Context): Set<String> {
        val ids = mutableSetOf(GhostRiderPath.TOR)
        val hasPsiphon = runCatching {
            context.assets.open("psiphon/config.json").close(); true
        }.getOrDefault(false)
        if (hasPsiphon) ids += GhostRiderPath.PSIPHON
        return ids
    }

    private fun titleOf(engineId: String) = if (engineId == GhostRiderPath.PSIPHON) "Psiphon" else "Tor"

    /** A stable id so repeated routing reuses one saved entry instead of piling up servers. */
    private const val PATH_ID = "ghost-rider-path"

    /**
     * Connect through the strongest Tor path this device can run: Psiphon → Tor when Psiphon is
     * configured, Tor alone otherwise. The service resolves a connect by saved-profile id, so the path is
     * saved under a stable id first (as the LAB does with its chains), then started; [force] skips the
     * saved-server race so the chosen private path is used.
     */
    fun routeThroughTor(context: Context) {
        // Already carried by Tor (a saved bridge config that works here): starting Tor again over
        // Snowflake would only replace a working path with one that may not start on this network.
        val state = VpnController.connectionState.value
        val active = state.activeProfile
        if (state.isTunnelUp && active != null && carriesTor(active)) {
            com.example.xray.XrayLogManager.i("GHOST", "Already routed through Tor (${com.example.core.SecretRedactor.redact(active.name)}); keeping it.")
            return
        }
        val base = when (GhostRiderPath.preference(availableEngines(context)).first()) {
            GhostRiderPath.Step.CHAIN_PSIPHON_TOR -> ChainRunner.profile(GhostRiderPath.chain()) { titleOf(it) }
            else -> TorSidecar.profile(name = "Ghost Rider · Tor")
        }
        val profile = base.copy(id = PATH_ID)
        val app = RayApplication.instance
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { app.serverRepository.insert(profile) }
            runCatching { app.settingsRepository.setSelectedProfileId(PATH_ID) }
            withContext(Dispatchers.Main) { VpnController.startVpn(context, profile, smart = false, force = true) }
        }
    }

    /** Whether [p] runs through Tor: a Tor config, or a chain with a Tor hop. */
    internal fun carriesTor(p: com.example.data.model.VlessProfile): Boolean =
        com.example.vpn.sidecar.Sidecars.forProfile(p)?.id == TorSidecar.ID ||
            ChainRunner.readChain(p)?.hops?.any { it.engineId == TorSidecar.ID } == true

    /**
     * Panic: drop the tunnel and wipe the sensitive on-device state at once. [regenerateAtomicId] clears
     * every secret conversation and rotates the device's secret-chat identity, so nothing links back;
     * the app's log is deleted as well.
     */
    fun panic(context: Context) {
        runCatching { VpnController.stopVpn(context) }
        runCatching { com.example.chat.SecretChatManager(context).regenerateAtomicId() }
        // The app's own log is kept on the phone across restarts; panic deletes it too.
        runCatching { com.example.xray.XrayLogManager.wipeDisk() }
        runCatching { com.example.vpn.sidecar.TorInApp.wipeNotices(context) }
    }
}
