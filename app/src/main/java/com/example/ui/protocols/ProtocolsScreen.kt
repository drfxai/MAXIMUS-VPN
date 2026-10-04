package com.example.ui.protocols

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.VlessProfile

private enum class Sheet { NONE, SETUP, SOURCE, CUSTOMIZE }

/**
 * Tests which protocol families get through the user's network, ranks them and sets up the one
 * picked. [onConnect] receives the saved profile once it is selected in the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProtocolsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToPanels: () -> Unit,
    onConnect: (VlessProfile) -> Unit,
    viewModel: ProtocolsViewModel = viewModel()
) {
    BackHandler { onNavigateBack() }
    val state by viewModel.state.collectAsState()
    var sheet by rememberSaveable { mutableStateOf(Sheet.NONE) }
    var keepBackups by rememberSaveable { mutableStateOf(true) }
    var cleanUp by rememberSaveable { mutableStateOf(true) }

    // Panels added on the Panels screen show up when the user comes back.
    LaunchedEffect(Unit) { viewModel.refresh() }

    ProtocolsContent(
        state = state,
        actions = remember(viewModel) {
            ProtocolsActions(
                onBack = onNavigateBack,
                onRun = viewModel::runTest,
                onPriority = viewModel::setPriority,
                onSelect = viewModel::select,
                onOpenSetup = { sheet = Sheet.SETUP },
                onOpenCustomize = { sheet = Sheet.CUSTOMIZE },
                onPickSource = { sheet = Sheet.SOURCE },
                onAddServer = onNavigateToPanels,
                onAutoFailover = viewModel::setAutoFailover,
                onDismissError = viewModel::dismissError
            )
        },
        relativeTime = { time ->
            if (System.currentTimeMillis() - time < DateUtils.MINUTE_IN_MILLIS) "just now"
            else DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        }
    )

    if (sheet != Sheet.NONE) {
        val c = labColors
        ModalBottomSheet(
            onDismissRequest = { sheet = Sheet.NONE },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.card,
            scrimColor = androidx.compose.ui.graphics.Color.Black.copy(alpha = if (c.dark) 0.6f else 0.35f),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            when (sheet) {
                Sheet.SETUP -> SetupSheetContent(
                    state = state,
                    keepBackups = keepBackups,
                    cleanUp = cleanUp,
                    onKeepBackups = { keepBackups = it },
                    onCleanUp = { cleanUp = it },
                    onConfirm = {
                        viewModel.applyChoice(keepBackups, cleanUp) { profile ->
                            sheet = Sheet.NONE
                            onConnect(profile)
                        }
                    }
                )
                Sheet.SOURCE -> SourceSheetContent(
                    state = state,
                    onPick = { viewModel.selectPanel(it); sheet = Sheet.NONE },
                    onAddServer = { sheet = Sheet.NONE; onNavigateToPanels() }
                )
                Sheet.CUSTOMIZE -> CustomizeSheetContent(state = state, onToggle = viewModel::toggleFamily)
                Sheet.NONE -> Unit
            }
        }
    }
}
