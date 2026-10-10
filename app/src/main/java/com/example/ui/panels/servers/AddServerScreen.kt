package com.example.ui.panels.servers

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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
        subtitle = "Entered once, kept encrypted on this phone",
        onBack = actions.onBack,
        bottomBar = {
            when {
                ui.busy.isNotBlank() -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(ui.busy, color = Sv.Muted, fontSize = 13.sp)
                    Shimmer(Modifier.fillMaxWidth().height(6.dp))
                }
                confirming -> PrimaryButton("Trust and sign in", actions.onTrust, Modifier.fillMaxWidth(), Icons.Rounded.Lock)
                else -> PrimaryButton("Continue", actions.onContinue, Modifier.fillMaxWidth(), enabled = ui.formValid)
            }
        }
    ) {
        SectionLabel("Where is it?")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for (location in listOf(ServerLocation.IRAN, ServerLocation.ABROAD)) {
                val selected = ui.location == location
                Surface(
                    modifier = Modifier.weight(1f).clickable(enabled = !confirming) { actions.onChange(ui.copy(location = location)) },
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) Sv.AccentSoft else Sv.Card,
                    border = BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) Sv.Accent else Sv.CardBorder)
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlagIcon(location, height = 22.dp)
                        Text(if (location == ServerLocation.IRAN) "In Iran" else "Abroad", color = Sv.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (location == ServerLocation.IRAN) "Relays and the inside end of a tunnel" else "Where your traffic leaves",
                            color = Sv.Muted, fontSize = 11.sp
                        )
                    }
                }
            }
        }

        SectionLabel("Server")
        SvField(ui.name, { actions.onChange(ui.copy(name = it)) }, "Name (optional)", placeholder = if (ui.location == ServerLocation.IRAN) "Tehran relay" else "Frankfurt")
        SvField(ui.host, { actions.onChange(ui.copy(host = it.trim(), fingerprint = "")) }, "IP address or host name",
            placeholder = "203.0.113.7", keyboard = KeyboardType.Uri)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SvField(ui.port, { actions.onChange(ui.copy(port = it.filter(Char::isDigit).take(5), fingerprint = "")) }, "SSH port",
                Modifier.weight(0.4f), keyboard = KeyboardType.Number)
            SvField(ui.user, { actions.onChange(ui.copy(user = it.trim())) }, "User", Modifier.weight(0.6f))
        }
        SvField(ui.password, { actions.onChange(ui.copy(password = it)) }, "Password", secret = true,
            supporting = "After adding you can switch to key login and turn passwords off.")

        AnimatedVisibility(confirming) {
            SvCard {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Fingerprint, null, tint = Sv.Blue)
                        Spacer(Modifier.width(8.dp))
                        Text("Confirm the server's key", color = Sv.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text(ui.fingerprint, color = Sv.Blue, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
                    Text(
                        "This is the server's SSH fingerprint. If your provider's console shows one, it should match. " +
                            "The app remembers it and refuses any server that later shows a different key, so nobody can pose as your server.",
                        color = Sv.Muted, fontSize = 12.sp, lineHeight = 17.sp
                    )
                }
            }
        }
        if (ui.error.isNotBlank()) Notice(ui.error, NoticeKind.ERROR)
        Notice("Your password stays on this phone, encrypted by Android's keystore. It is never sent to the AI or written to logs.")
    }
}
