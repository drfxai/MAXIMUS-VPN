package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.model.VlessProfile
import com.example.data.repository.ServerRepository
import com.example.data.repository.SubscriptionRepository
import com.example.vpn.hub.FreeConfigList
import com.example.vpn.hub.LastKnownGoodPool
import com.example.vpn.hub.RetainedFreeConfigs
import com.example.vpn.subscription.SubscriptionManager
import com.example.vpn.subscription.SubscriptionSnapshots
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Phases 2, 6 and 11 (scenarios A to D): a free list refresh swaps the list in one step and keeps the
 * last-known-good and in-use configs; a failed refresh changes nothing; Delete All Free removes only
 * free configs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FreeListSwapIntegrationTest {
    private lateinit var db: AppDatabase
    private lateinit var servers: ServerRepository
    private lateinit var subscriptions: SubscriptionRepository
    private var poolJson: String? = null
    private val pool = LastKnownGoodPool({ poolJson }, { poolJson = it })
    private var retainedIds = emptySet<String>()
    private val retained = RetainedFreeConfigs({ retainedIds }, { retainedIds = it })
    private var inUse = emptySet<String>()

    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = java.util.Base64.getEncoder().encodeToString(keys.public.encoded)
    private var published: Map<String, String>? = null

    private fun link(n: Int) =
        "vless://11111111-1111-1111-1111-111111111111@198.51.${n / 200}.${n % 200 + 1}:443?security=reality&sni=www.example.com&pbk=abc&sid=ab&type=tcp#Free%20VLESS%20$n"

    private fun sha(text: String) = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Publishes a signed list holding servers [range]. */
    private fun publish(range: IntRange, intelRules: String? = null) {
        val list = range.joinToString("\n") { link(it) } + "\n"
        val intelEntry = intelRules?.let { ""","intel.json":{"bytes":${it.length},"sha256":"${sha(it)}"}""" }.orEmpty()
        val manifest = """{"count":${range.count()},"created":"2026-10-06T12:00:00Z","files":{"free.txt":{"bytes":${list.length},"sha256":"${sha(list)}"}$intelEntry},"version":1}"""
        val sig = java.util.Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(manifest.toByteArray()); sign() })
        val base = FreeConfigList.URL.substringBeforeLast('/')
        published = mapOf("$base/manifest.json" to manifest, "$base/manifest.sig" to sig, FreeConfigList.URL to list) +
            listOfNotNull(intelRules?.let { "$base/${FreeConfigList.INTEL_FILE}" to it })
    }

    @get:Rule val tmp = TemporaryFolder()
    private var intel: String? = null
    private var snapshots: SubscriptionSnapshots? = null

    private fun manager() = SubscriptionManager(
        subscriptions, servers,
        snapshots = snapshots,
        download = { url -> published?.get(url) ?: throw java.io.IOException("unreachable") },
        staggerMs = 10,
        freeListEnabled = { true },
        lastKnownGood = { pool },
        inUseIds = { inUse },
        freeListKey = publicKey,
        retainedStore = retained,
        intelSink = { intel = it }
    )

    private suspend fun free(): List<VlessProfile> = servers.getProfilesBySubscription(FreeConfigList.URL).first()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        servers = ServerRepository(db.serverProfileDao())
        subscriptions = SubscriptionRepository(db.subscriptionDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `A - a refresh swaps the list atomically and keeps the three last-known-good and the active config`() = runBlocking {
        publish(1..30)
        assertTrue(manager().addAndSyncSubscription(FreeConfigList.NAME, FreeConfigList.URL).isSuccess)
        val before = free()
        assertEquals(30, before.size)
        // Three configs carried verified traffic here; one more is connected right now.
        before.take(3).forEachIndexed { i, p -> pool.recordVerified(p, 200, now = i.toLong()) }
        val active = before[3]
        inUse = setOf(active.id)

        // Watch the list while the refresh runs: it must never be empty or shrink below the old size.
        val sizes = mutableListOf<Int>()
        val watcher = CoroutineScope(Dispatchers.IO).launch {
            servers.getProfilesBySubscription(FreeConfigList.URL).collect { synchronized(sizes) { sizes += it.size } }
        }
        publish(101..130)
        val result = manager().syncSubscription(subscriptions.getSubscriptionByUrl(FreeConfigList.URL)!!)
        watcher.cancel()

        assertTrue(result.errorMessage, result.isSuccess)
        val after = free()
        val ids = after.map { it.id }.toSet()
        assertTrue(before.take(3).all { it.id in ids })
        assertTrue(active.id in ids)
        assertEquals(30, after.size)
        assertEquals(4, result.retainedCount)
        assertEquals(26, result.removedCount)
        assertEquals(26, result.addedCount)
        assertEquals(30, result.poolBefore)
        assertTrue(sizes.none { it == 0 })
        assertEquals(setOf(active.id) + before.take(3).map { it.id }, retainedIds)

        // After disconnect the in-use config goes; the three last-known-good ones stay.
        inUse = emptySet()
        assertEquals(1, manager().releaseRetained())
        assertFalse(free().any { it.id == active.id })
        assertTrue(before.take(3).all { p -> free().any { it.id == p.id } })
    }

    @Test
    fun `B - a failed refresh leaves the list as it was`() = runBlocking {
        publish(1..30)
        manager().addAndSyncSubscription(FreeConfigList.NAME, FreeConfigList.URL)
        val before = free().map { it.id }.toSet()
        published = null
        val result = manager().syncSubscription(subscriptions.getSubscriptionByUrl(FreeConfigList.URL)!!)
        assertTrue(result.fromOfflineCopy || !result.isSuccess)
        assertEquals(before, free().map { it.id }.toSet())
    }

    @Test
    fun `B2 - a failed refresh with an offline copy re-adds nothing, and an empty list comes back capped`() = runBlocking {
        snapshots = SubscriptionSnapshots(tmp.root, encrypt = { it.reversed() }, decrypt = { it.reversed() })
        publish(1..30)
        manager().addAndSyncSubscription(FreeConfigList.NAME, FreeConfigList.URL)
        val gone = free().first()
        manager().deleteFree(gone)
        published = null
        val sub = subscriptions.getSubscriptionByUrl(FreeConfigList.URL)!!
        val failed = manager().syncSubscription(sub)
        assertFalse(failed.isSuccess)
        assertFalse(failed.fromOfflineCopy)
        // Existing verified configurations retained; the one the user deleted does not come back.
        assertEquals(29, free().size)
        assertTrue(free().none { it.effectiveFingerprint == gone.effectiveFingerprint })
        manager().deleteAllFree()
        val restored = manager().syncSubscription(sub)
        assertTrue(restored.fromOfflineCopy)
        assertEquals(30, free().size)
    }

    @Test
    fun `E - intelligence rules are taken only when the signed manifest names them`() = runBlocking {
        val rules = """{"rules":[{"id":"r1","issued":1,"ttlHours":24,"confidence":1,"adjustment":1}]}"""
        publish(1..5, intelRules = rules)
        manager().addAndSyncSubscription(FreeConfigList.NAME, FreeConfigList.URL)
        assertEquals(rules, intel)
        // Changed after signing: refused, nothing is handed over (the store keeps what it had).
        intel = null
        publish(1..5, intelRules = rules)
        published = published!! + (FreeConfigList.URL.substringBeforeLast('/') + "/" + FreeConfigList.INTEL_FILE to rules.replace("\"adjustment\":1", "\"adjustment\":5"))
        manager().syncSubscription(subscriptions.getSubscriptionByUrl(FreeConfigList.URL)!!)
        assertEquals(null, intel)
    }

    @Test
    fun `C and D - Delete All Free removes only free configs, even the connected one's saved row`() = runBlocking {
        publish(1..5)
        manager().addAndSyncSubscription(FreeConfigList.NAME, FreeConfigList.URL)
        val vip = VlessProfile(name = "VIP", address = "203.0.113.50", port = 443, uuid = "22222222-2222-2222-2222-222222222222",
            security = "tls", sni = "vip.example", sourceSubscription = "https://bot.example/vip")
        val manual = VlessProfile(name = "Mine", address = "203.0.113.51", port = 443, uuid = "33333333-3333-3333-3333-333333333333",
            security = "tls", sni = "me.example")
        servers.insert(vip)
        servers.insert(manual)
        val connected = free().first()
        inUse = setOf(connected.id)
        pool.recordVerified(connected, 150, now = 1)

        assertEquals(5, manager().deleteAllFree())
        assertTrue(free().isEmpty())
        val left = servers.getAllProfilesOnce().map { it.name }.toSet()
        assertEquals(setOf("VIP", "Mine"), left)
        assertTrue(pool.entries().isEmpty())
    }
}
