package com.example.ui.popups

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.BuildConfig
import com.example.R
import com.example.update.AppUpdateChecker
import com.example.update.InAppUpdater
import com.example.update.UpdateNetworkPolicy

/** What the launch pop-ups remember on this phone. Nothing here leaves the device. */
private class LaunchPopupPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("maximus_launch_popups", Context.MODE_PRIVATE)
    var communityHidden: Boolean
        get() = prefs.getBoolean("community_hidden", false)
        set(value) = prefs.edit().putBoolean("community_hidden", value).apply()
    val snoozedBuild: Int get() = prefs.getInt("update_snoozed_build", 0)
    val snoozedUntil: Long get() = prefs.getLong("update_snoozed_until", 0L)
    fun snooze(build: Int, until: Long) =
        prefs.edit().putInt("update_snoozed_build", build).putLong("update_snoozed_until", until).apply()
}

/** Once per app process, so rotating the phone or returning to the app does not show them again. */
private object LaunchPopupSession {
    var communityHandled = false
    var updateHandled = false
}

/**
 * The pop-ups shown when the app starts: first "join the community" (unless the user chose
 * "Don't show again"), then "new update available" when GitHub has a newer release build.
 */
@Composable
fun LaunchPopups() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val prefs = remember { LaunchPopupPrefs(context.applicationContext) }
    val emblem = painterResource(R.drawable.img_maximus_emblem)
    var showCommunity by remember { mutableStateOf(!LaunchPopupSession.communityHandled && !prefs.communityHidden) }
    var update by remember { mutableStateOf<AppUpdateChecker.ReleaseInfo?>(null) }
    var installerRelease by remember { mutableStateOf<AppUpdateChecker.ReleaseInfo?>(null) }
    var installing by remember { mutableStateOf(false) }
    var installJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var progress by remember { mutableStateOf(0) }
    var verifiedFile by remember { mutableStateOf<java.io.File?>(null) }
    var installError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val installedBuild = BuildConfig.RELEASE_BUILD

    fun open(url: String) {
        runCatching { uriHandler.openUri(url) }
    }

    LaunchedEffect(Unit) {
        if (LaunchPopupSession.updateHandled) return@LaunchedEffect
        val latest = runCatching {
            UpdateNetworkPolicy.requireAllowed(context)
            AppUpdateChecker.fetchLatest()
        }.getOrNull() ?: return@LaunchedEffect
        if (AppUpdateChecker.shouldShow(latest, BuildConfig.VERSION_NAME, installedBuild, prefs.snoozedBuild, prefs.snoozedUntil, System.currentTimeMillis())) {
            update = latest
        }
    }

    installerRelease?.let { candidate ->
        VerifiedUpdateDialog(
            release = candidate,
            installedBuild = installedBuild,
            downloading = installing,
            progress = progress,
            verified = verifiedFile?.isFile == true,
            error = installError,
            onDismiss = {
                installJob?.cancel()
                installing = false
                installerRelease = null
            },
            onOfficialLink = { open(candidate.pageUrl) },
            onPrimary = {
                val installed = verifiedFile?.takeIf { it.isFile }
                val asset = candidate.apk
                when {
                    installed != null -> runCatching { InAppUpdater.install(context, installed) }
                        .onSuccess { if (!it) installError = "Allow installation for Maximus in Android Settings, then come back and tap Install." }
                        .onFailure { installError = it.message ?: "Android installer unavailable" }
                    asset != null -> {
                        installing = true
                        progress = 0
                        installError = null
                        installJob = scope.launch {
                            runCatching { InAppUpdater.download(context, asset) { progress = it } }
                                .onSuccess { verifiedFile = it }
                                .onFailure {
                                    if (it !is kotlinx.coroutines.CancellationException)
                                        installError = it.message ?: "APK verification/download failed"
                                }
                            installing = false
                        }
                    }
                    else -> open(candidate.pageUrl)
                }
            }
        )
        return
    }

    if (showCommunity) {
        PopupDialog(
            onDismiss = { LaunchPopupSession.communityHandled = true; showCommunity = false },
            tag = "community_popup"
        ) {
            CommunityPopupContent(
                emblem = emblem,
                onOpenLink = ::open,
                onClose = { LaunchPopupSession.communityHandled = true; showCommunity = false },
                onDontShowAgain = {
                    prefs.communityHidden = true
                    LaunchPopupSession.communityHandled = true
                    showCommunity = false
                }
            )
        }
        return
    }

    val release = update ?: return
    val later = {
        prefs.snooze(release.build, System.currentTimeMillis() + AppUpdateChecker.SNOOZE_MS)
        LaunchPopupSession.updateHandled = true
        update = null
    }
    PopupDialog(onDismiss = later, tag = "update_popup") {
        UpdatePopupContent(
            emblem = emblem,
            version = release.version,
            build = release.build,
            installedVersion = BuildConfig.VERSION_NAME.substringBefore('-'),
            installedBuild = installedBuild,
            onLater = later,
            onDownload = {
                LaunchPopupSession.updateHandled = true
                update = null
                installerRelease = release
            }
        )
    }
}

@Composable
private fun PopupDialog(onDismiss: () -> Unit, tag: String, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Scrolls on short screens, where the community card is taller than the window.
        Box(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 24.dp).testTag(tag)) { content() }
    }
}
