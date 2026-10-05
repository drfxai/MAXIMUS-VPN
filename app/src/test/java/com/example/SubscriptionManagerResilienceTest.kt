package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.repository.ServerRepository
import com.example.data.repository.SubscriptionRepository
import com.example.vpn.subscription.SubscriptionManager
import com.example.vpn.subscription.SubscriptionSnapshots
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
import java.io.IOException

/** War plan Phase 3: the subscription manager with mirrors, the offline copy and refresh-when-due. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SubscriptionManagerResilienceTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var servers: ServerRepository
    private lateinit var subscriptions: SubscriptionRepository
    private val reachable = mutableSetOf<String>()

    private val primary = "https://subs.example.org/s/abc"
    private val mirror = "https://mirror.example.net/s/abc"
    private val payload = "vless://11111111-1111-1111-1111-111111111111@203.0.113.7:443?security=tls&sni=a.example.org&type=ws&path=%2F#one\n" +
        "trojan://secret@203.0.113.8:443?security=tls&sni=b.example.org#two"

    private fun manager() = SubscriptionManager(
        subscriptions,
        servers,
        snapshots = SubscriptionSnapshots(tmp.root, encrypt = { it.reversed() }, decrypt = { it.reversed() }),
        download = { url -> if (url in reachable) payload else throw IOException("Connection reset") },
        staggerMs = 100
    )

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
    fun `blocked address falls back to a pasted mirror, then to the offline copy`() = runBlocking {
        reachable += mirror
        val added = manager().addAndSyncSubscription("Friends", "$primary\n$mirror")
        assertTrue(added.errorMessage, added.isSuccess)
        assertEquals(mirror, added.viaMirror)
        assertEquals(2, servers.getAllProfilesOnce().size)
        val saved = subscriptions.getSubscriptionByUrl(primary)!!
        assertEquals(listOf(mirror), saved.mirrors)

        // Every source blocked and the servers deleted: the offline copy brings them back.
        reachable.clear()
        servers.deleteBySubscription(primary)
        val offline = manager().syncSubscription(saved)
        assertFalse(offline.isSuccess)
        assertTrue(offline.fromOfflineCopy)
        assertEquals(2, servers.getAllProfilesOnce().size)
        assertTrue(subscriptions.getSubscriptionByUrl(primary)!!.lastError!!.startsWith("Offline copy from"))

        // The failed subscription is due; once a tunnel is up and the address answers, it recovers.
        reachable += primary
        val refreshed = manager().refreshDue()
        assertEquals(1, refreshed.size)
        assertTrue(refreshed.single().isSuccess)
        assertEquals(null, subscriptions.getSubscriptionByUrl(primary)!!.lastError)
        assertEquals(emptyList<Any>(), manager().refreshDue())
    }

    @Test
    fun `adding the same address again adds its mirrors instead of a second subscription`() = runBlocking {
        reachable += primary
        manager().addAndSyncSubscription("A", primary)
        manager().addAndSyncSubscription("A again", "$primary $mirror")
        val all = subscriptions.getAllOnce()
        assertEquals(1, all.size)
        assertEquals(listOf(mirror), all.single().mirrors)
    }
}
