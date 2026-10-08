package com.example.ai

import com.example.vpn.RayVpnService
import com.example.xray.XrayLogManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class ToolExecutionResult(
    val toolName: String,
    val success: Boolean,
    val summary: String,
    val details: Map<String, Any?> = emptyMap()
)

/** Model access is read-only: the app gathers this summary itself; no model can call anything. */
object AiAgentTools {
    /** The one read-only summary the Main Agent attaches to each question. */
    const val HEALTH_TOOL = "get_app_diagnostics_and_logs"

    suspend fun executeTool(name: String, args: Map<String, Any?>): ToolExecutionResult {
        // Explicit allowlist before accessing application state. Unknown and future tools
        // fail closed; there is no mutation implementation or boolean approval bypass.
        if (name != HEALTH_TOOL) {
            return ToolExecutionResult(name, false, "Tool unavailable: AI access is read-only.")
        }
        return withContext(Dispatchers.IO) {
            val isRunning = RayVpnService.vpnState.value.isTunnelUp
            val filter = args["filter_keyword"] as? String
            val maxLines = (args["max_lines"] as? Number)?.toInt()?.coerceIn(5, 100) ?: 30
            val allLogs = XrayLogManager.logsFlow.value
            val filteredLogs = if (!filter.isNullOrBlank()) {
                allLogs.filter { it.contains(filter, ignoreCase = true) }
            } else {
                allLogs
            }.takeLast(maxLines)

            // Strict allowlist: arbitrary runtime text, endpoints, DNS and routing
            // details never cross the model boundary. Redacting arbitrary logs is insufficient.
            ToolExecutionResult(
                toolName = name,
                success = true,
                summary = "Retrieved privacy-filtered health summary",
                details = mapOf(
                    "vpn_running" to isRunning,
                    "recent_event_count" to filteredLogs.size,
                    "recent_error_count" to filteredLogs.count { it.contains("ERROR", true) },
                    "raw_logs_withheld" to true
                )
            )
        }
    }
}
