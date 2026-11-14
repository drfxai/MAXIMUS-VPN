package com.example.vpn.smart

/**
 * Smart Connect skips servers that failed a real request on this network moments ago
 * ([com.example.vpn.connectivity.PathEligibility.RECENTLY_FAILED]). When that would leave fewer than [MIN_CANDIDATES]
 * to test, the oldest-skipped ones come back (servers not frozen here first), so a Connect never ends up testing
 * nothing: build 145 on Irancell "Skipped 28 servers that failed here moments ago" then "Testing 0 other servers".
 * Pure.
 */
object PoolRefill {
    const val MIN_CANDIDATES = 4

    data class Result<T>(val candidates: List<T>, val skipped: Int, val readmitted: Int)

    /**
     * [ordered] in test order; [failedAt] is when an item failed moments ago (null: not held back); [frozen] tells
     * the servers frozen on this network. Keeps [ordered]'s order.
     */
    fun <T> refill(ordered: List<T>, failedAt: (T) -> Long?, frozen: (T) -> Boolean, min: Int = MIN_CANDIDATES): Result<T> {
        val held = ordered.mapNotNull { item -> failedAt(item)?.let { item to it } }
        val freshCount = ordered.size - held.size
        val need = (min - freshCount).coerceAtLeast(0)
        val back = held.sortedWith(compareBy<Pair<T, Long>>({ frozen(it.first) }, { it.second }))
            .take(need).map { it.first }.toSet()
        val heldSet = held.map { it.first }.toSet()
        val out = ordered.filter { it !in heldSet || it in back }
        return Result(out, held.size, back.size)
    }

    fun describe(r: Result<*>): String =
        "Skipped ${r.skipped - r.readmitted} servers that failed here moments ago" +
            if (r.readmitted > 0) "; ${r.readmitted} of the oldest-skipped retested so the pool is not empty." else "."
}
