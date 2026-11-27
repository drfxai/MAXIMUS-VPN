package com.example.vpn.sidecar

import com.example.vpn.connections.BuiltinMethodMemory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Build 181 3.1: AMP -> SQS -> Netlify -> CDN77 ladder, two raced, winner remembered per network. */
class SnowflakeRendezvousTest {
    private val store = mutableMapOf<String, String>()

    @Before fun setUp() {
        store.clear()
        BuiltinMethodMemory.read = { store[it] }
        BuiltinMethodMemory.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
        BuiltinMethodMemory.network = { "cell:432-35" }
        SnowflakeRendezvous.active = null
        SnowflakeRendezvous.reset()
    }

    @After fun tearDown() {
        BuiltinMethodMemory.read = { null }
        BuiltinMethodMemory.write = { _, _ -> }
        BuiltinMethodMemory.network = { null }
        SnowflakeRendezvous.active = null
    }

    @Test fun ladderStartsWithAmpAndRacesTwo() {
        val p = SnowflakeRendezvous.pair(null, 0)
        assertEquals(SnowflakeRendezvous.Method.AMP to SnowflakeRendezvous.Method.SQS, p)
        assertEquals(SnowflakeRendezvous.Method.NETLIFY to SnowflakeRendezvous.Method.CDN77, SnowflakeRendezvous.pair(null, 1))
        assertEquals(p, SnowflakeRendezvous.pair(null, 2))
        val lines = SnowflakeRendezvous.lines(p)
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains(SnowflakeRendezvous.FP_A) && lines[0].contains("ampcache=https://cdn.ampproject.org/"))
        assertTrue(lines[1].contains(SnowflakeRendezvous.FP_B) && lines[1].contains("sqsqueue="))
    }

    /** Tor's built-in lines, 2026-01-29 (Tor forum, "Tor not working in Iran"), as published. */
    private val published = listOf(
        "snowflake 192.0.2.5:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://snowflake-broker.torproject.net/ ampcache=https://cdn.ampproject.org/ front=www.google.com  ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn",
        "snowflake 192.0.2.6:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA url=https://snowflake-broker.torproject.net/ ampcache=https://cdn.ampproject.org/ front=www.google.com ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn",
        "snowflake 192.0.2.3:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 url=https://voluble-torrone-fc39bf.netlify.app/ fronts=vuejs.org ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn",
        "snowflake 192.0.2.4:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA url=https://voluble-torrone-fc39bf.netlify.app/ fronts=vuejs.org ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn",
        "snowflake 192.0.2.5:80 2B280B23E1107BB62ABFC40DDCC8824814F80A72 fingerprint=2B280B23E1107BB62ABFC40DDCC8824814F80A72 sqsqueue=https://sqs.us-east-1.amazonaws.com/893902434899/snowflake-broker sqscreds=eyJhd3MtYWNjZXNzLWtleS1pZCI6IkFLSUE1QUlGNFdKSlhTN1lIRUczIiwiYXdzLXNlY3JldC1rZXkiOiI3U0RNc0pBNHM1RitXZWJ1L3pMOHZrMFFXV0lsa1c2Y1dOZlVsQ0tRIn0= ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn",
        "snowflake 192.0.2.6:80 8838024498816A039FCBBAB14E6F40A0843051FA fingerprint=8838024498816A039FCBBAB14E6F40A0843051FA sqsqueue=https://sqs.us-east-1.amazonaws.com/893902434899/snowflake-broker sqscreds=eyJhd3MtYWNjZXNzLWtleS1pZCI6IkFLSUE1QUlGNFdKSlhTN1lIRUczIiwiYXdzLXNlY3JldC1rZXkiOiI3U0RNc0pBNHM1RitXZWJ1L3pMOHZrMFFXV0lsa1c2Y1dOZlVsQ0tRIn0= ice=stun:stun.antisip.com:3478,stun:stun.epygi.com:3478,stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443 utls-imitate=hellorandomizedalpn"
    )

    @Test fun linesMatchTorsBuiltInLinesVerbatim() {
        val expected = published.map { it.replace(Regex(" +"), " ") }.toSet()
        val ours = listOf(SnowflakeRendezvous.Method.AMP, SnowflakeRendezvous.Method.NETLIFY, SnowflakeRendezvous.Method.SQS)
            .flatMap { it.lines() }.toSet()
        assertEquals(expected, ours)
    }

    @Test fun cdn77PairIsThePreBuild181Default() {
        assertEquals(TorSidecar.SNOWFLAKE, SnowflakeRendezvous.Method.CDN77.lines())
        assertTrue(TorSidecar.SNOWFLAKE[0].contains("url=https://1098762253.rsc.cdn77.org/"))
    }

    @Test fun winnerIsReadFromTheBridgeDescriptorNoticeAndRemembered() {
        val pair = SnowflakeRendezvous.current()
        SnowflakeRendezvous.active = pair
        SnowflakeRendezvous.observe("[notice] Bootstrapped 10% (conn_done)")
        assertNull(SnowflakeRendezvous.winner("cell:432-35"))
        SnowflakeRendezvous.observe("[notice] new bridge descriptor 'flakey' (fresh): \$${SnowflakeRendezvous.FP_B}~flakey at 192.0.2.6")
        assertEquals(SnowflakeRendezvous.Method.SQS, SnowflakeRendezvous.winner("cell:432-35"))
        assertEquals(SnowflakeRendezvous.Method.SQS to SnowflakeRendezvous.Method.NETLIFY, SnowflakeRendezvous.current())
        // another network keeps its own ladder
        assertNull(SnowflakeRendezvous.winner("wifi:other"))
    }

    @Test fun failedStartMovesOnAPairAndForgetsTheWinner() {
        SnowflakeRendezvous.recordWinner("cell:432-35", SnowflakeRendezvous.Method.AMP)
        SnowflakeRendezvous.active = SnowflakeRendezvous.current()
        SnowflakeRendezvous.finished(false)
        assertNull(SnowflakeRendezvous.winner("cell:432-35"))
        assertEquals(1, SnowflakeRendezvous.failedPairs("cell:432-35"))
        assertEquals(SnowflakeRendezvous.Method.NETLIFY to SnowflakeRendezvous.Method.CDN77, SnowflakeRendezvous.current())
    }

    @Test fun memoryExpires() {
        val now = 1_000_000_000L
        SnowflakeRendezvous.recordWinner("n", SnowflakeRendezvous.Method.NETLIFY, now)
        assertEquals(SnowflakeRendezvous.Method.NETLIFY, SnowflakeRendezvous.winner("n", now + 1))
        assertNull(SnowflakeRendezvous.winner("n", now + SnowflakeRendezvous.MEMORY_MS))
    }

    @Test fun defaultTorProfileUsesTheLadder() {
        val lines = TorSidecar.bridges(TorSidecar.profile())
        assertEquals(SnowflakeRendezvous.lines(SnowflakeRendezvous.current()), lines)
        assertEquals(setOf("snowflake"), TorSidecar.transportsOf(lines))
    }
}
