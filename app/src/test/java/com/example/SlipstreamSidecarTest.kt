package com.example

import com.example.data.model.AppSettings
import com.example.data.model.OperationalMode
import com.example.data.model.VlessProfile
import com.example.vpn.sidecar.SidecarContext
import com.example.vpn.sidecar.SlipstreamSidecar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.Base64

class SlipstreamSidecarTest {
    private val pem = "-----BEGIN CERTIFICATE-----\nMIIBhjCCASygAwIBAgIUtest\n-----END CERTIFICATE-----\n"

    private fun refused(link: String): String {
        try {
            SlipstreamSidecar.parse(link)
        } catch (e: IllegalArgumentException) {
            return e.message!!
        }
        fail("expected $link to be refused")
        error("unreachable")
    }

    @Test
    fun parsesALinkAndBuildsTheCommandLine() {
        val profile = SlipstreamSidecar.parse("slipstream://t.example.com?udp=1.1.1.1&cc=bbr#My%20tunnel")
        val engine = SlipstreamSidecar()
        assertTrue(engine.handles(profile))
        assertNull(engine.problem(profile))
        assertEquals("My tunnel", profile.name)
        assertEquals("t.example.com", profile.address)

        val s = SlipstreamSidecar.settings(profile)
        val command = SlipstreamSidecar.command(s, "/data/app/libslipstream.so", 41000, null)
        assertEquals(
            listOf(
                "/data/app/libslipstream.so", "--tcp-listen-host", "127.0.0.1",
                "--tcp-listen-port", "41000", "--resolver", "1.1.1.1",
                "--domain", "t.example.com", "--congestion-control", "bbr"
            ),
            command
        )
        assertEquals("slipstream", engine.id)
        assertEquals("slipstream", engine.binary)
    }

    @Test
    fun severalResolversAreKeptInOrderAndPassedEachWithTheirOwnFlag() {
        val s = SlipstreamSidecar.settings(
            SlipstreamSidecar.parse("slipstream://t.example.com?udp=1.1.1.1&udp=8.8.8.8:5353&udp=9.9.9.9")
        )
        assertEquals(listOf("1.1.1.1", "8.8.8.8:5353", "9.9.9.9"), s.resolvers)
        val command = SlipstreamSidecar.command(s, "slip", 1, null)
        assertEquals(3, command.count { it == "--resolver" })
    }

    @Test
    fun theDefaultCongestionControlIsDcubic() {
        val s = SlipstreamSidecar.settings(SlipstreamSidecar.parse("slipstream://t.example.com?udp=1.1.1.1"))
        assertEquals("dcubic", s.congestionControl)
        assertFalse(s.verified)
    }

    @Test
    fun aPinnedCertificateSurvivesTheLinkRoundTripAndIsWrittenForTheCommand() {
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(pem.toByteArray())
        val profile = SlipstreamSidecar.parse("slipstream://t.example.com?udp=1.1.1.1&cert=$encoded#Pinned")
        val s = SlipstreamSidecar.settings(profile)
        assertTrue(s.verified)
        assertTrue(s.certPem.contains("BEGIN CERTIFICATE"))

        // The link it produces carries the certificate back out unchanged.
        val again = SlipstreamSidecar.settings(SlipstreamSidecar.parse(SlipstreamSidecar.toLink(profile)))
        assertEquals(s.certPem, again.certPem)

        val dir = File(System.getProperty("java.io.tmpdir"))
        val context = SidecarContext(dir, File(dir, "libslipstream.so"), 42000, "u", "p", OperationalMode.DAILY)
        val launch = SlipstreamSidecar().prepare(profile, AppSettings(), context)
        // The last two command arguments pin the written certificate file.
        assertEquals("--cert", launch.command[launch.command.size - 2])
        assertEquals(s.certPem, File(dir, SlipstreamSidecar.CERT_FILE).readText())
        assertFalse(launch.socksAuth)
    }

    @Test
    fun hostNamesAndBadValuesAreRefused() {
        assertTrue(refused("slipstream://t.example.com?udp=resolver.example.com").contains("IP address"))
        assertTrue(refused("slipstream://not_a_domain?udp=1.1.1.1").contains("valid domain"))
        assertTrue(refused("slipstream://t.example.com?udp=1.1.1.1&cc=reno").contains("bbr or dcubic"))
        assertTrue(refused("dnstt://whatever").contains("Not a slipstream"))
    }
}
