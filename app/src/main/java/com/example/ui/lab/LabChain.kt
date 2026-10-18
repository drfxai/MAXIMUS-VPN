package com.example.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Save
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.LabText

/** The two orders a Psiphon/Tor chain can run in. */
enum class ChainOrder(val first: String, val second: String, val title: String) {
    PSIPHON_TOR("psiphon", "tor", "Psiphon → Tor"),
    TOR_PSIPHON("tor", "psiphon", "Tor → Psiphon"),
    PSIPHON_MASQUE("psiphon", "masque", "Psiphon / WARP"),
    PSIPHON_MATRIX("psiphon", "matrix-transport", "Psiphon / HTTP2")
}

/** LAB "Engine chain": builds a two-hop Psiphon/Tor chain and saves it as a server to connect to. */
data class ChainUi(
    val order: ChainOrder = ChainOrder.PSIPHON_TOR,
    /** Psiphon egress country, or empty for any. */
    val region: String = "",
    val psiphonReady: Boolean = true,
    val torReady: Boolean = true,
    val masqueReady: Boolean = false,
    val matrixReady: Boolean = false,
    val saving: Boolean = false,
    /** Name of the chain last saved this session, for the confirmation line. */
    val savedName: String? = null
)

/** Egress countries offered for the Psiphon hop; the first is "any". */
internal val CHAIN_REGIONS = listOf("" to "Any", "US" to "US", "DE" to "DE", "NL" to "NL", "GB" to "GB", "FR" to "FR", "CA" to "CA")

@Composable
internal fun ChainPage(c: LabColors, s: ChainUi, onOrder: (ChainOrder) -> Unit, onRegion: (String) -> Unit, onSave: () -> Unit, onPsiphonSettings: (String) -> Unit = {}) {
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel(c) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    LabText("Send your traffic through two relays", c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
                    LabText("Your traffic enters the first engine, which dials the second, and the second reaches the site. " +
                        "Two hops that block differently are harder to cut off together than either alone.",
                        c.text2, 12.5.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 4, lineHeight = 17.sp)
                }
            }
        }
        item {
            Panel(c) {
                val settings = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
                LabText("Psiphon network settings", c.text, 14.sp, FontWeight.SemiBold)
                LabText("Import your authorized JSON settings. They are stored encrypted on this phone.", c.text3, 12.sp)
                androidx.compose.material3.OutlinedTextField(value = settings.value, onValueChange = { settings.value = it },
                    modifier = Modifier.fillMaxWidth(), label = { androidx.compose.material3.Text("Settings JSON") },
                    minLines = 2, maxLines = 5)
                SecondaryButton(c, "Save Psiphon settings", Icons.Rounded.Save, enabled = settings.value.isNotBlank()) {
                    onPsiphonSettings(settings.value)
                }
            }
        }
        item { GroupLabel(c, "Order") }
        item {
            Column(Modifier.padding(horizontal = Gutter)) {
                ChainOrder.entries.toList().chunked(2).forEach { choices ->
                    Segmented(c, choices, s.order, { it.title }, onOrder)
                }
                LabText(
                    when (s.order) {
                        ChainOrder.PSIPHON_TOR -> "Psiphon is the way in; Tor is the exit the site sees."
                        ChainOrder.TOR_PSIPHON -> "Tor is the way in; Psiphon is the exit the site sees."
                        ChainOrder.PSIPHON_MATRIX -> "Psiphon reaches its servers through a pinned MASQUE HTTP/2 carrier. The final exit is Psiphon."
                        ChainOrder.PSIPHON_MASQUE -> "Psiphon reaches its servers through WARP MASQUE (HTTP/3). The final exit is Psiphon."
                    },
                    c.text3, 12.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp), maxLines = 2, lineHeight = 16.sp
                )
            }
        }
        item { GroupLabel(c, "Psiphon exit country") }
        item {
            Column(Modifier.padding(horizontal = Gutter)) {
                Segmented(c, CHAIN_REGIONS.map { it.first }, s.region, { code -> CHAIN_REGIONS.first { it.first == code }.second }, onRegion)
                LabText("Where Psiphon leaves to. Any lets it pick the fastest.", c.text3, 12.sp,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp), maxLines = 1)
            }
        }
        val missing = buildList {
            if (!s.psiphonReady) add("Psiphon")
            if (s.order == ChainOrder.PSIPHON_MATRIX) {
                if (!s.matrixReady) add("Matrix Transport")
            } else if (s.order == ChainOrder.PSIPHON_MASQUE) {
                if (!s.masqueReady) add("WARP MASQUE")
            } else if (!s.torReady) add("Tor")
        }
        if (missing.isNotEmpty()) item {
            Panel(c) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    LabText("${missing.joinToString(" and ")} ${if (missing.size == 1) "is" else "are"} not ready in this build, so this chain can't run yet.",
                        c.okay, 12.5.sp, maxLines = 3, lineHeight = 16.sp)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = Gutter)) {
                PrimaryButton(c, if (s.saving) "Saving..." else "Save this chain", Icons.Rounded.Save, Modifier.fillMaxWidth(), enabled = !s.saving, onClick = onSave)
                s.savedName?.let { name ->
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Icon(Icons.Rounded.CheckCircle, null, tint = c.good, modifier = Modifier.width(18.dp))
                        Spacer(Modifier.width(8.dp))
                        LabText("Saved “$name”. Connect to it on the Servers screen.", c.text2, 12.5.sp, maxLines = 2, lineHeight = 16.sp)
                    }
                }
            }
        }
        item {
            Footnote(c, "Each hop runs as its own engine on the phone with a private local login. Xray still owns the " +
                "tunnel, the DNS and the kill switch, and every engine's own server check is unchanged. A chain is " +
                "slower than one hop.")
        }
    }
}
