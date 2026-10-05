package com.example.vpn.hub

/**
 * Three snapshots of the configuration list, so a bad or missing update never leaves the app without
 * one: the [current] verified list, the [previous] one it replaced, and the [lastKnownGood] list whose
 * nodes actually carried traffic. A list that fails verification is not stored at all.
 */
data class HubSnapshots(
    val current: Snapshot? = null,
    val previous: Snapshot? = null,
    val lastKnownGood: Snapshot? = null
) {
    data class Snapshot(val created: String, val count: Int, val content: String, val approved: Int = 0)

    /** Records a list that passed signature and hash checks. */
    fun accept(snapshot: Snapshot): HubSnapshots =
        if (snapshot.content == current?.content) copy(current = snapshot)
        else HubSnapshots(current = snapshot, previous = current, lastKnownGood = lastKnownGood)

    /** Records how many of [current]'s nodes carried traffic; from one upward it becomes the known-good list. */
    fun recordApproved(approved: Int): HubSnapshots {
        val now = current ?: return this
        val updated = now.copy(approved = approved)
        return copy(current = updated, lastKnownGood = if (approved > 0) updated else lastKnownGood)
    }

    /** The list to use: the current one, else the last that worked, else the previous one. */
    fun best(): Snapshot? = current ?: lastKnownGood ?: previous
}
