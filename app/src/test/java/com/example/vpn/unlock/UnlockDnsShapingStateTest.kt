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

/** The query-shaping toggle, shaped re-checks and the per-network memory kept across launches. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class UnlockDnsShapingStateTest {
    private lateinit var context: Context
    private var network = "wifi:aaaaaaaaaaaa"
    private val shapedAsked = mutableListOf<QueryShape>()

    /** 5.175.161.3's plain answers are rewritten to the block page; with the Z shape it answers honestly. */
    private fun probe(shape: QueryShape?) = object : UnlockProbe {
        override fun lookup(server: String, name: String, timeoutMs: Int): Lookup? {
            if (shape != null) synchronized(shapedAsked) { shapedAsked += shape }
            val filtered = server == "5.175.161.3" && (shape != QueryShape.Z || network != "wifi:aaaaaaaaaaaa")
            return when {
                filtered && name == UnlockDnsHealth.CONTROL_NAME -> Lookup(listOf("10.10.34.35"), 5)
                name == UnlockDnsHealth.CONTROL_NAME -> Lookup(listOf("1.1.1.1"), 40)
                else -> Lookup(listOf("143.20.64.10"), 45)
            }
        }
        override fun reach(ip: String, port: Int, timeoutMs: Int): Long? = 30
    }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("maximus_unlock_dns", Context.MODE_PRIVATE).edit().clear().commit()
        UnlockDnsList.bundled = { "143.20.64.55\n5.175.161.3\n".byteInputStream() }
        UnlockDnsList.updated = null
        UnlockDns.resetForTest()
        UnlockDns.probe = { probe(null) }
        UnlockDns.shapedProbe = { probe(it) }
        UnlockDns.networkKey = { network }
        UnlockDns.load(context)
    }

    @After fun tearDown() {
        UnlockDnsList.bundled = null
        UnlockDns.probe = { UnlockDnsWire.Probe(UnlockDns.protector) }
        UnlockDns.shapedProbe = { UnlockDnsWire.Probe(UnlockDns.protector, it) }
        UnlockDns.networkKey = { null }
        UnlockDns.onChoiceChanged = null
        UnlockDns.resetForTest()
    }

    @Test fun shapingIsOffByDefaultAndPlainChecksSendNoShapes() = runBlocking {
        assertFalse(UnlockDns.state.value.shaping)
        val checks = UnlockDns.recheck(context)
        assertEquals(ServerHealth.BLOCKED, checks.first { it.ip == "5.175.161.3" }.health)
        assertTrue(shapedAsked.isEmpty())
        assertNull(UnlockDns.leadingShape("5.175.161.3"))
    }

    @Test fun theToggleIsKeptAndAShapedServerIsRememberedPerNetwork() = runBlocking {
        UnlockDns.setShaping(context, true)
        UnlockDns.load(context)
        assertTrue(UnlockDns.state.value.shaping)

        val checks = UnlockDns.recheck(context)
        val shaped = checks.first { it.ip == "5.175.161.3" }
        assertTrue(shaped.usable)
        assertEquals(QueryShape.Z, shaped.shape)
        assertNull(checks.first { it.ip == "143.20.64.55" }.shape)
        assertEquals(QueryShape.Z, UnlockDns.leadingShape("5.175.161.3"))
        assertNull(UnlockDns.leadingShape("143.20.64.55"))

        // A new launch reads the memory back from the preferences.
        UnlockDns.resetForTest()
        UnlockDns.load(context)
        assertEquals(QueryShape.Z, UnlockDns.leadingShape("5.175.161.3"))

        // The remembered shape is tried first on the next check of the same network.
        shapedAsked.clear()
        UnlockDns.recheck(context)
        assertEquals(QueryShape.Z, shapedAsked.first())

        // On another network nothing is remembered (and here no shape works).
        network = "cell:43235"
        UnlockDns.resetForTest()
        UnlockDns.load(context)
        assertNull(UnlockDns.leadingShape("5.175.161.3"))
        val elsewhere = UnlockDns.recheck(context).first { it.ip == "5.175.161.3" }
        assertEquals(ServerHealth.BLOCKED, elsewhere.health)
        assertNull(elsewhere.shape)
    }

    @Test fun turningShapingOffStopsUsingRememberedShapes() = runBlocking {
        UnlockDns.setShaping(context, true)
        UnlockDns.recheck(context)
        assertEquals(QueryShape.Z, UnlockDns.leadingShape("5.175.161.3"))
        UnlockDns.setShaping(context, false)
        assertNull(UnlockDns.leadingShape("5.175.161.3"))
        shapedAsked.clear()
        UnlockDns.recheck(context)
        assertTrue(shapedAsked.isEmpty())
    }
}
