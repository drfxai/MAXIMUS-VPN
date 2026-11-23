package com.example.vpn.unlock

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Unlock DNS choices the sheet changes, kept across launches, and the re-check that ranks the rows. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [33])
class UnlockDnsStateTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("maximus_unlock_dns", Context.MODE_PRIVATE).edit().clear().commit()
        UnlockDnsList.bundled = { "143.20.64.55\n193.233.112.67\n45.13.237.213\n5.175.161.3\n".byteInputStream() }
        UnlockDnsList.updated = null
        UnlockDns.resetForTest()
        UnlockDns.load(context)
    }

    @After fun tearDown() {
        UnlockDnsList.bundled = null
        UnlockDns.probe = { UnlockDnsWire.Probe(UnlockDns.protector) }
        UnlockDns.onChoiceChanged = null
    }

    @Test fun autoPickIsOnByDefaultAndTheBundledListIsShown() {
        val s = UnlockDns.state.value
        assertTrue(s.autoPick)
        assertNull(s.manual)
        assertEquals(UnlockDnsList.FALLBACK, s.servers)
        assertFalse(s.listFromPack)
    }

    @Test fun customServersAreAddedValidatedRemovedAndRemembered() {
        var changes = 0
        UnlockDns.onChoiceChanged = { changes++ }
        assertEquals(UnlockDnsList.CustomResult.Added("9.9.9.9"), UnlockDns.addCustom(context, "9.9.9.9"))
        assertEquals(UnlockDnsList.CustomResult.Invalid, UnlockDns.addCustom(context, "10.0.0.1"))
        assertEquals(UnlockDnsList.CustomResult.Duplicate, UnlockDns.addCustom(context, "9.9.9.9"))
        assertEquals(listOf("9.9.9.9"), UnlockDns.state.value.custom)
        assertEquals(1, changes)

        UnlockDns.load(context)
        assertEquals(listOf("9.9.9.9"), UnlockDns.state.value.custom)

        UnlockDns.setAutoPick(context, false)
        UnlockDns.select(context, "9.9.9.9")
        assertEquals("9.9.9.9", UnlockDns.state.value.manual)
        UnlockDns.removeCustom(context, "9.9.9.9")
        assertTrue(UnlockDns.state.value.custom.isEmpty())
        assertNull(UnlockDns.state.value.manual)
        // An official server cannot be removed.
        UnlockDns.removeCustom(context, "143.20.64.55")
        assertTrue("143.20.64.55" in UnlockDns.state.value.servers)
    }

    @Test fun manualSelectionNeedsAutoPickOffAndPersists() {
        UnlockDns.select(context, "45.13.237.213")
        assertNull(UnlockDns.state.value.manual)
        UnlockDns.setAutoPick(context, false)
        UnlockDns.select(context, "45.13.237.213")
        UnlockDns.select(context, "8.8.8.8") // not listed: ignored
        UnlockDns.load(context)
        assertFalse(UnlockDns.state.value.autoPick)
        assertEquals("45.13.237.213", UnlockDns.state.value.manual)
        UnlockDns.setAutoPick(context, true)
        UnlockDns.load(context)
        assertTrue(UnlockDns.state.value.autoPick)
    }

    @Test fun aRecheckRanksRowsAndBlockedServersGoLast() = runBlocking {
        UnlockDns.probe = {
            object : UnlockProbe {
                override fun lookup(server: String, name: String, timeoutMs: Int): Lookup? = when {
                    server == "5.175.161.3" -> null
                    name == UnlockDnsHealth.CONTROL_NAME -> Lookup(listOf("1.1.1.1"), if (server == "45.13.237.213") 20 else 60)
                    else -> Lookup(listOf("143.20.64.10"), 70)
                }
                override fun reach(ip: String, port: Int, timeoutMs: Int): Long? = 30
            }
        }
        val checks = UnlockDns.recheck(context)
        assertEquals(4, checks.size)
        val rows = UnlockDns.state.value.rows
        assertEquals("45.13.237.213", rows.first().ip)
        assertEquals("5.175.161.3", rows.last().ip)
        assertEquals(ServerHealth.BLOCKED, rows.last().check?.health)
        assertFalse(UnlockDns.state.value.checking)
    }

    @Test fun badgesResetWhenTheActiveServerChanges() {
        UnlockDns.setActive("143.20.64.55", 38)
        assertEquals(UnlockDnsHealth.Service.entries.toSet(), UnlockDns.state.value.services.keys)
        assertTrue(UnlockDns.state.value.services.values.all { it == null })
        UnlockDns.setActive(null, null)
        assertNull(UnlockDns.state.value.active)
        assertTrue(UnlockDns.state.value.services.isEmpty())
    }
}
