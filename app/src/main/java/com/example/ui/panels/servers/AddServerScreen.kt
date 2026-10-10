package com.example.ui.panels.servers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.panels.servers.ServerLocation

/** The Add server form. [fingerprint] is set once the server presented its SSH key for confirmation. */
data class AddServerUi(
    val editingId: String? = null,
    val location: ServerLocation = ServerLocation.ABROAD,
    val name: String = "",
    val host: String = "",
    val port: String = "22",
    val user: String = "root",
    val password: String = "",
    val fingerprint: String = "",
    val busy: String = "",
    val error: String = ""
) {
    val formValid: Boolean get() = host.isNotBlank() && port.toIntOrNull() in 1..65535 && user.isNotBlank() && password.isNotBlank()
}

data class AddServerActions(
    val onChange: (AddServerUi) -> Unit,
    val onContinue: () -> Unit,
    val onTrust: () -> Unit,
    val onBack: () -> Unit
)

@Composable
internal fun AddServerScreen(ui: AddServerUi, actions: AddServerActions) {
    val confirming = ui.fingerprint.isNotBlank()
    ScreenFrame(
        title = if (ui.editingId != null) "Sign in to server" else "Add server",
        subtitle = "Saved encrypted on this phone",
        onBack = actions.onBack,
        bottomBar = {
            when {
                ui.busy.isNotBlank() -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(46.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Sv.Blue, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(ui.busy, color = Sv.TextSoft, fontSize = 13.sp)
                }
                confirming -> PrimaryButton("Trust and sign in", actions.onTrust, Modifier.fillMaxWidth(), Icons.Rounded.Lock)
                else -> PrimaryButton("Continue", actions.onContinue, Modifier.fillMaxWidth(), enabled = ui.formValid)
            }
        }
    ) {
        SectionLabel("Location")
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Sv.Card)
                .border(1.dp, Sv.CardBorder, RoundedCornerShape(12.dp)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (location in listOf(ServerLocation.IRAN, ServerLocation.ABROAD)) {
                val selected = ui.location == location
                Row(
                    Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(9.dp))
                        .background(if (selected) Sv.Raised else Sv.Card)
                        .border(1.dp, if (selected) Sv.CardBorder else Sv.Card, RoundedCornerShape(9.dp))
                        .clickable(enabled = !confirming) { actions.onChange(ui.copy(location = location)) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    FlagIcon(location, height = 14.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (location == ServerLocation.IRAN) "In Iran" else "Abroad",
                        color = if (selected) Sv.Text else Sv.Muted, fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal)
                }
            }
        }
        Text(
            if (ui.location == ServerLocation.IRAN) "For relays and the inside end of Maximus Tunnel."
            else "Where your traffic leaves. Tunnels and fast protocols run here.",
            color = Sv.Dim, fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp)
        )

        SectionLabel("Connection")
        SvField(ui.name, { actions.onChange(ui.copy(name = it)) }, "Name (optional)",
            placeholder = if (ui.location == ServerLocation.IRAN) "Tehran relay" else "Frankfurt")
        SvField(ui.host, { actions.onChange(ui.copy(host = it.trim(), fingerprint = "")) }, "IP address or host name",
            placeholder = "203.0.113.7", keyboard = KeyboardType.Uri, mono = true)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SvField(ui.port, { actions.onChange(ui.copy(port = it.filter(Char::isDigit).take(5), fingerprint = "")) }, "SSH port",
                Modifier.weight(0.4f), keyboard = KeyboardType.Number, mono = true)
            SvField(ui.user, { actions.onChange(ui.copy(user = it.trim())) }, "User", Modifier.weight(0.6f), mono = true)
        }
        SvField(ui.password, { actions.onChange(ui.copy(password = it)) }, "Password", secret = true,
            supporting = "Later you can switch to key login and turn passwords off.")

        AnimatedVisibility(confirming) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Confirm the server's key")
                GroupCard {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconTile(Icons.Rounded.Fingerprint, 32.dp, accent = true)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("SSH fingerprint", color = Sv.Text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                                Text("SHA-256", color = Sv.Dim, fontSize = 12.sp)
                            }
                        }
                        Text(ui.fingerprint, color = Sv.Text, fontFamily = Mono, fontSize = 12.sp, lineHeight = 18.sp,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Sv.Inset)
                                .border(1.dp, Sv.Divider, RoundedCornerShape(8.dp)).padding(10.dp))
                        Text(
                            "If your provider's console shows a fingerprint, it should match. The app remembers this key " +
                                "and refuses the server if it ever changes, so nobody can pose as it.",
                            color = Sv.Muted, fontSize = 13.sp, lineHeight = 18.sp
                        )
                    }
                }
            }
        }
        if (ui.error.isNotBlank()) Notice(ui.error, NoticeKind.ERROR)
        Notice("Your password stays on this phone, encrypted by the Android keystore. It is never sent to the AI or written to logs.")
    }
}
