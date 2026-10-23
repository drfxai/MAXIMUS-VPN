package com.example.vpn.smart

import com.example.data.model.VlessProfile
import com.example.vpn.sidecar.ByedpiSidecar
import org.junit.Assert.*
import org.junit.Test

class FakeSniRecoveryTest {
    private fun source() = VlessProfile(name = "saved", address = "origin.example", port = 443,
        uuid = "existing-credential", security = "tls", sni = "origin.example", transport = "ws", host = "origin.example")
    @Test fun resolvesEndpointButPreservesTlsIdentityAndCredentials() {
        val p = source()
        val variants = FakeSniRecovery.candidates(listOf(p)) { it.copy(address = "1.1.1.1") }
        assertEquals(2, variants.size)
        assertEquals(2, variants.map { it.id }.distinct().size)
        variants.forEach {
            assertEquals(p.uuid, it.uuid); assertEquals(p.sni, it.sni); assertEquals(p.host, it.host)
            assertEquals(p.port, it.port); assertTrue(ByedpiSidecar.handles(it))
            assertNull(it.lastTestedTimestamp); assertNull(ByedpiSidecar.problem(it))
        }
        assertFalse(ByedpiSidecar.handles(p))
    }
    @Test fun insecurePrivateAndAlreadyWrappedProfilesAreExcluded() {
        assertTrue(FakeSniRecovery.candidates(listOf(source().copy(allowInsecure = true))) { error("must not resolve") }.isEmpty())
        assertTrue(FakeSniRecovery.candidates(listOf(source())) { it.copy(address = "127.0.0.1") }.isEmpty())
        val wrapped = ByedpiSidecar.enable(source().copy(address = "1.1.1.1"), "www.microsoft.com", 4)
        assertTrue(FakeSniRecovery.candidates(listOf(wrapped)) { error("must not resolve") }.isEmpty())
    }
    @Test fun atMostTwoOriginsAndFourTestsAreGenerated() {
        val inputs = (1..8).map { source().copy(address = "origin$it.example") }
        var resolved = 0
        assertEquals(4, FakeSniRecovery.candidates(inputs) { resolved++; it.copy(address = "1.1.1.1") }.size)
        assertEquals(2, resolved)
    }
}
