package com.example.vpn.share

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.RayApplication
import com.example.vpn.VpnController
import com.example.vpn.sidecar.*
import com.example.vpn.verify.Socks5
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** Explicit proxy-only session: creates no Android VPN interface and changes no global routes. */
class LocalProxyService : Service() {
    data class State(val starting: Boolean = false, val port: Int = 0, val user: String = "",
        val password: String = "", val error: String? = null) {
        val running: Boolean get() = port > 0
    }
    companion object {
        private val mutable = MutableStateFlow(State())
        val state = mutable.asStateFlow()
        private const val CHANNEL = "maximus-local-proxy"
        private const val NOTIFICATION = 1004
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var engine: RunningEngine? = null
    @Volatile private var gateway: LoopbackSocksProxy? = null
    private var work: File? = null

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        if (job != null) return START_NOT_STICKY
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Local SOCKS proxy", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 0, Intent(this, LocalProxyService::class.java).setAction("stop"),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        startForeground(NOTIFICATION, NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_upload_done).setContentTitle("MAXIMUS local proxy")
            .setContentText("Proxy mode only · no device-wide VPN").setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop).build())
        mutable.value = State(starting = true)
        job = scope.launch {
            try {
                check(!VpnController.connectionState.value.let { it.isTunnelUp || it.isBusy }) { "Disconnect the VPN before starting proxy mode." }
                val cm = getSystemService(android.net.ConnectivityManager::class.java)
                check(cm.allNetworks.none { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }) {
                    "Disconnect the active VPN before starting proxy mode."
                }
                val app = RayApplication.instance
                val settings = app.settingsRepository.getSettings()
                var profile = app.serverRepository.getAllProfilesOnce().firstOrNull { it.id == settings.selectedProfileId }
                    ?: error("Select a server first.")
                if (Sidecars.forProfile(profile) == null && SingBoxSidecar.conversionProblem(profile) == null)
                    profile = SingBoxSidecar.select(profile)
                val adapter = Sidecars.forProfile(profile) ?: error("Select Psiphon, Tor, SSH, a DNS tunnel or a sing-box-compatible server.")
                check(adapter.id != ByedpiSidecar.ID) { "This carrier requires VPN mode." }
                adapter.problem(profile)?.let { error(it) }
                val executable = Sidecars.executable(adapter) ?: error("Required engine is missing from this APK.")
                Sidecars.torStarter = { torrc, port -> TorInApp.start(this@LocalProxyService, torrc, port) }
                val directory = File(noBackupFilesDir, "proxy-session-${Sidecars.randomToken(6)}").apply { mkdirs() }
                work = directory
                val ctx = SidecarContext(directory, executable, Sidecars.freeLoopbackPort(), Sidecars.randomToken(9),
                    Sidecars.randomToken(), settings.operationalMode)
                val launch = adapter.prepare(profile, settings, ctx)
                val running = adapter.startInApp(launch, ctx) ?: SidecarProcess.start(adapter.id, launch, directory, ctx.socksPort)
                engine = running
                check(runInterruptible { running.awaitReady(launch.readyTimeoutMs) }) { "Engine did not become ready." }
                ensureActive()
                val credentials = HotspotShare.newCredentials()
                val endpoint = Socks5.Endpoint("127.0.0.1", ctx.socksPort,
                    ctx.socksUser.takeIf { launch.socksAuth }, ctx.socksPass.takeIf { launch.socksAuth })
                val proxy = LoopbackSocksProxy(credentials.first, credentials.second) { host, port -> Socks5.connect(endpoint, host, port, 10_000) }
                gateway = proxy
                mutable.value = State(port = proxy.port, user = credentials.first, password = credentials.second)
                while (isActive && running.isAlive) delay(1000)
                if (isActive) error("Proxy engine stopped.")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.value = State(error = "Proxy could not start or stopped. Check the selected engine and its settings.") }
            finally {
                gateway?.close(); gateway = null
                engine?.stop(); engine = null
                work?.deleteRecursively(); work = null
                if (mutable.value.error == null) mutable.value = State()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        gateway?.close()
        engine?.stop()
        if (mutable.value.error == null) mutable.value = State()
        super.onDestroy()
    }
}
