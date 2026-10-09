package com.example.vpn.lab

import com.example.vpn.connectivity.RecoveryLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigTransactionTest {
    private val t0 = 1_000_000L
    private fun proposed() = ConfigOptimizer.propose("TX-001", "fp", "fragment@1", "cell:43235", "edge-1", listOf("finalMask"), "Fragment verified", t0)
    private fun entry(
        successes: Int = 1, lastSuccessAt: Long? = t0, withdrawn: Boolean = false, reason: String? = null, expiresAt: Long = t0 + 86_400_000L
    ) = RecoveryLedger.Entry("fp", "fragment@1", "edge-1", "cell:43235", t0, expiresAt, successes = successes, lastSuccessAt = lastSuccessAt,
        withdrawn = withdrawn, withdrawnReason = reason)

    @Test fun refusedChangeNeverStages() {
        val tx = ConfigOptimizer.validate(proposed(), "Refused by the security gate.", t0)
        assertEquals(ConfigTransaction.State.REFUSED, tx.state)
        assertTrue(tx.state.terminal)
        // A refused transaction is final whatever the ledger says.
        assertEquals(tx, ConfigOptimizer.reconcile(tx, entry(successes = 5, lastSuccessAt = t0 + 10), t0 + 20))
    }

    @Test fun stagedCommitsOnlyAfterRealTrafficLater() {
        val staged = ConfigOptimizer.validate(proposed(), null, t0)
        assertEquals(ConfigTransaction.State.STAGED, staged.state)
        // The LAB verification itself (recorded at staging time) does not commit it.
        assertEquals(ConfigTransaction.State.STAGED, ConfigOptimizer.reconcile(staged, entry(), t0 + 5).state)
        assertEquals(ConfigTransaction.State.COMMITTED, ConfigOptimizer.reconcile(staged, entry(successes = 2, lastSuccessAt = t0 + 5), t0 + 6).state)
    }

    @Test fun withdrawnEntryRollsBackToTheSavedConfig() {
        val committed = ConfigOptimizer.reconcile(ConfigOptimizer.validate(proposed(), null, t0), entry(successes = 2, lastSuccessAt = t0 + 5), t0 + 6)
        val back = ConfigOptimizer.reconcile(committed, entry(withdrawn = true, reason = "failed 2 times after working; back to the original"), t0 + 9)
        assertEquals(ConfigTransaction.State.ROLLED_BACK, back.state)
        assertTrue(back.note.contains("failed 2 times"))
        assertEquals("use the saved config unchanged", back.rollback)
    }

    @Test fun missingOrExpiredEntryExpires() {
        val staged = ConfigOptimizer.validate(proposed(), null, t0)
        assertEquals(ConfigTransaction.State.EXPIRED, ConfigOptimizer.reconcile(staged, null, t0 + 1).state)
        assertEquals(ConfigTransaction.State.EXPIRED, ConfigOptimizer.reconcile(staged, entry(expiresAt = t0 + 1), t0 + 2).state)
    }

    @Test fun onlyProposedCanBeValidated() {
        val staged = ConfigOptimizer.validate(proposed(), null, t0)
        assertFalse(runCatching { ConfigOptimizer.validate(staged, null, t0) }.isSuccess)
    }

    @Test fun survivesRestartAndReconciles() {
        var saved: String? = null
        val store = LabStore({ saved }, { saved = it })
        val tx = ConfigOptimizer.validate(proposed(), null, t0)
        store.saveTransaction(tx)
        val reloaded = LabStore({ saved }, { saved = it })
        assertEquals(listOf(tx), reloaded.transactions())
        val changed = reloaded.reconcileTransactions { ConfigOptimizer.reconcile(it, null, t0 + 1) }
        assertEquals(ConfigTransaction.State.EXPIRED, changed.single().state)
    }
}
