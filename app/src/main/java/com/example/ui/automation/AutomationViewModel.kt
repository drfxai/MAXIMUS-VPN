package com.example.ui.automation

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.vpn.automation.AutomationScheduler
import com.example.vpn.automation.AutomationStores
import com.example.vpn.automation.AutomationTask
import com.example.vpn.automation.AutomationTrigger
import com.example.vpn.automation.SkipReason
import com.example.vpn.automation.TaskOutcome
import com.example.vpn.automation.TaskStatus
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** One upkeep task as the status card shows it. */
data class AutomationRow(
    val task: AutomationTask,
    val lastRunAt: Long = 0,
    val outcome: TaskOutcome? = null,
    val found: Int = 0,
    val skip: SkipReason? = null,
    val detail: String = ""
)

/** Everything the status card needs; the new design can render it however it likes. */
data class AutomationUiState(
    val rows: List<AutomationRow> = AutomationTask.entries.map { AutomationRow(it) },
    val running: Boolean = false,
    val autoDrop: Boolean = false
) {
    /** The most recent run of any task (0: never). */
    val lastRunAt: Long get() = rows.maxOfOrNull { it.lastRunAt } ?: 0L
    val totalFound: Int get() = rows.filter { it.outcome == TaskOutcome.OK }.sumOf { it.found }

    companion object {
        fun from(statuses: Map<AutomationTask, TaskStatus>, running: Boolean, autoDrop: Boolean) = AutomationUiState(
            rows = AutomationTask.entries.map { t ->
                statuses[t]?.let { AutomationRow(t, it.lastRunAt, it.outcome, it.found, it.skip, it.detail) } ?: AutomationRow(t)
            },
            running = running,
            autoDrop = autoDrop
        )
    }
}

class AutomationViewModel(app: Application) : AndroidViewModel(app) {
    private val book = AutomationStores.status(app)
    private val autoDrop = MutableStateFlow(AutomationStores.autoDrop(app))

    private val running: Flow<Boolean> = runCatching {
        WorkManager.getInstance(app).getWorkInfosByTagFlow(AutomationScheduler.TAG)
            .map { infos -> infos.any { it.state == WorkInfo.State.RUNNING } }
    }.getOrElse { flowOf(false) }

    val state: StateFlow<AutomationUiState> = combine(book.flow, running, autoDrop) { s, r, d -> AutomationUiState.from(s, r, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutomationUiState.from(book.current(), false, autoDrop.value))

    fun runNow(task: AutomationTask? = null) {
        runCatching { AutomationScheduler.trigger(getApplication(), AutomationTrigger.MANUAL, task?.let { setOf(it) }) }
    }

    fun setAutoDrop(value: Boolean) {
        AutomationStores.setAutoDrop(getApplication(), value)
        autoDrop.value = value
    }
}
