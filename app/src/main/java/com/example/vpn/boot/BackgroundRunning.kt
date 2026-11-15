package com.example.vpn.boot

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Battery-optimisation exemption, so Android does not suspend the tunnel when the screen goes off (the
 * WhiteAestherMobile "Allow background running" card, WhiteDNS/WhiteAestherMobile 6e2ade9e86c5, AGPL-3.0).
 * Only ever asked when the user taps the row; a VPN is one of the uses Android's policy allows it for.
 */
object BackgroundRunning {
    fun isUnrestricted(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        val app = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        val order = if (isUnrestricted(context)) listOf(list, app) else listOf(direct, list, app)
        for (intent in order) {
            if (runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
    }
}
