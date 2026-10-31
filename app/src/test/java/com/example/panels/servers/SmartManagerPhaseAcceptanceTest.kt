package com.example.panels.servers

import com.example.data.model.VlessProfile
import com.example.panels.cloudflare.CloudflareRoutePolicy
import com.example.vpn.sidecar.PsiphonSidecar
import com.example.vpn.smart.ManagedNodeEligibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class SmartManagerPhaseAcceptanceTest {
    private val now = 1_800_000_000_000L
    private val facts = ServerFacts(root = true, systemd = true, arch = "x86_64",
        socketInspectionAvailable = true, diskMb = 16384, diskFreeMb = 12000,
        ramMb = 2048, ramAvailableMb = 1024, services = mapOf("maximus-psiphon" to "active"))

    @Test fun serializedInstallRequiresFlockAndNonWorldWritableHomeDir() {
        val wrapper = InstallerShellLock.wrap("echo testing")
        assertTrue(wrapper.contains("flock -x -w 10 9"))
        assertTrue(wrapper.contains("umask 077"))
        assertTrue(wrapper.contains("Unsafe Maximus installer lock"))
        assertTrue(wrapper.contains("echo testing"))
    }

    @Test fun privatePsiphonGeneratorUsesPinnedBinaryAndNeverOverwritesExistingUnit() {
        val spec = PrivatePsiphonServerScripts.Request(
            "203.0.113.15", 9999, "/tmp/maximus-psiphond-${"a".repeat(32)}.bin", "c".repeat(64))
        val script = PrivatePsiphonServerScripts.install(spec).text
        assertTrue(script.contains("sha256sum"))
        assertTrue(script.contains("-protocol 'OSSH:9999' generate"))
        assertTrue(script.contains("maximus-psiphon.service"))
        assertTrue(script.contains("systemctl is-active --quiet"))
        assertTrue(script.contains("Existing Psiphon installation"))
        assertFalse(script.contains("##RESULT SERVER_ENTRY="))
    }

    @Test fun sharedDnsMustUseDistinctNonOverlappingNamesAndLoopbackOnly() {
        val routes = listOf(
            DnsGatewayCompatibility.Route(ServerToolCatalog.DNSTT, "d.example.com", "127.0.0.1:5300"),
            DnsGatewayCompatibility.Route(ServerToolCatalog.SOAR, "s.example.com", "127.0.0.1:5301"))
        assertTrue(DnsGatewayCompatibility.plan(routes, facts, now, now).eligibleForPrototype)
        assertEquals(ServerToolCatalog.DNSTT,
            DnsGatewayCompatibility.match("x.y.d.example.com", routes)?.engine)
        assertFalse(DnsGatewayCompatibility.plan(
            routes + DnsGatewayCompatibility.Route(ServerToolCatalog.COTTEN,
                "example.com", "127.0.0.1:5302"), facts, now, now).eligibleForPrototype)
        assertFalse(DnsGatewayCompatibility.plan(
            routes, facts.copy(udpPorts = setOf(53)), now, now).eligibleForPrototype)
        assertFalse(DnsGatewayCompatibility.plan(
            listOf(DnsGatewayCompatibility.Route(ServerToolCatalog.SOAR,
                "s.example.com", "198.51.100.7:53")), facts, now, now).eligibleForPrototype)
    }

    @Test fun CloudflareMustKeepPrivateTransportAndDnsTunnelOutsideHttpProxy() {
        val transport = CloudflareRoutePolicy.Transport.PSIPHON_OSSH
        assertFalse(CloudflareRoutePolicy.assess(transport, 9999, userApproved = true).proxied)
        assertTrue(CloudflareRoutePolicy.assess(transport, 9999, userApproved = true).eligible)
        assertFalse(CloudflareRoutePolicy.assess(
            CloudflareRoutePolicy.Transport.DNSTT, 53, true).proxied)
        assertFalse(CloudflareRoutePolicy.assess(
            CloudflareRoutePolicy.Transport.CDN_HTTPS_GRPC, 443, false).eligible)
        assertTrue(CloudflareRoutePolicy.assess(
            CloudflareRoutePolicy.Transport.CDN_HTTPS_WEBSOCKET, 443, true).proxied)
        assertFalse(CloudflareRoutePolicy.assess(
            CloudflareRoutePolicy.Transport.CDN_HTTPS_WEBSOCKET, 9999, true).eligible)
    }

    @Test fun privatePsiphonRequiresFreshMeasuredTrafficBeforeAutomaticSelection() {
        val id = UUID.randomUUID().toString()
        val profile = PsiphonSidecar.privateProfile(id, "sample", "203.0.113.15")
        assertFalse(ManagedNodeEligibility.allowed(profile, emptyList(), now))
        val managed = ManagedNodeRecord("vps", PrivatePsiphonServerScripts.TOOL_ID, profile.id,
            ManagedNodeRecord.State.CONFIGURED, now)
        assertFalse(ManagedNodeEligibility.allowed(profile, listOf(managed), now))
        assertTrue(ManagedNodeEligibility.allowed(profile,
            listOf(managed.copy(state = ManagedNodeRecord.State.AVAILABLE)), now))
        assertFalse(ManagedNodeEligibility.allowed(profile,
            listOf(managed.copy(state = ManagedNodeRecord.State.AVAILABLE,
                updatedAt = now - ManagedNodeEligibility.MAX_EVIDENCE_AGE_MS - 1)), now))
        val wifiVerified = managed.copy(state = ManagedNodeRecord.State.AVAILABLE,
            networkKey = "wifi")
        assertTrue(ManagedNodeEligibility.allowed(profile, listOf(wifiVerified), now,
            networkKey = "wifi"))
        assertFalse(ManagedNodeEligibility.allowed(profile, listOf(wifiVerified), now,
            networkKey = "cell:43235"))
        assertFalse(ManagedNodeEligibility.allowed(profile, listOf(wifiVerified), now,
            networkKey = ""))
        val manuallyAdded = VlessProfile(name = "manual", address = "203.0.113.30", port = 443, uuid = "test")
        assertTrue(ManagedNodeEligibility.allowed(manuallyAdded, emptyList(), now))
    }

    @Test fun activeSystemdIsNotTreatedAsVerifiedVpn() {
        val server = ManagedServer(name = "Test", host = "203.0.113.15",
            location = ServerLocation.ABROAD, checkedAt = now,
            facts = facts, tools = listOf(InstalledTool(PrivatePsiphonServerScripts.TOOL_ID)))
        val state = SmartServerHealth.evaluate(server, now)
        assertEquals(SmartServerHealth.Level.SERVICES_ACTIVE_UNVERIFIED, state.level)
        assertTrue(state.concerns.single().contains("not verified"))
        assertEquals(SmartServerHealth.Level.SERVICE_FAILURE,
            SmartServerHealth.evaluate(server.copy(
                facts = facts.copy(services = mapOf("maximus-psiphon" to "failed"))), now).level)
    }
}
