package com.example.ui.home

import com.example.data.model.ConnectionState
import com.example.data.model.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** 2026-11-25 engine test: the config-free button's status line and the picker's tab counts. */
class ConfigFreeModelTest {
    @Test fun tabsCountTheConnectionsTheyList() {
        // Methods: Aether / WARP, Serverless, Tor, Psiphon, Shir o Khorshid, Psiphon over Lantern, Shir o Khorshid over
        // Lantern, Lantern, Serverless + Lantern (Build 181), Personal server (ECH) (Build 181 Phase 6), Cloudflare relay
        // (Build 181 Phase 7).
        assertEquals(11, ConfigFreePickerCounts.methods())
        // Aether & chains: H2, H3, WireGuard, Gool, Gool Classic, MASQUE-in-MASQUE, Aether → Psiphon, Aether → Tor,
        // Aether over Lantern.
        // It read "1": 10 (with the two setup cards) cut to one digit by the label.
        assertEquals(9, ConfigFreePickerCounts.chains())
    }

    private fun state(status: ConnectionStatus, seen: Boolean = false) =
        ConnectionState(status = status, attemptId = "a", deviceTrafficSeen = seen)

    @Test fun theStatusLineFollowsTheState() {
        assertEquals(ConfigFreeStatus.READY, ConfigFreeStatusModel.of(false, state(ConnectionStatus.CONNECTED)))
        assertEquals(ConfigFreeStatus.CHECKING, ConfigFreeStatusModel.of(true, state(ConnectionStatus.PREPARING)))
        assertEquals(ConfigFreeStatus.CHECKING, ConfigFreeStatusModel.of(true, state(ConnectionStatus.VERIFYING)))
        assertEquals(ConfigFreeStatus.NO_TRAFFIC,
            ConfigFreeStatusModel.of(true, state(ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED)))
        assertEquals(ConfigFreeStatus.LIMITED, ConfigFreeStatusModel.of(true, state(ConnectionStatus.CONNECTED)))
        // An engine program's device traffic seen on the TUN → engine hop verifies it.
        assertEquals(ConfigFreeStatus.VERIFIED, ConfigFreeStatusModel.of(true, state(ConnectionStatus.CONNECTED, seen = true)))
        assertEquals(ConfigFreeStatus.FAILED, ConfigFreeStatusModel.of(true, state(ConnectionStatus.FAILED)))
        assertEquals(ConfigFreeStatus.INTERRUPTED, ConfigFreeStatusModel.of(true, state(ConnectionStatus.DEGRADED)))
        assertEquals(ConfigFreeStatus.STOPPING, ConfigFreeStatusModel.of(true, state(ConnectionStatus.DISCONNECTING)))
        assertEquals(null, ConfigFreeStatus.READY.detail)
    }
}
