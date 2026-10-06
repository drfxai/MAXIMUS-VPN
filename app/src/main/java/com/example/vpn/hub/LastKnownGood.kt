package com.example.vpn.hub

import com.example.data.model.VlessProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Up to three free configs that carried real traffic on this phone: the one in use and the two best
 * recent backups. A list refresh never deletes them; one leaves the pool only when a newer config has
 * proved itself here (a verified VPN session) and takes its place, or when the phone's own evidence
 * says it is dead. So when the list changes under the user, the configs that worked stay.
 */
class LastKnownGoodPool(
    private val load: () -> String?,
    private val save: (String) -> Unit
) {
    data class Entry(
        val fingerprint: String,
        val profileId: String,
        val lastVerifiedAt: Long,
        val verifiedCount: Int,
        val lastRttMs: Long?
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("f", fingerprint); put("id", profileId); put("t", lastVerifiedAt); put("n", verifiedCount)
            lastRttMs?.let { put("r", it) }
        }

        companion object {
            fun fromJson(o: JSONObject) = Entry(
                o.getString("f"), o.getString("id"), o.optLong("t"), o.optInt("n"),
                if (o.has("r")) o.optLong("r") else null
            )
        }
    }

    private var entries: MutableList<Entry>? = null

    @Synchronized
    private fun list(): MutableList<Entry> = entries ?: runCatching {
        val a = JSONArray(load() ?: "[]")
        (0 until a.length()).map { Entry.fromJson(a.getJSONObject(it)) }.toMutableList()
    }.getOrDefault(mutableListOf()).also { entries = it }

    @Synchronized
    fun entries(): List<Entry> = list().toList()

    /**
     * A free config just carried verified traffic. It joins (or refreshes) the pool; when the pool is
     * full the entry verified longest ago leaves, except [activeFingerprint], which always stays.
     */
    @Synchronized
    fun recordVerified(profile: VlessProfile, rttMs: Long?, now: Long, activeFingerprint: String? = profile.effectiveFingerprint): List<Entry> {
        val all = list()
        val fp = profile.effectiveFingerprint
        val old = all.firstOrNull { it.fingerprint == fp }
        all.removeAll { it.fingerprint == fp }
        all.add(Entry(fp, profile.id, now, (old?.verifiedCount ?: 0) + 1, rttMs ?: old?.lastRttMs))
        trim(all, activeFingerprint)
        persist()
        return all.toList()
    }

    /** Removes entries the phone's evidence shows are dead, unless one is in use. */
    @Synchronized
    fun dropDead(isDead: (String) -> Boolean, activeFingerprint: String?): List<Entry> {
        val all = list()
        val removed = all.filter { it.fingerprint != activeFingerprint && isDead(it.fingerprint) }
        if (removed.isNotEmpty()) {
            all.removeAll(removed.toSet())
            persist()
        }
        return removed
    }

    /** Called when the user deletes every free config: the pool empties except the config in use. */
    @Synchronized
    fun clear(keepFingerprint: String?) {
        list().removeAll { it.fingerprint != keepFingerprint }
        persist()
    }

    /** The profile ids a refresh must keep. */
    @Synchronized
    fun protectedIds(): Set<String> = list().map { it.profileId }.toSet()

    @Synchronized
    fun protectedFingerprints(): Set<String> = list().map { it.fingerprint }.toSet()

    /** A saved config was replaced by its copy in a new list (same fingerprint, new id). */
    @Synchronized
    fun rebind(fingerprint: String, newProfileId: String) {
        val all = list()
        val i = all.indexOfFirst { it.fingerprint == fingerprint }
        if (i >= 0 && all[i].profileId != newProfileId) {
            all[i] = all[i].copy(profileId = newProfileId)
            persist()
        }
    }

    private fun trim(all: MutableList<Entry>, activeFingerprint: String?) {
        while (all.size > SIZE) {
            val victim = all.filter { it.fingerprint != activeFingerprint }.minWithOrNull(
                compareBy<Entry>({ it.lastVerifiedAt }, { it.verifiedCount })
            ) ?: break
            all.remove(victim)
        }
    }

    private fun persist() {
        val a = JSONArray()
        list().forEach { a.put(it.toJson()) }
        runCatching { save(a.toString()) }
    }

    companion object {
        const val SIZE = 3
    }
}

/**
 * Replaces the saved free list with a new one in one step. Computed here, applied in one database
 * transaction by the caller, so the list is never cleared and refilled and the screen never shows an
 * empty or half list.
 */
object FreeListSwap {
    data class Plan(
        /** Saved free configs to delete (none of them protected or in use). */
        val delete: List<VlessProfile>,
        /** New configs to add. */
        val insert: List<VlessProfile>,
        /** Saved configs the new list dropped but that stay: protected, favourite or in use. */
        val retained: List<VlessProfile>,
        /** Saved configs the new list still has (kept as they are, with their test results). */
        val kept: List<VlessProfile>
    ) {
        val resultingCount: Int get() = kept.size + retained.size + insert.size
    }

    /**
     * [saved] are the free configs on the phone, [fresh] the verified new list. [protectedIds] are the
     * last-known-good pool's, [activeId] the config the VPN is using. At most [limit] configs result:
     * retained ones take places first (they are proven here), then kept, then new ones in list order.
     */
    fun plan(
        saved: List<VlessProfile>,
        fresh: List<VlessProfile>,
        protectedIds: Set<String>,
        activeId: String?,
        limit: Int = FreeConfigList.MAX_CONFIGS
    ): Plan {
        val freshKeys = fresh.map { it.effectiveFingerprint }.toSet()
        val savedKeys = saved.map { it.effectiveFingerprint }.toSet()
        val dropped = saved.filter { it.effectiveFingerprint !in freshKeys }
        val retained = dropped.filter { it.id in protectedIds || it.id == activeId || it.isFavorite }
        val keptAll = saved.filter { it.effectiveFingerprint in freshKeys }
        // Kept in the new list's order, so the best of the new list stays when the limit bites.
        val freshOrder = fresh.mapIndexed { i, p -> p.effectiveFingerprint to i }.toMap()
        val keptSorted = keptAll.sortedBy { freshOrder[it.effectiveFingerprint] ?: Int.MAX_VALUE }
        val room = (limit - retained.size).coerceAtLeast(0)
        // Protected and in-use configs among the kept ones always stay; the rest fill the room left.
        val mustKeep = keptSorted.filter { it.id in protectedIds || it.id == activeId || it.isFavorite }
        val kept = (mustKeep + keptSorted.filter { it !in mustKeep }.take((room - mustKeep.size).coerceAtLeast(0))).distinct()
        val overflow = keptSorted.filter { it !in kept }
        val newOnes = fresh.filter { it.effectiveFingerprint !in savedKeys }
            .distinctBy { it.effectiveFingerprint }
            .take((room - kept.size).coerceAtLeast(0))
        val delete = (dropped - retained.toSet()) + overflow
        return Plan(delete = delete, insert = newOnes, retained = retained, kept = kept)
    }
}

/**
 * Free configs a refresh dropped but kept because they were in use or protected at the time. They are
 * removed after the VPN disconnects (SubscriptionManager.releaseRetained), unless still protected.
 */
class RetainedFreeConfigs(private val load: () -> Set<String>, private val save: (Set<String>) -> Unit) {
    @Synchronized
    fun ids(): Set<String> = runCatching { load() }.getOrDefault(emptySet())

    @Synchronized
    fun update(retained: List<String>, gone: List<String>) {
        val next = (ids() - gone.toSet()) + retained
        runCatching { save(next) }
    }
}
