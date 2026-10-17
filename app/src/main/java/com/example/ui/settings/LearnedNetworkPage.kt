package com.example.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.ui.lab.Footnote
import com.example.ui.lab.Gutter
import com.example.ui.lab.Hairline
import com.example.ui.lab.Panel
import com.example.ui.lab.SecondaryButton
import com.example.ui.lab.TitleBar
import com.example.ui.protocols.LabText
import com.example.ui.protocols.labColors

/**
 * Settings → Smart Connect → "Learned network data": what Smart Connect remembers per network, and a way to
 * forget it (with a confirm step). Only carrier names and route counts are shown; no Wi-Fi name is kept.
 */
@Composable
fun LearnedNetworkPage(rows: List<LearnedNetworks.Row>, onClear: () -> Unit, onDismiss: () -> Unit) {
    val c = labColors
    var confirm by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(c.bg).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            TitleBar(c, "Settings", "Smart Connect", Icons.AutoMirrored.Rounded.ArrowBack, onDismiss)
            Spacer(Modifier.height(10.dp))
            Panel(c) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    LabText("Learned network data", c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
                    LabText(LearnedNetworks.explanation, c.text2, 12.5.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 3, lineHeight = 17.sp)
                }
                if (rows.isEmpty()) {
                    Hairline(c)
                    LabText("Nothing learned yet", c.text3, 13.sp, modifier = Modifier.padding(14.dp), maxLines = 1)
                }
                rows.forEach { r ->
                    Hairline(c)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        LabText(r.name, c.text2, 13.5.sp, modifier = Modifier.weight(1f), maxLines = 1)
                        Spacer(Modifier.width(10.dp))
                        LabText(r.count, c.text2, 13.sp, maxLines = 1)
                    }
                }
            }
            SecondaryButton(c, "Clear learned network data", null, Modifier.padding(horizontal = Gutter).padding(top = 12.dp).fillMaxWidth(),
                color = c.bad, onClick = { confirm = true })
            Footnote(c, LearnedNetworks.FOOTER)
        }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Clear learned network data?", color = c.text) },
                text = { Text(LearnedNetworks.FOOTER, color = c.text2) },
                confirmButton = {
                    TextButton(onClick = { confirm = false; onClear() }) { Text("Clear", color = c.bad, fontWeight = FontWeight.SemiBold) }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel", color = c.text2) } },
                containerColor = c.card
            )
        }
    }
}
