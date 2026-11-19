package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vpn.connections.BuiltinConnections
import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
import com.example.vpn.engine.ProfileExtras
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class BuiltinConnectionsSettingsTest {
    @Test
    fun configFreeAetherUsesSavedSettingsAndExplicitTransportKeepsItsIdentity() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("galaxy_connections", Context.MODE_PRIVATE)
            .edit().putString("transport", "wireguard").putString("aether_family", "ipv4").commit()
        val selected = checkNotNull(BuiltinConnections.resolve(context, ConfigFreeMethod.AETHER_WARP.id))
        assertEquals(ConfigFreeMethod.AETHER_WARP.id, selected.id)
        assertEquals("wireguard", ProfileExtras.read(selected).getString("carrier"))
        assertEquals("ipv4", ProfileExtras.read(selected).getString("family"))

        val explicit = checkNotNull(BuiltinConnections.resolve(context, ConfigFreeMethod.AETHER_H2.id))
        assertEquals("h2", ProfileExtras.read(explicit).getString("carrier"))
    }
}
