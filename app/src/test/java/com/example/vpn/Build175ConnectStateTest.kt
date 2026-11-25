package com.example.vpn

import com.example.data.model.ConnectionStatus
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

/**
 * Build 175 report: every Connect pressed a few seconds after a Disconnect (10:39, 10:57, 11:10, 11:28, 11:34, 11:36,
 * 11:40) stayed at the "Preparing" the button showed. The Disconnect fenced tickets below the old service's generation
 * and the service stopped itself; the next Connect started a new service whose own generation counted from 1 again,
 * below the fence, so the attempt could change neither the state nor the notification. Psiphon (direct) read the
 * fence as a cancel and ended silently; Lantern and Psiphon over Lantern verified traffic that never counted.
 */
class Build175ConnectStateTest {
    @Before fun setUp() = StopFence.resetForTest()
    @After fun tearDown() = StopFence.resetForTest()

    @Test fun aConnectInANewServiceInstanceIsNotFencedByTheOldInstancesStop() {
        val shared = AtomicLong(0)
        val first = ConnectionLifecycle(shared)
        first.newIntent()                       // Connect
        StopFence.fence(first.newIntent())      // Disconnect; the service then stops itself
        val second = ConnectionLifecycle(shared) // Android creates a new service for the next Connect
        val ticket = second.newIntent()
        assertTrue("the new Connect owns the state", StopFence.admits(ticket))
        // An attempt from before the Stop is still fenced.
        assertFalse(StopFence.admits(1))
    }

    @Test fun separateCountersReproduceTheReportedBug() {
        val first = ConnectionLifecycle(AtomicLong(0))
        first.newIntent()
        StopFence.fence(first.newIntent())
        val second = ConnectionLifecycle(AtomicLong(0))
        assertFalse("what build 175 did: the new attempt was fenced off", StopFence.admits(second.newIntent()))
    }

    @Test fun theServiceSharesOneProcessWideCounter() {
        val a = ConnectionLifecycle(ConnectionLifecycle.PROCESS)
        val before = a.newIntent()
        val b = ConnectionLifecycle(ConnectionLifecycle.PROCESS)
        assertTrue(b.newIntent() > before)
    }

    @Test fun aConnectJobThatEndsWhileStillConnectingBecomesAFailure() {
        assertTrue(UnfinishedConnect.stuck(ConnectionStatus.PREPARING, current = true, cancelled = false))
        assertTrue(UnfinishedConnect.stuck(ConnectionStatus.VERIFYING, current = true, cancelled = false))
        // A newer Connect or Disconnect, or a cancelled job: not this job's business.
        assertFalse(UnfinishedConnect.stuck(ConnectionStatus.PREPARING, current = false, cancelled = false))
        assertFalse(UnfinishedConnect.stuck(ConnectionStatus.PREPARING, current = true, cancelled = true))
        // Settled states are left alone.
        for (s in listOf(ConnectionStatus.CONNECTED, ConnectionStatus.FAILED, ConnectionStatus.DISCONNECTED,
            ConnectionStatus.TUNNEL_STARTED_CONNECTIVITY_UNVERIFIED, ConnectionStatus.DEGRADED, ConnectionStatus.DISCONNECTING)) {
            assertFalse("$s", UnfinishedConnect.stuck(s, current = true, cancelled = false))
        }
        val reason = UnfinishedConnect.reason(kotlinx.coroutines.CancellationException("Psiphon start cancelled"))
        assertTrue(reason, reason.contains("ended without a result"))
        assertTrue(reason, reason.contains("Psiphon start cancelled"))
        assertTrue(UnfinishedConnect.reason(null).contains("traffic is unverified"))
    }
}
