package com.example.vpn.routing

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.drawable.Drawable

/*
 * Ported from WhiteAestherMobile (https://github.com/WhiteDNS/WhiteAestherMobile, commit 6e2ade9e86c5,
 * app/src/main/java/com/whitedns/whiteaesther/data/InstalledApps.kt), AGPL-3.0.
 */

/** An app the user could plausibly want routed, or kept out of the tunnel. No icon: rows fetch their own. */
data class InstalledApp(
    val packageName: String,
    val label: String,
    /** Shipped with the device rather than installed by the user. */
    val system: Boolean,
)

object InstalledApps {
    /**
     * Everything with a launcher entry, by name, user-installed first. Not every package: QUERY_ALL_PACKAGES
     * is restricted and returns hundreds of services nobody recognises. Call off the main thread.
     */
    fun launchable(context: Context): List<InstalledApp> {
        val manager = context.packageManager
        return runCatching {
            sequenceOf(Intent.CATEGORY_LAUNCHER, Intent.CATEGORY_LEANBACK_LAUNCHER)
                .flatMap { category ->
                    manager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0).asSequence()
                }
                .mapNotNull { it.activityInfo?.applicationInfo }
                .distinctBy { it.packageName }
                .map { info ->
                    InstalledApp(
                        packageName = info.packageName,
                        label = runCatching { manager.getApplicationLabel(info).toString() }.getOrDefault(info.packageName),
                        system = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                    )
                }
                .sortedWith(compareBy({ it.system }, { it.label.lowercase() }))
                .toList()
        }.getOrDefault(emptyList())
    }

    /** One app's launcher icon. Expensive enough to be worth doing off the main thread. */
    fun icon(context: Context, packageName: String): Drawable? =
        runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull()
}
