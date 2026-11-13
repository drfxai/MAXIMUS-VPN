package com.example
import com.example.vpn.connections.ConnectionEngineRunner
import com.example.vpn.engine.ProfileExtras
import com.example.vpn.sidecar.AetherSidecar
import com.example.vpn.sidecar.AetherSettings
import org.junit.Assert.*
import org.junit.Test
class AetherCarrierScopeTest {
    @Test fun udpWireGuardDoesNotInheritMasqueTlsOptions() {
        val options = AetherSettings(ech = true, fingerprint = "firefox")
        for (carrier in listOf("wireguard", "wiw")) {
            val args = options.arguments(carrier, true)
            assertFalse(args.contains("--fragment")); assertFalse(args.contains("--tls-ciphers")); assertFalse(args.contains("--ech"))
        }
        assertTrue(options.arguments("wiw", true).contains("--wiw-scan"))
        assertTrue(options.arguments("mim", true).contains("--mim-scan"))
        assertFalse(options.arguments("h3", true).contains("--fragment"))
        assertTrue(options.arguments("h2", true).contains("--fragment"))
    }
    @Test fun autoCarrierUsesIpv4OnlyWhenSameLinkMeasurementSaysNoIpv6() {
        val original = AetherSidecar.profile("auto", "auto")
        val measured = ConnectionEngineRunner.candidates(original, ipv4Only = true)
        assertEquals(listOf("h2", "h3", "wireguard"), measured.map { ProfileExtras.read(it).optString("carrier") })
        assertTrue(measured.all { ProfileExtras.read(it).optString("family") == "ipv4" })
        val unmeasured = ConnectionEngineRunner.candidates(original)
        assertTrue(unmeasured.all { ProfileExtras.read(it).optString("family") == "auto" })
        val userPinnedIpv6 = ConnectionEngineRunner.candidates(AetherSidecar.profile("auto", "ipv6"), ipv4Only = true)
        assertTrue(userPinnedIpv6.all { ProfileExtras.read(it).optString("family") == "ipv6" })
    }

}
