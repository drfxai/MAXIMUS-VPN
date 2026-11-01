package com.example.panels.servers

import android.content.Context
import com.example.data.security.SecureStorage
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Minimal durable journal for user-approved server mutations. Each entry contains only identifiers,
 * coarse state and timestamps; passwords, command lines, DNS tokens and configuration never belong
 * in this store. The journal shares the existing Android Keystore-backed SecureStorage.
 *
 * An operation interrupted in RUNNING/VERIFYING remains UNCERTAIN when the app restarts. It MUST NOT
 * be replayed, rolled back or re-approved automatically; SSH inspection and explicit user review are
 * required to reconcile effects already applied to the remote host.
 */
data class SmartOperation(
    val id: String,
    val serverId: String,
    val toolId: String,
    val status: Status,
    val startedAt: Long,
    val updatedAt: Long,
    val preflightAt: Long
) {
    enum class Status { RUNNING, VERIFYING, COMPLETED, FAILED, UNCERTAIN, RECONCILED }

    val actionable: Boolean get() = status == Status.UNCERTAIN || status == Status.FAILED
}

/** Minimal persistence port so the state machine is testable without Android Keystore. */
interface OperationJournalPersistence {
    fun load(): String
    fun save(raw: String)
}

private class EncryptedOperationJournal(context: Context) : OperationJournalPersistence {
    private val secure = SecureStorage(context.applicationContext)
    override fun load(): String = secure.getAndDecrypt("smart_manager_operation_journal_v1", "[]")
    override fun save(raw: String) = secure.encryptAndSave("smart_manager_operation_journal_v1", raw)
}

class SmartOperationStore(private val persistence: OperationJournalPersistence) {
    constructor(context: Context) : this(EncryptedOperationJournal(context))

    fun all(): List<SmartOperation> = synchronized(JOURNAL_LOCK) { decode(persistence.load()) }

    /** Start only after the existing installation wizard has obtained user approval. */
    fun begin(serverId: String, toolId: String, inspectedAt: Long, now: Long = System.currentTimeMillis()): SmartOperation =
        synchronized(JOURNAL_LOCK) {
        require(serverId.isNotBlank() && toolId.isNotBlank()) { "Missing operation identity" }
        require(inspectedAt > 0 && now >= inspectedAt && now - inspectedAt <= INSPECTION_TTL_MS) {
            "A fresh server inspection is required before a mutation"
        }
        val list = all()
        check(list.none { it.serverId == serverId && it.status in setOf(
            SmartOperation.Status.RUNNING, SmartOperation.Status.VERIFYING, SmartOperation.Status.UNCERTAIN
        ) }) { "An unfinished server operation needs reconciliation before starting another" }
        val item = SmartOperation(UUID.randomUUID().toString(), serverId, toolId,
            SmartOperation.Status.RUNNING, now, now, inspectedAt)
        persist((list + item).takeLast(MAX_ENTRIES))
        item
    }

    fun transition(id: String, status: SmartOperation.Status, now: Long = System.currentTimeMillis()): SmartOperation =
        synchronized(JOURNAL_LOCK) {
        val list = all()
        val current = requireNotNull(list.firstOrNull { it.id == id }) { "Unknown operation" }
        val permitted = when (current.status) {
            SmartOperation.Status.RUNNING -> setOf(SmartOperation.Status.VERIFYING, SmartOperation.Status.FAILED, SmartOperation.Status.UNCERTAIN)
            SmartOperation.Status.VERIFYING -> setOf(SmartOperation.Status.COMPLETED, SmartOperation.Status.FAILED, SmartOperation.Status.UNCERTAIN)
            SmartOperation.Status.UNCERTAIN -> setOf(SmartOperation.Status.RECONCILED)
            SmartOperation.Status.COMPLETED, SmartOperation.Status.FAILED, SmartOperation.Status.RECONCILED -> emptySet()
        }
        check(status in permitted) { "Invalid operation transition: ${current.status} -> $status" }
        val changed = current.copy(status = status, updatedAt = now)
        persist(list.map { if (it.id == id) changed else it })
        changed
    }

    /**
     * Called during initialization after process recreation. No remote commands are executed.
     * A previously interrupted operation becomes UNCERTAIN until an operator reconciles it.
     */
    fun markInterrupted(now: Long = System.currentTimeMillis()): List<SmartOperation> =
        synchronized(JOURNAL_LOCK) {
        val list = all()
        val changed = list.map {
            if (it.status in setOf(SmartOperation.Status.RUNNING, SmartOperation.Status.VERIFYING))
                it.copy(status = SmartOperation.Status.UNCERTAIN, updatedAt = now)
            else it
        }
        if (changed != list) persist(changed)
        return changed.filter { it.status == SmartOperation.Status.UNCERTAIN }
    }

    private fun persist(list: List<SmartOperation>) {
        persistence.save(JSONArray().apply {
            list.forEach { op -> put(JSONObject()
                .put("id", op.id).put("serverId", op.serverId).put("toolId", op.toolId)
                .put("status", op.status.name).put("startedAt", op.startedAt)
                .put("updatedAt", op.updatedAt).put("preflightAt", op.preflightAt)) }
        }.toString())
    }

    companion object {
        private val JOURNAL_LOCK = Any()
        private const val MAX_ENTRIES = 100

        /** Unknown or corrupted data may never be promoted to an installed/verified state. */
        fun decode(raw: String): List<SmartOperation> {
            val values = JSONArray(raw) // malformed persisted state must fail closed
            return (0 until values.length()).map { index ->
                    val v = values.getJSONObject(index)
                    SmartOperation(
                        id = v.getString("id"), serverId = v.getString("serverId"),
                        toolId = v.getString("toolId"),
                        status = SmartOperation.Status.valueOf(v.getString("status")),
                        startedAt = v.getLong("startedAt"), updatedAt = v.getLong("updatedAt"),
                        preflightAt = v.getLong("preflightAt")
                    )
            }
        }
    }
}
