package com.example
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
}
