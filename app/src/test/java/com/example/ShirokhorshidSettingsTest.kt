package com.example

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.vpn.connections.BuiltinConnections
import com.example.vpn.connections.BuiltinConnections.ConfigFreeMethod
import com.example.vpn.connections.ShirokhorshidSettings
import com.example.vpn.sidecar.ShirokhorshidSidecar
import com.example.vpn.sidecar.ShirokhorshidSidecar.Mode
import com.example.vpn.sidecar.ShirokhorshidSidecar.Options
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class ShirokhorshidSettingsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun connectWithoutAConfigUsesTheSavedChoices() {
        val method = ConfigFreeMethod.SHIROKHORSHID
        // Nothing saved yet: the defaults.
        val first = checkNotNull(BuiltinConnections.resolve(context, method.id))
        assertEquals(method.id, first.id)
        assertEquals(Options(), Options.read(first))

        val chosen = Options(Mode.CONDUIT, "NL", cdnFronting = false, edges = listOf("23.215.0.0/24", "1.1.1.1"), avoidIranConduits = false)
        ShirokhorshidSettings.save(context, chosen)
        val resolved = checkNotNull(BuiltinConnections.resolve(context, method.id))
        assertEquals(method.id, resolved.id)
        assertEquals(chosen, Options.read(resolved))
        assertTrue(ShirokhorshidSidecar({ null }).handles(resolved))
    }

    @Test fun brokenStoredValuesFallBackInsteadOfFailingAConnect() {
        context.getSharedPreferences(ShirokhorshidSettings.PREFS, Context.MODE_PRIVATE).edit()
            .putString("mode", "warp").putString("region", "Germany").putString("edges", "[\"x\",\"9.9.9.9\"]").commit()
        val options = ShirokhorshidSettings.load(context)
        assertEquals(Mode.AUTO, options.mode)
        assertEquals("", options.region)
        assertEquals(listOf("9.9.9.9"), options.edges)
        context.getSharedPreferences(ShirokhorshidSettings.PREFS, Context.MODE_PRIVATE).edit().putString("edges", "{not json").commit()
        assertEquals(emptyList<String>(), ShirokhorshidSettings.load(context).edges)
    }

    @Test fun itSitsNextToPsiphonInTheMethodList() {
        val methods = ConfigFreeMethod.entries
        assertEquals(methods.indexOf(ConfigFreeMethod.PSIPHON) + 1, methods.indexOf(ConfigFreeMethod.SHIROKHORSHID))
        assertEquals(null, ConfigFreeMethod.SHIROKHORSHID.aetherTransport)
    }
}
