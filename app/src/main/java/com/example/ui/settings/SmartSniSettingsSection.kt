package com.example.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.AppTheme
import com.example.vpn.smartsni.SmartSniSettings

/**
 * Build 181 Phase 5: Smart SNI, Smart ECH and the clean-IP scanner (Settings → Anti-censorship), in the page's own
 * group/switch/field style. Values live in [SmartSniSettings]; an invalid manual value is shown with its reason and
 * never saved; an empty field returns to automatic.
 */
@Composable
internal fun SmartSniSettingsSection() {
    var smartSni by rememberSaveable { mutableStateOf(SmartSniSettings.smartSni) }
    var smartEch by rememberSaveable { mutableStateOf(SmartSniSettings.smartEch) }
    var cleanIp by rememberSaveable { mutableStateOf(SmartSniSettings.cleanIpScan) }

    SettingsSectionLabel(stringResource(R.string.ui_smart_sni_section))
    SettingsGroup {
        SettingsSwitchRow(
            title = stringResource(R.string.ui_smart_sni),
            subtitle = stringResource(R.string.ui_smart_sni_hint),
            icon = Icons.Default.Lock,
            checked = smartSni,
            onCheckedChange = { smartSni = it; SmartSniSettings.setFlag(SmartSniSettings.SMART_SNI, it) }
        )
        ManualField(SmartSniSettings.MANUAL_SNI, stringResource(R.string.ui_smart_sni_manual),
            stringResource(R.string.ui_smart_sni_manual_hint), "www.speedtest.net", R.string.ui_invalid_domain)
        SettingsDivider(inset = false)
        SettingsSwitchRow(
            title = stringResource(R.string.ui_smart_ech),
            subtitle = stringResource(R.string.ui_smart_ech_hint),
            icon = Icons.Default.Dns,
            checked = smartEch,
            onCheckedChange = { smartEch = it; SmartSniSettings.setFlag(SmartSniSettings.SMART_ECH, it) }
        )
        ManualField(SmartSniSettings.MANUAL_ECH_OUTER, stringResource(R.string.ui_smart_ech_outer),
            stringResource(R.string.ui_smart_ech_manual_hint), "cloudflare-ech.com", R.string.ui_invalid_domain)
        ManualField(SmartSniSettings.MANUAL_ECH_CONFIG, stringResource(R.string.ui_smart_ech_config),
            null, "AEX+DQBB…", R.string.ui_invalid_ech_config, singleLine = false)
        SettingsDivider(inset = false)
        SettingsSwitchRow(
            title = stringResource(R.string.ui_clean_ip_scanner),
            subtitle = stringResource(R.string.ui_clean_ip_scanner_hint),
            icon = Icons.Default.Speed,
            checked = cleanIp,
            onCheckedChange = { cleanIp = it; SmartSniSettings.setFlag(SmartSniSettings.CLEAN_IP_SCAN, it) }
        )
    }
}

@Composable
private fun ManualField(key: String, label: String, hint: String?, placeholder: String, invalid: Int, singleLine: Boolean = true) {
    var text by rememberSaveable(key) { mutableStateOf(SmartSniSettings.read(key).orEmpty()) }
    var error by rememberSaveable(key) { mutableStateOf(false) }
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
        Text(label, color = AppTheme.colors.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        if (hint != null) {
            Spacer(Modifier.height(2.dp))
            Text(hint, color = AppTheme.colors.textMuted, fontSize = 12.5.sp)
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { v ->
                text = v
                error = SmartSniSettings.setManual(key, v) != null
            },
            placeholder = { Text(placeholder, fontSize = 12.sp) },
            isError = error,
            supportingText = if (error) { { Text(stringResource(invalid)) } } else null,
            colors = settingsFieldColors(),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else 4
        )
    }
}
