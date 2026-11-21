package com.example

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.language.AppLanguage
import com.example.ui.language.LanguagePickerScreen
import com.example.ui.language.LanguageStore
import com.example.ui.navigation.MainApp
import com.example.ui.theme.AppTheme
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.DiagnosticsViewModel
import com.example.ui.viewmodel.ServerViewModel
import com.example.ui.viewmodel.SettingsViewModel
import com.example.ui.viewmodel.VpnViewModel
import com.example.vpn.VpnController

class MainActivity : ComponentActivity() {

    private val vpnViewModel: VpnViewModel by viewModels()
    private val serverViewModel: ServerViewModel by viewModels()
    private val diagnosticsViewModel: DiagnosticsViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The VPN foreground service remains usable if notifications are declined. */ }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            vpnViewModel.onPermissionGranted(this)
        } else {
            vpnViewModel.onPermissionDenied()
            Toast.makeText(this, this.getString(R.string.ui_vpn_permission_is_required_to), Toast.LENGTH_LONG).show()
        }
    }

    /** First launch: the language picker comes before any other onboarding or permission prompt. */
    private var needsLanguageChoice by mutableStateOf(false)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LanguageStore.wrap(newBase))
    }

    private fun requestNotificationPermissionIfNeeded() {
        // Android 13+ requires a runtime grant for the persistent VPN status
        // notification. Request it while the activity is visible, rather than
        // letting foreground-service startup fail or silently hide its status.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun onLanguageChosen(language: AppLanguage) {
        val showing = resources.configuration.locales[0]?.language
        needsLanguageChoice = false
        LanguageStore.apply(this, language)
        // A changed language recreates the activity, and onCreate asks for notifications then.
        if (showing == language.tag) requestNotificationPermissionIfNeeded()
    }

    fun launchVpnPermission(intent: Intent) {
        vpnPermissionLauncher.launch(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        needsLanguageChoice = LanguageStore.needsFirstLaunchChoice(this)
        if (!needsLanguageChoice) requestNotificationPermissionIfNeeded()

        setContent {
            val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

            MyApplicationTheme(darkTheme = settings.darkTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = AppTheme.colors.background
                ) {
                    if (needsLanguageChoice) {
                        LanguagePickerScreen(
                            initial = AppLanguage.defaultFor(LanguageStore.deviceLanguageTag()),
                            onContinue = { onLanguageChosen(it) }
                        )
                    } else MainApp(
                        vpnViewModel = vpnViewModel,
                        serverViewModel = serverViewModel,
                        diagnosticsViewModel = diagnosticsViewModel,
                        settingsViewModel = settingsViewModel,
                        onRequestVpnPermission = {
                            val prepareIntent = VpnController.prepareVpn(this)
                            if (prepareIntent != null) {
                                vpnPermissionLauncher.launch(prepareIntent)
                            }
                        }
                    )
                }
            }
        }
    }
}
