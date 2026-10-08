package com.example.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.example.RayApplication
import com.example.core.SecretRedactor
import com.example.data.model.ConnectionStatus
import com.example.data.model.DiagnosticReport
import com.example.data.repository.SettingsRepository
import com.example.vpn.VpnController
import com.example.xray.XrayEngine
import com.example.xray.XrayEngineImpl
import com.example.xray.XrayLogManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import com.example.data.model.ErrorReport
import com.example.data.model.ErrorSeverity
import com.example.data.model.ErrorSource
import com.example.data.model.SubsystemHealth

class DiagnosticsViewModel(
    private val settingsRepository: SettingsRepository = RayApplication.instance.settingsRepository,
    private val xrayEngine: XrayEngine = XrayEngineImpl.instance
) : ViewModel() {

    val logs: StateFlow<List<String>> = XrayLogManager.logsFlow
    val connectionState = VpnController.connectionState

    private val _reportedErrors = MutableStateFlow<List<ErrorReport>>(emptyList())
    val reportedErrors: StateFlow<List<ErrorReport>> = _reportedErrors.asStateFlow()

    private val _subsystemHealth = MutableStateFlow(SubsystemHealth())
    val subsystemHealth: StateFlow<SubsystemHealth> = _subsystemHealth.asStateFlow()

    private val _tunnelConnectivity = MutableStateFlow<com.example.vpn.diagnostics.TunnelConnectivityResult?>(null)
    val tunnelConnectivity: StateFlow<com.example.vpn.diagnostics.TunnelConnectivityResult?> = _tunnelConnectivity.asStateFlow()
    private val _isTunnelTestRunning = MutableStateFlow(false)
    val isTunnelTestRunning: StateFlow<Boolean> = _isTunnelTestRunning.asStateFlow()

    private val _dnsPath = MutableStateFlow<com.example.vpn.diagnostics.DnsPathResult?>(null)
    val dnsPath: StateFlow<com.example.vpn.diagnostics.DnsPathResult?> = _dnsPath.asStateFlow()
    private val _dnsTestRunning = MutableStateFlow(false)
    val dnsTestRunning: StateFlow<Boolean> = _dnsTestRunning.asStateFlow()

    /**
     * A PASS holds only for the attempt, network, profile and settings it was measured on: a disconnect,
     * profile change, network change, engine restart or new attempt changes this key.
     */
    private fun dnsSessionKey() = connectionState.value.passKey to settingsRepository.getSettings()

    private var lastDnsSessionKey: Any? = null

    init {
        viewModelScope.launch {
            kotlinx.coroutines.flow.combine(connectionState, settingsRepository.settingsFlow) { conn, settings ->
                conn.passKey to settings
            }.collect { key ->
                if (lastDnsSessionKey != key) {
                    // Older results stay only as history (the event log); the live view drops them.
                    _dnsPath.value = null
                    _tunnelConnectivity.value = null
                    lastDnsSessionKey = key
                }
            }
        }
    }

    fun testDnsPath() {
        if (_dnsTestRunning.value) return
        if (!connectionState.value.isConnected) {
            _dnsPath.value = com.example.vpn.diagnostics.DnsPathResult(false, "Connect the VPN before testing DNS.")
            return
        }
        val session = dnsSessionKey()
        _dnsTestRunning.value = true
        _dnsPath.value = null
        viewModelScope.launch {
            try {
                val result = com.example.vpn.diagnostics.DnsPathTest.run(RayApplication.instance)
                _dnsPath.value = if (session == dnsSessionKey()) result else
                    com.example.vpn.diagnostics.DnsPathResult(false, "Connection or settings changed; result discarded.")
                XrayLogManager.i("DNS_TEST", _dnsPath.value!!.summary)
            } finally { _dnsTestRunning.value = false }
        }
    }

    fun testTunnelConnectivity() {
        if (_isTunnelTestRunning.value) return
        if (!connectionState.value.isVpnInterfaceActive) {
            _tunnelConnectivity.value = com.example.vpn.diagnostics.TunnelConnectivityResult(
                reachable = false,
                errorMessage = "Connect the VPN before testing tunneled internet access."
            )
            return
        }
        val session = dnsSessionKey()
        viewModelScope.launch {
            _isTunnelTestRunning.value = true
            try {
                val result = com.example.vpn.diagnostics.NetworkDiagnostics.testTunnelConnectivity()
                // A result for a session that has since changed is not shown as current.
                _tunnelConnectivity.value = if (session == dnsSessionKey()) result else
                    com.example.vpn.diagnostics.TunnelConnectivityResult(reachable = false, errorMessage = "Connection or network changed; result discarded.")
            } finally {
                _isTunnelTestRunning.value = false
            }
        }
    }

    fun clearLogs() {
        XrayLogManager.clear()
    }

    fun submitErrorReport(
        source: ErrorSource,
        severity: ErrorSeverity,
        title: String,
        description: String,
        userNotes: String = "",
        throwable: Throwable? = null
    ): ErrorReport {
        val sanitizedLogs = XrayLogManager.getLogs().takeLast(25)
        val report = ErrorReport(
            source = source,
            severity = severity,
            title = title,
            description = description,
            userNotes = userNotes,
            sanitizedLogs = sanitizedLogs
        )

        _reportedErrors.value = listOf(report) + _reportedErrors.value

        // Log directly into XrayLogManager with tagged category
        val logTag = "${source.tagPrefix}_REPORT"
        val logMessage = "[$title] $description ${if (userNotes.isNotBlank()) "(Note: $userNotes)" else ""}"
        
        when (severity) {
            ErrorSeverity.CRITICAL -> XrayLogManager.fatal(logTag, logMessage, throwable)
            ErrorSeverity.ERROR -> XrayLogManager.e(logTag, logMessage, throwable)
            ErrorSeverity.WARNING -> XrayLogManager.w(logTag, logMessage, throwable)
            ErrorSeverity.INFO -> XrayLogManager.i(logTag, logMessage)
        }

        return report
    }

    fun runHealthCheck(): SubsystemHealth {
        val conn = connectionState.value
        val settings = settingsRepository.getSettings()

        val uiState = "HEALTHY (Compose M3)"
        val vpnState = if (conn.isConnected) "CONNECTED (${conn.vpnIp})" else "IDLE (Ready)"
        val dnsState = when {
            conn.activeEngineName.orEmpty().contains("Kotlin", ignoreCase = true) ->
                "Selected DNS is routed through the proxy; live DoH/leak verification not performed"
            settings.dnsServer.startsWith("https://") -> "DoH configured; runtime status not measured"
            else -> "STANDARD (${settings.dnsServer}); runtime status not measured"
        }
        val routingState = "Configured: ${settings.routingMode.title}; routing not tested"

        val health = SubsystemHealth(
            uiState = uiState,
            vpnEngineState = vpnState,
            dnsResolverState = dnsState,
            networkRoutingState = routingState,
            lastCheckedTimestamp = System.currentTimeMillis()
        )

        _subsystemHealth.value = health

        XrayLogManager.i("HEALTH_CHECK", "Subsystem audit completed: UI=$uiState | VPN=$vpnState | DNS=$dnsState | Routing=$routingState")
        return health
    }

    fun generateDiagnosticReport(): DiagnosticReport {
        val conn = connectionState.value
        val settings = settingsRepository.getSettings()
        val profile = conn.activeProfile

        val profileSummary = if (profile != null) {
            "${profile.name} (${com.example.vpn.diagnostics.events.DiagEvent.profileRef(profile.id)} • ${profile.transport.uppercase()}/${profile.security.ifBlank { "none" }.uppercase()})"
        } else {
            "No active server connected"
        }

        val selectedEngine = conn.activeEngineName.orEmpty()
        val engineName = when {
            !conn.isVpnInterfaceActive -> "Inactive"
            selectedEngine.startsWith("Xray-core via XTLS/libXray") -> "Xray-core (native libXray)"
            selectedEngine.isNotBlank() -> selectedEngine
            else -> "Unknown (engine not recorded)"
        }
        val protocolName = profile?.protocolType?.displayName ?: "None"

        return DiagnosticReport(
            appVersion = "Maximus v${com.example.BuildConfig.VERSION_NAME} (${com.example.BuildConfig.VERSION_CODE}, by DrFXAi)",
            vpnServiceRunning = conn.isVpnInterfaceActive,
            activeEngine = engineName,
            activeServerSummary = profileSummary,
            activeProtocol = protocolName,
            routingMode = settings.routingMode.title,
            dnsServer = settings.dnsServer,
            dohWorking = null,
            dnsLeakDetected = null,
            resolverIp = null,
            networkType = if (conn.isVpnInterfaceActive) "Android TUN (${conn.vpnIp ?: "unknown"})" else "Direct Interface",
            lastLatencyMs = conn.pingMs,
            connectionState = conn.status.name,
            sanitizedLogs = XrayLogManager.getLogs(),
            lastError = conn.errorMessage,
            tunnelConnectivity = _tunnelConnectivity.value,
            dnsPathTest = _dnsPath.value
        )
    }

    /** Saves the summary and JSON reports (on the IO dispatcher), then hands back the share sheet. */
    fun exportReports(onReady: (android.content.Intent) -> Unit) {
        viewModelScope.launch {
            runCatching {
                val app = RayApplication.instance
                val export = com.example.vpn.diagnostics.report.ReportExporter.export(app)
                com.example.vpn.diagnostics.report.ReportExporter.shareIntent(app, export)
            }.onSuccess(onReady)
                .onFailure { XrayLogManager.w("DIAG", "Could not export the diagnostic report: ${it.message}") }
        }
    }

    private fun dateFormatForDns(time: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(time))

    fun formatReportText(report: DiagnosticReport): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US).format(Date(report.generatedAt))
        val sb = StringBuilder()
        sb.appendLine("==========================================")
        sb.appendLine("        MAXIMUS VPN DIAGNOSTIC REPORT     ")
        sb.appendLine("               (By DrFXAi)                ")
        sb.appendLine("==========================================")
        sb.appendLine("Timestamp: $dateStr")
        sb.appendLine("App Version: ${report.appVersion}")
        sb.appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} (Android ${android.os.Build.VERSION.RELEASE}, API ${android.os.Build.VERSION.SDK_INT})")
        sb.appendLine("VPN Service Status: ${if (report.vpnServiceRunning) "ACTIVE (TUN ESTABLISHED)" else "INACTIVE"}")
        sb.appendLine("Active Core Engine: ${report.activeEngine}")
        sb.appendLine("Active Protocol: ${report.activeProtocol}")
        sb.appendLine("Native Engine: ${if (report.activeEngine.startsWith("Xray-core")) "XTLS/libXray; attached to Android TUN" else "Not active for this connection"}")
        sb.appendLine("Connection State: ${report.connectionState}")
        sb.appendLine("Android lockdown (Block connections without VPN): ${com.example.vpn.RayVpnService.lockdown.value.name}")
        sb.appendLine("Network at last connect: ${com.example.vpn.RayVpnService.environment.value?.describe() ?: "not observed yet"}")
        sb.appendLine("Active Server: ${report.activeServerSummary}")
        sb.appendLine("Configured Routing Mode: ${report.routingMode}")
        sb.appendLine("Configured DNS: ${report.dnsServer}")
        sb.appendLine("DoH Runtime Verification: ${report.dohWorking?.toString() ?: "Not measured"}")
        sb.appendLine("External DNS Leak Test: ${report.dnsLeakDetected?.toString() ?: "NOT TESTED (needs an external resolver observation)"}")
        report.dnsPathTest?.let {
            sb.appendLine("Local VPN DNS Path Test: ${if (it.completed) "RESPONSE RECEIVED" else "INCONCLUSIVE"}; ${it.summary}")
            sb.appendLine("DNS Path Test Time: ${dateFormatForDns(it.testedAt)}; latency=${it.latencyMs?.let { ms -> "${ms}ms" } ?: "N/A"}")
        }
        sb.appendLine("Network Type: ${report.networkType}")
        val tunnelTest = report.tunnelConnectivity
        val tunnelTestText = when {
            tunnelTest == null -> "Not performed"
            tunnelTest.reachable -> "PASS (HTTP ${tunnelTest.httpStatus}, ${tunnelTest.latencyMs}ms via ${tunnelTest.endpoint})"
            else -> "FAIL (${tunnelTest.errorMessage ?: "no response"})"
        }
        sb.appendLine("End-to-End Tunnel Test: $tunnelTestText")
        sb.appendLine("Latency: ${report.lastLatencyMs?.let { "${it}ms" } ?: "N/A"}")
        if (report.lastError != null) {
            sb.appendLine("Last Error Reported: ${report.lastError}")
        }
        sb.appendLine("\n--- CONNECTION METRICS (since the app started) ---")
        com.example.vpn.diagnostics.ConnectionMetrics.summary().forEach { sb.appendLine(it) }
        sb.appendLine("\n--- SANITIZED LOG TRACE (UUIDs & CREDENTIALS REDACTED) ---")
        if (report.sanitizedLogs.isEmpty()) {
            sb.appendLine("[No log entries available]")
        } else {
            report.sanitizedLogs.takeLast(250).forEach { logLine ->
                sb.appendLine(SecretRedactor.redact(logLine))
            }
        }
        sb.appendLine("==========================================")
        sb.appendLine("END OF DIAGNOSTIC REPORT")
        sb.appendLine("==========================================")
        return com.example.vpn.diagnostics.report.ReportRedaction.secondPass(sb.toString())
    }
}
