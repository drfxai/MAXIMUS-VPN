package com.example.vpn.smart

import android.content.Context
import com.example.vpn.sidecar.TorSidecar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EngineRouteHintsTest {
    @Test fun hintsAreNetworkScopedExpiringAndRequireTheSameProfile() {
        val context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences("engine-route-hints", Context.MODE_PRIVATE).edit().clear().commit()
        val p = TorSidecar.profile()
        EngineRouteHints.record(context, "cell-a", p, 1000)
        assertEquals(listOf(p), EngineRouteHints.preferred(context, "cell-a", listOf(p), 2000))
        assertTrue(EngineRouteHints.preferred(context, "cell-b", listOf(p), 2000).isEmpty())
        assertTrue(EngineRouteHints.preferred(context, "cell-a", listOf(p), 0).isEmpty())
        assertTrue(EngineRouteHints.preferred(context, "cell-a", listOf(p), 90_000_000).isEmpty())
        assertTrue(EngineRouteHints.preferred(context, "cell-a", emptyList(), 2000).isEmpty())
        assertNull(p.lastTestedTimestamp)
    }
}
