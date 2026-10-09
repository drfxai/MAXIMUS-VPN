package com.example.vpn.lab

import com.example.vpn.connectivity.RecoveryLedger
import org.json.JSONArray
import org.json.JSONObject

/**
 * One change the config optimizer made, or refused to make, to how a saved config is used on one network.
 * The saved config itself is never edited: a transaction only decides whether a derived copy is tried first,
 * through the recovery ledger, and rolling back means the original config is used again exactly as saved.
 *
 * Lifecycle: PROPOSED → REFUSED (a security or mutation check said no; nothing changed)
 *                     → STAGED (tried first on the next connect; verified by LAB only so far)
 *                     → COMMITTED (it then carried real traffic) → ROLLED_BACK / EXPIRED.
 * A STAGED copy that fails in real use is ROLLED_BACK by the ledger's own rule.
 *
 * Only the names of changed fields are kept ([changedFields]), never their values; [endpoint] is the same
 * validated edge key the recovery ledger already stores.
 */
data class ConfigTransaction(
    val id: String,
    val parentFingerprint: String,
    val profileKey: String,
    val network: String?,
    val endpoint: String?,
    val changedFields: List<String>,
    /** Why it was proposed: the verified profile and its evidence, in words. */
    val reason: String,
    val state: State,
    val createdAt: Long,
    val updatedAt: Long,
    /** What decided the last state change (a refusal reason, a rollback reason). */
    val note: String = ""
) {
    enum class State(val terminal: Boolean) { PROPOSED(false), REFUSED(true), STAGED(false), COMMITTED(false), ROLLED_BACK(true), EXPIRED(true) }

    /** Rollback is always the same and always available: use the saved config unchanged. */
    val rollback: String get() = "use the saved config unchanged"

    fun toJson(): JSONObject = JSONObject().put("id", id).put("f", parentFingerprint).put("k", profileKey)
        .put("n", network ?: JSONObject.NULL).put("e", endpoint ?: JSONObject.NULL)
        .put("c", JSONArray(changedFields)).put("r", reason).put("s", state.name).put("t0", createdAt).put("t1", updatedAt).put("no", note)

    companion object {
        fun fromJson(o: JSONObject): ConfigTransaction? = runCatching {
            ConfigTransaction(
                o.getString("id"), o.getString("f"), o.getString("k"), if (o.isNull("n")) null else o.optString("n"),
                if (o.isNull("e")) null else o.optString("e"),
                o.optJSONArray("c")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
                o.optString("r"), State.valueOf(o.getString("s")), o.optLong("t0"), o.optLong("t1"), o.optString("no")
            )
        }.getOrNull()
    }
}

/** Pure transitions for [ConfigTransaction]; the controller calls these and stores the result. */
object ConfigOptimizer {

    fun propose(
        id: String, parentFingerprint: String, profileKey: String, network: String?, endpoint: String?,
        changedFields: List<String>, reason: String, now: Long
    ) = ConfigTransaction(id, parentFingerprint, profileKey, network, endpoint, changedFields, reason, ConfigTransaction.State.PROPOSED, now, now)

    /** The checks ran: [refusal] null means every check passed and the copy is staged. */
    fun validate(tx: ConfigTransaction, refusal: String?, now: Long): ConfigTransaction {
        require(tx.state == ConfigTransaction.State.PROPOSED) { "only a proposed change can be validated" }
        return if (refusal != null) tx.copy(state = ConfigTransaction.State.REFUSED, updatedAt = now, note = refusal)
        else tx.copy(state = ConfigTransaction.State.STAGED, updatedAt = now, note = "Tried first on the next connect on this network.")
    }

    /**
     * Brings a staged or committed transaction in line with what the ledger saw in real use: a withdrawn
     * entry is a rollback, a success after staging commits it, and a missing or expired entry expires it.
     */
    fun reconcile(tx: ConfigTransaction, entry: RecoveryLedger.Entry?, now: Long): ConfigTransaction {
        if (tx.state.terminal || tx.state == ConfigTransaction.State.PROPOSED) return tx
        return when {
            entry == null || entry.expiresAt <= now -> tx.copy(state = ConfigTransaction.State.EXPIRED, updatedAt = now, note = "Its time on this network ran out.")
            entry.withdrawn -> tx.copy(state = ConfigTransaction.State.ROLLED_BACK, updatedAt = now,
                note = "Rolled back: ${entry.withdrawnReason ?: "it stopped working"}.")
            tx.state == ConfigTransaction.State.STAGED && (entry.lastSuccessAt ?: 0L) > tx.updatedAt ->
                tx.copy(state = ConfigTransaction.State.COMMITTED, updatedAt = now, note = "Carried real traffic after staging.")
            else -> tx
        }
    }

    fun matches(tx: ConfigTransaction, e: RecoveryLedger.Entry) =
        e.parentFingerprint == tx.parentFingerprint && e.profileKey == tx.profileKey && e.endpoint == tx.endpoint && e.network == tx.network
}
