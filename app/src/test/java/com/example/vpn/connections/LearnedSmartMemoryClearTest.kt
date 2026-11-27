package com.example.vpn.connections

import com.example.vpn.smartsni.SmartSni
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Build 181 Phase 6 (Phase 5 gap): Settings → "Learned network data" → Clear also forgets Smart SNI, Smart ECH, the
 * clean-IP scanner, the Aether MASQUE name and the personal server's ECH cooldown, and nothing else.
 */
class LearnedSmartMemoryClearTest {
    private val store = linkedMapOf<String, String>()

    @After fun tearDown() {
        BuiltinMethodMemory.read = { null }
        BuiltinMethodMemory.write = { _, _ -> }
        BuiltinMethodMemory.keys = { emptySet() }
    }

    @Test fun clearRemovesEverySmartPrefixAndKeepsVerifiedMethods() {
        BuiltinMethodMemory.read = { store[it] }
        BuiltinMethodMemory.write = { k, v -> if (v == null) store.remove(k) else store[k] = v }
        BuiltinMethodMemory.keys = { store.keys.toSet() }
        SmartSni.recordSuccess("wifi-1", "qwertyuiopas.com", 1)
        store["smartEch#wifi-1"] = "{}"
        store["smartIp#wifi-1"] = "{}"
        store["aetherMasqueSni#wifi-1"] = "x"
        store["personalEch#s1#wifi-1"] = "{\"fail\":9}"
        store["wifi-1"] = "config-free-aether=1" // verified built-in methods: not part of this store's smart memory
        assertEquals(5, BuiltinMethodMemory.clearPrefixes())
        assertEquals(setOf("wifi-1"), store.keys)
        assertTrue(SmartSni.ranked("wifi-1", 2).isEmpty())
    }
}
