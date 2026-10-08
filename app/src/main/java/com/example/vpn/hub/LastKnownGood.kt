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
     * last-known-good pool's and the ones in use, [activeId] the config the VPN is using. At most [limit]
     * configs result, in this order of claim: the one in use, the protected ones, favourites, the saved
     * configs the new list still has (in its order), then new ones in list order. Only the one in use and
     * the protected ones (a handful: the pool holds three) may never be deleted, so a long list of starred
     * free configs cannot push the free list past [limit].
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
        val freshOrder = fresh.mapIndexed { i, p -> p.effectiveFingerprint to i }.toMap()
        fun inList(p: VlessProfile) = p.effectiveFingerprint in freshKeys
        fun order(p: VlessProfile) = freshOrder[p.effectiveFingerprint] ?: Int.MAX_VALUE
        val stay = mutableListOf<VlessProfile>()
        // Never deleted: the config in use, then the protected ones.
        saved.filter { it.id == activeId }.forEach { stay += it }
        saved.filter { it.id in protectedIds && it !in stay }.sortedBy(::order).forEach { stay += it }
        // Then, while there is room: favourites, then the saved configs the new list still has.
        val optional = saved.filter { it !in stay && it.isFavorite }.sortedWith(compareBy({ !inList(it) }, ::order)) +
            saved.filter { it !in stay && !it.isFavorite && inList(it) }.sortedBy(::order)
        optional.forEach { if (stay.size < limit) stay += it }
        val newOnes = fresh.filter { it.effectiveFingerprint !in savedKeys }
            .distinctBy { it.effectiveFingerprint }
            .take((limit - stay.size).coerceAtLeast(0))
        val delete = saved.filter { it !in stay }
        return Plan(delete = delete, insert = newOnes, retained = stay.filter { !inList(it) }, kept = stay.filter(::inList))
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
