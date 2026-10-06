package com.example.ui.freeconfigs

import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.VlessProfile
import com.example.ui.protocols.labColors

/**
 * The project's signed free list: its servers tested on the user's network, fastest first.
 * [onConnect] receives the server picked, or the fastest one from the dock.
 */
@Composable
fun FreeConfigsScreen(
    onNavigateBack: () -> Unit,
    onConnect: (VlessProfile) -> Unit,
    viewModel: FreeConfigsViewModel = viewModel()
) {
    BackHandler { onNavigateBack() }
    val state by viewModel.state.collectAsState()

    FreeConfigsContent(
        state = state,
        c = labColors,
        actions = remember(viewModel) {
            FreeConfigsActions(
                onBack = onNavigateBack,
                onRefresh = viewModel::refresh,
                onTestAll = viewModel::testAll,
                onStopTest = viewModel::stopTest,
                onTestOne = viewModel::testOne,
                onSite = viewModel::setSite,
                onSort = viewModel::setSort,
                onProtocol = viewModel::setProtocol,
                onToggleHidden = viewModel::toggleHidden,
                onConnect = { onConnect(it.profile) },
                onDelete = viewModel::delete,
                onDismissMessage = viewModel::dismissMessage
            )
        },
        ago = { time ->
            if (System.currentTimeMillis() - time < DateUtils.MINUTE_IN_MILLIS) "just now"
            else DateUtils.getRelativeTimeSpanString(time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
        },
        until = { time ->
            val left = time - System.currentTimeMillis()
            when {
                left <= DateUtils.MINUTE_IN_MILLIS -> "soon"
                left < DateUtils.HOUR_IN_MILLIS -> "in ${left / DateUtils.MINUTE_IN_MILLIS} min"
                else -> "in ${(left + DateUtils.HOUR_IN_MILLIS / 2) / DateUtils.HOUR_IN_MILLIS} h"
            }
        }
    )
}
