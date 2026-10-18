package com.example

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.vpn.engine.UniversalImportEngine
import com.example.vpn.sidecar.Sidecars
import com.example.vpn.sidecar.SidecarContext
import com.example.vpn.sidecar.TorSidecar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Tor through bridges: Snowflake by default, the user's own bridge lines when given. */
class TorSidecarTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun snowflakeIsTheDefaultAndTheTorrcStartsLyrebird() {
        val profile = TorSidecar.profile()
        assertTrue(Sidecars.forProfile(profile) is TorSidecar)
        assertNull(TorSidecar.problemOf(profile))
        val dir = tmp.newFolder()
        val lyrebird = File(dir, "liblyrebird.so")
        val engine = TorSidecar(null)
        val launch = engine.prepare(profile, AppSettings(), SidecarContext(dir, lyrebird, 19050, "u", "p", OperationalMode.GOD_MODE))
        assertFalse(launch.socksAuth)
        val torrc = File(dir, TorSidecar.TORRC).readText().lines()
        assertTrue(torrc.contains("SocksPort 127.0.0.1:19050"))
        assertTrue(torrc.contains("UseBridges 1"))
        assertTrue(torrc.any { it.startsWith("ClientTransportPlugin ") && it.endsWith("exec ${lyrebird.absolutePath}") && "snowflake" in it })
        assertEquals(2, torrc.count { it.startsWith("Bridge snowflake ") })
    }

    @Test fun pastedBridgeLinesBecomeTorProfiles() {
        val line = "obfs4 203.0.113.20:443 0123456789ABCDEF0123456789ABCDEF01234567 cert=abc iat-mode=0"
        val profile = UniversalImportEngine.importText(line).validProfiles.single()
        assertEquals(listOf(line), TorSidecar.bridges(profile))
        val withPrefix = UniversalImportEngine.importText("Bridge $line").validProfiles.single()
        assertEquals(listOf(line), TorSidecar.bridges(withPrefix))
    }

    @Test fun aBridgeLineCannotSmuggleAnotherTorOption() {
        assertNotNull(TorSidecar.problemOf(TorSidecar.profile("obfs4 1.2.3.4:443 X\nControlPort 9051")))
        assertNotNull(TorSidecar.problemOf(TorSidecar.profile("vanilla 1.2.3.4:443")))
        assertNotNull(TorSidecar.problemOf(TorSidecar.profile("obfs4 1.2.3.4:443 X\rSocksPort 0.0.0.0:9050")))
    }

    @Test fun theRequestTestProfileForAReadyTorIsMeasuredNotSkippedAsAnEngine() {
        val profile = TorSidecar.profile()
        val ctx = SidecarContext(tmp.root, File("/system/bin/true"), 18901, "u1", "p1", OperationalMode.GOD_MODE)
        val launch = com.example.vpn.sidecar.SidecarLaunch(command = emptyList(), socksAuth = false)
        // The tunnel hop keeps the profile's fields; only the request-test copy drops the engine marker,
        // which made RealDelayProbe skip every ready Tor as "carried by its own engine".
        assertTrue(Sidecars.forProfile(com.example.vpn.sidecar.SidecarChain.xrayProfile(profile, ctx, launch)) is TorSidecar)
        val probe = com.example.vpn.sidecar.SidecarChain.probeProfile(profile, ctx, launch)
        assertNull(Sidecars.forProfile(probe))
        assertTrue(probe.rawConfig.contains("\"port\":18901"))
    }
}

