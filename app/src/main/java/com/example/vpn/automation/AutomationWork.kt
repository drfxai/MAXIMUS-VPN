package com.example.vpn.automation

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.startup.Initializer
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.data.model.ConnectionStatus
import com.example.vpn.VpnController
import com.example.vpn.dnsfirst.DnsLadderHook
import com.example.vpn.smart.NetworkIdentity
import com.example.vpn.smart.NetworkKey
import com.example.xray.XrayLogManager
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Runs one upkeep pass for the trigger in its input ([AutomationScheduler]). */
class AutomationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val trigger = AutomationScheduler.triggerOf(inputData)
        val only = AutomationScheduler.onlyOf(inputData)
        return try {
            val tasks = AutomationTasks(applicationContext, { isStopped })
            val profiles = tasks.profiles()
            val env = tasks.env(profiles)
            val book = AutomationStores.status(applicationContext)
            // Beside this app's own traffic block (a failed connect kept fail-closed): measured outside it, other
            // apps stay blocked (OwnVpnWindow). When the window cannot open, the run records VPN_ACTIVE and retries.
            val summary = (if (env.ownBlockOnly) OwnVpnWindow.run { intact ->
                XrayLogManager.i("AUTO", "Upkeep ($trigger) runs beside this app's traffic block; other apps stay blocked.")
                AutomationRunner(book, AutomationTasks(applicationContext, { isStopped || !intact() }, intact).bodies(profiles))
                    .run(trigger, env, only)
            } else null) ?: AutomationRunner(book, tasks.bodies(profiles)).run(trigger, env.copy(ownBlockOnly = false), only)
            XrayLogManager.i("AUTO", "Upkeep ($trigger): ran ${summary.ran}, skipped ${summary.skipped}, failed ${summary.failed}" +
                if (env.vpnActive) " (VPN up: ${if (env.tunnelUp) "this app's tunnel" else if (env.ownBlockOnly) "this app's traffic block" else "another VPN or a connect under way"})" else "")
            // The diagnostics report carries each upkeep run (build 174 had only log lines to go on).
            runCatching {
                com.example.vpn.diagnostics.events.EventLog.event("upkeep.run", attributes = mapOf(
                    "trigger" to trigger.name,
                    "ran" to summary.ran.joinToString(",").ifEmpty { "none" },
                    "skipped" to summary.skipped.entries.joinToString(",") { "${it.key.name}=${it.value.name}" }.ifEmpty { "none" },
                    "failed" to summary.failed.joinToString(",").ifEmpty { "none" },
                    "vpn" to when {
                        !env.vpnActive -> "none"
                        env.tunnelUp -> "tunnel"
                        env.ownBlockOnly -> "own-block"
                        else -> "other"
                    }), result = if (summary.failed.isEmpty()) "PASS" else "FAIL")
            }
            // After a failed connect the blocking interface may still be up for a moment: try again a little later.
            if (summary.blockedByVpn && trigger == AutomationTrigger.CONNECT_FAILED && runAttemptCount < MAX_RETRIES) Result.retry()
            else Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            XrayLogManager.w("AUTO", "Upkeep ($trigger) stopped: ${t.message ?: t.javaClass.simpleName}")
            Result.success()
        }
    }

    companion object {
        const val MAX_RETRIES = 3
    }
}

/**
 * When upkeep runs: daily (battery not low), on a network change and when the VPN stops (debounced; a newer
 * change replaces a pending one), after a failed connect, after a connect (GeoFiles through the tunnel), and
 * on request. No network-type constraint: WorkManager's "connected" needs a validated network, and a
 * DNS-only network never validates — exactly when this upkeep matters most. The tasks cope with no network.
 */
object AutomationScheduler {
    const val PERIODIC = "dns-first-daily"
    private const val KEY_TRIGGER = "trigger"
    private const val KEY_ONLY = "only"

    fun uniqueName(trigger: AutomationTrigger): String = "dns-first-${trigger.name.lowercase()}"

    fun delaySeconds(trigger: AutomationTrigger): Long = when (trigger) {
        AutomationTrigger.NETWORK_CHANGED -> 20
        AutomationTrigger.VPN_STOPPED -> 30
        AutomationTrigger.CONNECT_FAILED -> 15
        AutomationTrigger.CONNECTED -> 60
        AutomationTrigger.DAILY, AutomationTrigger.MANUAL -> 0
    }

    fun policy(trigger: AutomationTrigger): ExistingWorkPolicy = when (trigger) {
        AutomationTrigger.NETWORK_CHANGED, AutomationTrigger.VPN_STOPPED, AutomationTrigger.MANUAL -> ExistingWorkPolicy.REPLACE
        else -> ExistingWorkPolicy.KEEP
    }

    fun constraints(trigger: AutomationTrigger): Constraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
        .setRequiresBatteryNotLow(trigger in setOf(AutomationTrigger.DAILY, AutomationTrigger.NETWORK_CHANGED, AutomationTrigger.VPN_STOPPED))
        .build()

    fun data(trigger: AutomationTrigger, only: Set<AutomationTask>? = null): Data = Data.Builder()
        .putString(KEY_TRIGGER, trigger.name)
        .apply { if (only != null) putStringArray(KEY_ONLY, only.map { it.name }.toTypedArray()) }
        .build()

    fun triggerOf(data: Data): AutomationTrigger =
        runCatching { AutomationTrigger.valueOf(data.getString(KEY_TRIGGER).orEmpty()) }.getOrDefault(AutomationTrigger.DAILY)

    fun onlyOf(data: Data): Set<AutomationTask>? =
        data.getStringArray(KEY_ONLY)?.mapNotNull { runCatching { AutomationTask.valueOf(it) }.getOrNull() }?.toSet()

    fun oneTime(trigger: AutomationTrigger, only: Set<AutomationTask>? = null): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<AutomationWorker>()
            .setInitialDelay(delaySeconds(trigger), TimeUnit.SECONDS)
            .setConstraints(constraints(trigger))
            .setInputData(data(trigger, only))
            .setBackoffCriteria(BackoffPolicy.LINEAR, 60, TimeUnit.SECONDS)
            .addTag(TAG)
            .build()

    fun periodic(): PeriodicWorkRequest = PeriodicWorkRequestBuilder<AutomationWorker>(1, TimeUnit.DAYS)
        .setConstraints(constraints(AutomationTrigger.DAILY))
        .setInputData(data(AutomationTrigger.DAILY))
        .addTag(TAG)
        .build()

    fun schedulePeriodic(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, periodic())
    }

    fun trigger(context: Context, trigger: AutomationTrigger, only: Set<AutomationTask>? = null) {
        val name = if (only == null) uniqueName(trigger) else uniqueName(trigger) + "-" + only.joinToString("-") { it.name.lowercase() }
        WorkManager.getInstance(context).enqueueUniqueWork(name, policy(trigger), oneTime(trigger, only))
    }

    const val TAG = "dns-first-upkeep"
}

/**
 * Starts the upkeep in the main process: WorkManager (initialised here, on demand, so the Tor process and
 * Robolectric never start it), the daily job, the network-change watch, the connection-state watch, and the
 * connect-time hook that hands verified DNS-tunnel variants to the VPN.
 */
object AutomationStartup {
    private val started = AtomicBoolean(false)
    @Volatile private var lastNetwork: String? = null

    fun start(context: Context) {
        if (Build.FINGERPRINT == "robolectric" || !started.compareAndSet(false, true)) return
        if (!mainProcess(context)) return
        val app = context.applicationContext
        val ok = runCatching { WorkManager.initialize(app, androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()) }
        if (ok.isFailure && runCatching { WorkManager.getInstance(app) }.isFailure) return
        DnsLadderHook.lookup = DnsLadderHook.lookupFor(AutomationStores.ladder(app)) {
            runCatching { NetworkIdentity.current(app) }.getOrElse { NetworkKey.current(app) }
        }
        runCatching { AutomationScheduler.schedulePeriodic(app) }
        runCatching { watchNetwork(app) }
        watchConnection(app)
    }

    private fun mainProcess(context: Context): Boolean {
        val name = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else runCatching {
            java.io.File("/proc/self/cmdline").readText().trim('\u0000', ' ', '\n')
        }.getOrNull()
        return name == null || name == context.packageName
    }

    private fun watchNetwork(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = changed()

            private fun changed() {
                val key = runCatching { NetworkIdentity.current(context) }.getOrNull() ?: return
                if (key == lastNetwork || key == "other") return
                lastNetwork = key
                runCatching { AutomationScheduler.trigger(context, AutomationTrigger.NETWORK_CHANGED) }
            }
        })
    }

    private fun watchConnection(context: Context) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            // Let Application.onCreate finish before touching the VPN service's state.
            delay(3_000)
            var previous: ConnectionStatus? = null
            VpnController.connectionState.map { it.status }.distinctUntilChanged().collect { status ->
                val trigger = when {
                    status == ConnectionStatus.FAILED -> AutomationTrigger.CONNECT_FAILED
                    status == ConnectionStatus.CONNECTED && previous != ConnectionStatus.CONNECTED -> AutomationTrigger.CONNECTED
                    status == ConnectionStatus.DISCONNECTED && previous != null && previous != ConnectionStatus.DISCONNECTED -> AutomationTrigger.VPN_STOPPED
                    else -> null
                }
                previous = status
                if (trigger != null) runCatching { AutomationScheduler.trigger(context, trigger) }
            }
        }
    }
}

/** androidx.startup entry (see AndroidManifest.xml). */
class AutomationInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        runCatching { AutomationStartup.start(context) }
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
