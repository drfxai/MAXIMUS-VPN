package com.example.vpn.diagnostics.report

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.vpn.RayVpnService
import com.example.vpn.diagnostics.events.DiagEvent
import com.example.vpn.diagnostics.events.EventLog
import com.example.vpn.diagnostics.events.RuntimeHealth
import com.example.vpn.diagnostics.events.Tests
import com.example.vpn.smart.NetworkCapabilityDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Builds both reports from the live state, saves them (the last [KEEP] exports) and offers them to
 * the system share sheet. Disk work runs on the IO dispatcher, never on the main thread.
 */
object ReportExporter {
    const val KEEP = 5

    data class Export(val summary: String, val json: File, val text: File)

    fun inputs(now: Long = System.currentTimeMillis()): DiagnosticReportBuilder.Inputs {
        val state = RayVpnService.vpnState.value
        val network = NetworkCapabilityDetector.last
        return DiagnosticReportBuilder.Inputs(
            generatedAt = now,
            env = ReportEnvironment(
                appVersion = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE,
                buildCommit = BuildConfig.GIT_COMMIT,
                buildTime = BuildConfig.BUILD_TIME,
                androidVersion = Build.VERSION.RELEASE.orEmpty(),
                sdkInt = Build.VERSION.SDK_INT,
                device = "${Build.MANUFACTURER} ${Build.MODEL}",
                engineVersion = com.example.xray.XrayEngineImpl.ENGINE_VERSION
            ),
            connection = state,
            networkProfile = network?.key(),
            networkObservations = network?.observations().orEmpty(),
            events = EventLog.snapshot(),
            eventLogTruncated = EventLog.truncated,
            tests = Tests.registry.tests.value,
            runtime = RuntimeHealth.summary,
            profileRef = DiagEvent.profileRef(state.activeProfile?.id ?: state.attemptedProfileId)
        )
    }

    /** Writes the summary and the JSON report, keeping only the newest [KEEP] exports. */
    suspend fun export(context: Context): Export = withContext(Dispatchers.IO) {
        EventLog.flush()
        val i = inputs()
        val dir = File(context.filesDir, "diagnostics/reports").apply { mkdirs() }
        val stamp = i.generatedAt
        val summary = DiagnosticReportBuilder.summary(i)
        val json = File(dir, "maximus-report-$stamp.json").apply { writeText(DiagnosticReportBuilder.json(i).toString(2)) }
        val text = File(dir, "maximus-summary-$stamp.txt").apply { writeText(summary) }
        dir.listFiles().orEmpty().filter { it.name.startsWith("maximus-report-") }.sortedByDescending { it.name }.drop(KEEP).forEach { old ->
            old.delete()
            File(dir, old.name.replace("maximus-report-", "maximus-summary-").replace(".json", ".txt")).delete()
        }
        Export(summary, json, text)
    }

    /** The share sheet with the short summary as text and the JSON report attached. */
    fun shareIntent(context: Context, export: Export): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.reports", export.json)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "Maximus VPN diagnostic report")
            putExtra(Intent.EXTRA_TEXT, export.summary)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, "Share diagnostic report").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
