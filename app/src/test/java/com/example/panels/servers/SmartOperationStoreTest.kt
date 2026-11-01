package com.example.panels.servers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** State-machine tests run without Android context or a live server. */
class SmartOperationStoreTest {
    private class InMemoryJournal : OperationJournalPersistence {
        var data: String = "[]"
        override fun load() = data
        override fun save(raw: String) { data = raw }
    }

    @Test fun beforeMutationFailureDoesNotLockFutureInstall() {
        val backend = InMemoryJournal()
        val first = SmartOperationStore(backend).begin("vps", "dnstt", 1000L, 1001L)
        assertEquals(SmartOperation.Status.FAILED,
            SmartOperationStore(backend).transition(first.id, SmartOperation.Status.FAILED, 1002L).status)
        assertEquals(SmartOperation.Status.RUNNING,
            SmartOperationStore(backend).begin("vps", "soar-server", 1002L, 1003L).status)
    }

    @Test fun unknownRemoteOutcomeBlocksUntilManualReconciliation() {
        val backend = InMemoryJournal()
        val store = SmartOperationStore(backend)
        val started = store.begin("vps", "dnstt", 1000L, 1002L)
        store.transition(started.id, SmartOperation.Status.UNCERTAIN, 1003L)
        assertThrows(IllegalStateException::class.java) {
            SmartOperationStore(backend).begin("vps", "soar-server", 1004L, 1005L)
        }
        assertThrows(IllegalStateException::class.java) {
            store.transition(started.id, SmartOperation.Status.COMPLETED, 1006L)
        }
        store.transition(started.id, SmartOperation.Status.RECONCILED, 1007L)
        assertEquals(SmartOperation.Status.RUNNING,
            store.begin("vps", "soar-server", 1007L, 1008L).status)
    }

    @Test fun interruptedOperationRemainsUncertainAfterRecreatingStore() {
        val backend = InMemoryJournal()
        val first = SmartOperationStore(backend).begin("vps", "dnstt", 1000L, 1001L)
        val recreated = SmartOperationStore(backend)
        assertEquals(SmartOperation.Status.UNCERTAIN,
            recreated.markInterrupted(1002L).single().status)
        assertEquals(SmartOperation.Status.UNCERTAIN, recreated.all().single().status)
        assertTrue(recreated.all().single().id == first.id)
    }

    @Test fun staleInspectionFailsBeforeAnyJournalWrite() {
        val backend = InMemoryJournal()
        assertThrows(IllegalArgumentException::class.java) {
            SmartOperationStore(backend).begin("vps", "dnstt", 1L, INSPECTION_TTL_MS + 2L)
        }
        assertEquals("[]", backend.data)
    }
}
