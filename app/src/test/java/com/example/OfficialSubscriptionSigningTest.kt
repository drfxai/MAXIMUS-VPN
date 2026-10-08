package com.example

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.model.VlessProfile
import com.example.data.repository.ServerRepository
import com.example.data.repository.SubscriptionRepository
import com.example.vpn.subscription.OfficialSequences
import com.example.vpn.subscription.OfficialSigning
import com.example.vpn.subscription.OfficialSubscriptions
import com.example.vpn.subscription.SubscriptionManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * The official subscription as a signed envelope: any independent copy is accepted only when the
 * signature, the subscription id, the payload hash, the expiry and the sequence all check out; a
 * verified list replaces the saved servers atomically and never removes the one in use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfficialSubscriptionSigningTest {
    private val primary = OfficialSubscriptions.URLS.first()
    private val mirrorBase = "https://mirror.example/maximus"
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = java.util.Base64.getEncoder().encodeToString(keys.public.encoded)
    private val now = System.currentTimeMillis()

    private lateinit var db: AppDatabase
    private lateinit var servers: ServerRepository
    private lateinit var subscriptions: SubscriptionRepository
    private val sequences = OfficialSequences.InMemory()
    private val served = mutableMapOf<String, String>()
    private var inUse = emptySet<String>()

    private fun link(n: Int) =
        "vless://11111111-1111-1111-1111-111111111111@198.51.100.$n:443?security=reality&sni=www.example.com&pbk=abc&sid=ab&type=tcp#Official%20$n"

    private fun envelope(range: IntRange, sequence: Long, id: String = "official-sub", expires: Long = now + 86_400_000L,
                         tamper: Boolean = false): String {
        val payload = java.util.Base64.getEncoder().encodeToString(range.joinToString("\n") { link(it) }.toByteArray())
        val manifest = JSONObject().put("version", 1).put("id", id).put("sequence", sequence).put("expires", expires)
            .put("sha256", OfficialSigning.sha256(payload)).toString()
        val sig = java.util.Base64.getEncoder().encodeToString(
            Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(manifest.toByteArray()); sign() })
        return JSONObject().put("manifest", manifest).put("signature", sig)
            .put("payload", if (tamper) payload.reversed() else payload).toString()
    }

    private fun manager(key: String = publicKey) = SubscriptionManager(
        subscriptions, servers,
        download = { url -> served[url] ?: throw java.io.IOException("unreachable") },
        staggerMs = 10,
        freeListEnabled = { true },
        lastKnownGood = { null },
        inUseIds = { inUse },
        retainedStore = null,
        intelSink = null,
        officialKey = key,
        officialSequences = sequences,
        officialMirrorBases = mirrorBase
    )

    private suspend fun official(): List<VlessProfile> = servers.getProfilesBySubscription(primary).first()

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        servers = ServerRepository(db.serverProfileDao())
        subscriptions = SubscriptionRepository(db.subscriptionDao())
    }

    @After fun tearDown() = db.close()

    @Test fun theIndependentMirrorServesTheListWhenTheWorkerIsBlocked() = runBlocking {
        // The Worker is unreachable; only the mirror answers, with a signed copy.
        served["$mirrorBase/sub${OfficialSigning.SUFFIX}"] = envelope(1..5, sequence = now)
        val result = manager().addAndSyncSubscription(OfficialSubscriptions.NAME, primary)
        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals("$mirrorBase/sub", result.viaMirror)
        assertEquals(5, official().size)
        assertEquals(now, sequences.last("official-sub"))
    }

    @Test fun aVerifiedListReplacesTheServersButKeepsTheOneInUse() = runBlocking {
        served["$primary${OfficialSigning.SUFFIX}"] = envelope(1..5, sequence = now)
        manager().addAndSyncSubscription(OfficialSubscriptions.NAME, primary)
        val inUseProfile = official().first { it.address == "198.51.100.1" }
        inUse = setOf(inUseProfile.id)

        // The admin removed servers 1 and 2 and added 6.
        served["$primary${OfficialSigning.SUFFIX}"] = envelope(3..6, sequence = now + 1)
        val result = manager().syncSubscription(subscriptions.getSubscriptionByUrl(primary)!!)
        assertTrue(result.errorMessage, result.isSuccess)
        val addresses = official().map { it.address }.toSet()
        assertEquals(setOf("198.51.100.1", "198.51.100.3", "198.51.100.4", "198.51.100.5", "198.51.100.6"), addresses)
    }

    @Test fun anOlderCopyIsRefusedAndTheListStaysAsItIs() = runBlocking {
        served["$primary${OfficialSigning.SUFFIX}"] = envelope(1..5, sequence = now)
        manager().addAndSyncSubscription(OfficialSubscriptions.NAME, primary)
        // A mirror replays an older copy (with fewer servers).
        served.clear()
        served["$mirrorBase/sub${OfficialSigning.SUFFIX}"] = envelope(1..1, sequence = now - 60_000)
        val result = manager().syncSubscription(subscriptions.getSubscriptionByUrl(primary)!!)
        assertFalse(result.isSuccess)
        assertEquals(5, official().size)
    }

    @Test fun unsignedTamperedWrongIdAndExpiredCopiesAreRefused() {
        assertThrows(OfficialSigning.Refused::class.java) { OfficialSigning.verify(envelope(1..2, now, tamper = true), "official-sub", 0, publicKey, now) }
        assertThrows(OfficialSigning.Refused::class.java) { OfficialSigning.verify(envelope(1..2, now, id = "official-vip"), "official-sub", 0, publicKey, now) }
        assertThrows(OfficialSigning.Refused::class.java) { OfficialSigning.verify(envelope(1..2, now, expires = now - 1), "official-sub", 0, publicKey, now) }
        assertThrows(OfficialSigning.Refused::class.java) { OfficialSigning.verify(java.util.Base64.getEncoder().encodeToString(link(1).toByteArray()), "official-sub", 0, publicKey, now) }
        val otherKey = java.util.Base64.getEncoder().encodeToString(
            KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public.encoded)
        assertThrows(OfficialSigning.Refused::class.java) { OfficialSigning.verify(envelope(1..2, now), "official-sub", 0, otherKey, now) }
        assertEquals(now, OfficialSigning.verify(envelope(1..2, now), "official-sub", now, publicKey, now).sequence)
    }

    @Test fun withoutAKeyInTheBuildThePlainListIsUsedAsBefore() = runBlocking {
        served[primary] = java.util.Base64.getEncoder().encodeToString((1..3).joinToString("\n") { link(it) }.toByteArray())
        val result = manager(key = "").addAndSyncSubscription(OfficialSubscriptions.NAME, primary)
        assertTrue(result.errorMessage, result.isSuccess)
        assertEquals(3, official().size)
        assertEquals("official-vip", OfficialSigning.idFor("https://maximus-bot.drpouriafx.workers.dev/vip"))
        assertTrue(OfficialSubscriptions.mirrorsFor(primary, "").isEmpty())
    }
}
