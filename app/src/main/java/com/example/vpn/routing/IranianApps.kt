package com.example.vpn.routing

/**
 * Suggests installed apps that are usually better left outside the tunnel in Iran: banking, payment,
 * ride-hailing, operator and local messenger apps, many of which refuse to run or are slowed behind a VPN
 * with a foreign exit. Only a suggestion: the user reviews the list before saving.
 *
 * Matching is by package name only (never by label), so a localised label on a foreign app cannot pull it in.
 */
object IranianApps {
    /** Package-name prefixes used by Iranian publishers. */
    private val PREFIXES = listOf("ir.", "com.ir.")

    /** Well-known Iranian apps whose package names do not start with `ir.`. */
    private val KNOWN = setOf(
        "com.farsitel.bazaar",      // Cafe Bazaar
        "com.myirancell",           // MyIrancell
        "cab.snapp.passenger",      // Snapp
        "taxi.tap30.passenger",     // Tapsi
        "com.digikala",             // Digikala
        "app.rbmain.a",             // Rubika
        "mobi.mmdt.ottplus",        // Soroush Plus
    )

    fun matches(packageName: String): Boolean {
        val p = packageName.lowercase()
        return p in KNOWN || PREFIXES.any { p.startsWith(it) }
    }

    fun suggest(installed: List<InstalledApp>): Set<String> =
        installed.asSequence().filter { !it.system && matches(it.packageName) }.map { it.packageName }.toSet()
}
