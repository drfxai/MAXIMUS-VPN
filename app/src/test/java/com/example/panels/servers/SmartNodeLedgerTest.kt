package com.example.panels.servers

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SmartNodeLedgerTest {
    @Test fun decodeKeepsOnlyExplicitMeasuredAvailability() {
        val raw = JSONArray().put(JSONObject()
            .put("serverId", "test-vps").put("serviceId", ServerToolCatalog.SOAR)
            .put("profileId", "test-profile").put("state", "CONFIGURED")
            .put("updatedAt", 123L)).toString()
        val record = SmartNodeStore.decode(raw).single()
        assertEquals(ManagedNodeRecord.State.CONFIGURED, record.state)
        assertEquals("test-vps", record.serverId)
    }

    @Test fun malformedStateNeverDefaultsToAvailable() {
        val bad = JSONArray().put(JSONObject()
            .put("serverId", "a").put("serviceId", "b")
            .put("profileId", "c").put("state", "VERIFIED_BY_SSH")
            .put("updatedAt", 1L)).toString()
        assertThrows(IllegalArgumentException::class.java) { SmartNodeStore.decode(bad) }
    }
}
