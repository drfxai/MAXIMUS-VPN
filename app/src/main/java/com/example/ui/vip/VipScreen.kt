package com.example.ui.vip

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.data.model.VlessProfile
import com.example.ui.theme.AppTheme

/** The VIP servers the admin publishes from Telegram, tested on the user's network, fastest first. */
@Composable
fun VipScreen(
    onNavigateBack: () -> Unit,
    onConnect: (VlessProfile) -> Unit,
    viewModel: VipViewModel = viewModel()
) {
    BackHandler { onNavigateBack() }
    val state by viewModel.state.collectAsState()

    VipContent(
        state = state,
        c = if (AppTheme.colors.isDark) DarkVipColors else LightVipColors,
        actions = remember(viewModel) {
            VipActions(
                onBack = onNavigateBack,
                onRefresh = viewModel::refresh,
                onTestAll = viewModel::testAll,
                onConnect = { onConnect(it.profile) },
                onDismissMessage = viewModel::dismissMessage
            )
        }
    )
}
