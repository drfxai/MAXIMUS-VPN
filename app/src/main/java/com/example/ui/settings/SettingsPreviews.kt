package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.data.model.AppSettings
import com.example.ui.theme.AppTheme
import com.example.ui.theme.MyApplicationTheme

/** Sample state shared by the previews and the Settings screenshot tests. */
internal object SettingsSamples {
    val settings = AppSettings()
    val update = UpdateUi(checked = true)
    const val VERSION = "1.0.4"
}

@Composable
internal fun SettingsPreviewFrame(dark: Boolean, content: @Composable () -> Unit) {
    MyApplicationTheme(darkTheme = dark) {
        Box(Modifier.background(AppTheme.colors.background)) { content() }
    }
}

@Composable
internal fun SettingsHubSample() = SettingsHub(
    settings = SettingsSamples.settings,
    versionName = SettingsSamples.VERSION,
    update = SettingsSamples.update,
    onOpen = {},
    onOpenAiAgent = {},
    onUpdateChip = {}
)

@Composable
internal fun ConnectionPageSample() = ConnectionSettingsPage(
    settings = SettingsSamples.settings,
    actions = SettingsActions.Noop,
    learnedSummary = "12 routes on 3 networks",
    localProxy = LocalProxyUi(canStart = true),
    onOpenLearnedNetworks = {},
    onToggleLocalProxy = {},
    onBack = {}
)

@Composable
internal fun RoutingDnsPageSample() = RoutingDnsSettingsPage(
    settings = SettingsSamples.settings,
    actions = SettingsActions.Noop,
    dnsTestRunning = false,
    dnsTestResult = null,
    onCustomDns = {},
    onTestVpnDns = {},
    onTestResolver = {},
    onOpenResolverData = {},
    onBack = {}
)

@Composable
internal fun AntiCensorshipPageSample() = AntiCensorshipSettingsPage(
    settings = SettingsSamples.settings,
    actions = SettingsActions.Noop,
    onBack = {}
)

@Composable
internal fun PrivacyPageSample() = PrivacySettingsPage(
    settings = SettingsSamples.settings,
    actions = SettingsActions.Noop,
    onOpenAndroidVpnSettings = {},
    onBack = {}
)

@Composable
internal fun UpdatesPageSample() = UpdatesSettingsPage(
    versionName = SettingsSamples.VERSION,
    update = SettingsSamples.update,
    data = UpdatesDataUi(),
    onCheckForUpdates = {},
    onCoreManager = {},
    onUpdateResolvers = {},
    onRollbackResolvers = {},
    onRefreshCleanIps = {},
    onVpnOnlyUpdates = {},
    onChangelog = {},
    onBack = {}
)

@Composable
internal fun DiagnosticsPageSample() = DiagnosticsSettingsPage(onViewLogs = {}, onViewConfig = {}, onReset = {}, onBack = {})

@Preview(name = "Settings hub · dark", widthDp = 412, heightDp = 915)
@Composable
private fun SettingsHubDarkPreview() = SettingsPreviewFrame(dark = true) { SettingsHubSample() }

@Preview(name = "Settings hub · light", widthDp = 412, heightDp = 915)
@Composable
private fun SettingsHubLightPreview() = SettingsPreviewFrame(dark = false) { SettingsHubSample() }

@Preview(name = "Connection · dark", widthDp = 412, heightDp = 915)
@Composable
private fun ConnectionPreview() = SettingsPreviewFrame(dark = true) { ConnectionPageSample() }

@Preview(name = "Routing & DNS · dark", widthDp = 412, heightDp = 915)
@Composable
private fun RoutingDnsPreview() = SettingsPreviewFrame(dark = true) { RoutingDnsPageSample() }

@Preview(name = "Anti-censorship · dark", widthDp = 412, heightDp = 915)
@Composable
private fun AntiCensorshipPreview() = SettingsPreviewFrame(dark = true) { AntiCensorshipPageSample() }

@Preview(name = "Privacy · light", widthDp = 412, heightDp = 915)
@Composable
private fun PrivacyPreview() = SettingsPreviewFrame(dark = false) { PrivacyPageSample() }

@Preview(name = "Updates · dark", widthDp = 412, heightDp = 915)
@Composable
private fun UpdatesPreview() = SettingsPreviewFrame(dark = true) { UpdatesPageSample() }

@Preview(name = "Diagnostics · dark", widthDp = 412, heightDp = 915)
@Composable
private fun DiagnosticsPreview() = SettingsPreviewFrame(dark = true) { DiagnosticsPageSample() }
