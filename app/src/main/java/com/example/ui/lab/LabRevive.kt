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
import androidx.compose.material.icons.rounded.Healing
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.LabText
import com.example.vpn.lab.ConfigRevival

/** LAB "Revive configs": the last run's results and the live progress of a running one. */
data class RevivalUi(
    /** Saved configs revival can work on (Cloudflare-fronted TLS). */
    val eligible: Int = 0,
    val running: Boolean = false,
    val done: Int = 0,
    val total: Int = 0,
    val current: String? = null,
    val results: List<ConfigRevival.Result> = emptyList(),
    val ranAt: Long? = null
)

private fun revivalColor(c: LabColors, o: ConfigRevival.Outcome) = when (o) {
    ConfigRevival.Outcome.REVIVED -> c.good
    ConfigRevival.Outcome.ALREADY_WORKING -> c.info
    ConfigRevival.Outcome.NOT_REVIVED -> c.bad
    ConfigRevival.Outcome.NOT_TESTED -> c.text3
}

@Composable
internal fun RevivePage(c: LabColors, r: RevivalUi, vpnOn: Boolean, busy: Boolean, onRevive: () -> Unit, onStop: () -> Unit, relativeTime: (Long) -> String) {
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel(c) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    LabText("Cloudflare configs that stopped working", c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
                    LabText("BPB and other Workers, Pages and CDN configs often die because the network blocks how they connect, " +
                        "not because the server is gone. LAB tries each dead one with these recipes and keeps the fastest that works.",
                        c.text2, 12.5.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 4, lineHeight = 17.sp)
                }
                Hairline(c)
                listOf(
                    "ECH via Cloudflare" to "hides the server name with Cloudflare's shared key, Chrome fingerprint",
                    "Fragment v2 and v1" to "splits the TLS hello so filters can't match it, Go TLS stack",
                    "Fingerprint and HTTP/1.1" to "Firefox or Chrome hello, HTTP/1.1 for Workers",
                    "Clean Cloudflare IP" to "an edge address that already worked on this network"
                ).forEachIndexed { i, (t, d) ->
                    if (i > 0) Hairline(c, 14.dp)
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                        LabText(t, c.text, 13.5.sp, FontWeight.Medium, maxLines = 1)
                        LabText(d, c.text3, 12.sp, maxLines = 2)
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = Gutter)) {
                if (r.running) {
                    SecondaryButton(c, "Stop", Icons.Rounded.Stop, Modifier.fillMaxWidth(), onClick = onStop)
                } else {
                    PrimaryButton(c, if (r.eligible == 1) "Revive 1 config" else "Revive ${minOf(r.eligible, ConfigRevival.MAX_CONFIGS)} configs",
                        Icons.Rounded.Healing, Modifier.fillMaxWidth(), r.eligible > 0 && !vpnOn && !busy, onClick = onRevive)
                }
                val note = when {
                    r.running -> null
                    vpnOn -> "Turn the VPN off first. Each recipe is tested with a real request, which needs the connection free."
                    busy -> "Wait for the running LAB test to finish."
                    r.eligible == 0 -> "No saved config is a Cloudflare TLS config (WebSocket, HTTPUpgrade, XHTTP, gRPC or HTTP/2 over TLS)."
                    r.eligible > ConfigRevival.MAX_CONFIGS -> "The first ${ConfigRevival.MAX_CONFIGS} of ${r.eligible} configs are tested per run."
                    else -> null
                }
                note?.let { LabText(it, c.text3, 12.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp), maxLines = 2) }
            }
        }
        if (r.running) item {
            Panel(c) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LabText(r.current ?: "Testing", c.text, 14.sp, FontWeight.Medium, Modifier.weight(1f), maxLines = 1)
                        LabText("${r.done} of ${r.total}", c.text3, 12.sp, maxLines = 1)
                    }
                    Meter(c, if (r.total == 0) 0f else r.done.toFloat() / r.total, c.okay, Modifier.padding(top = 10.dp).fillMaxWidth())
                }
            }
        }
        if (r.results.isNotEmpty()) {
            item {
                val revived = r.results.count { it.outcome == ConfigRevival.Outcome.REVIVED }
                GroupLabel(c, "$revived of ${r.results.size} revived" + (r.ranAt?.let { " · ${relativeTime(it)}" }.orEmpty()))
            }
            item {
                Panel(c) {
                    r.results.forEachIndexed { i, res ->
                        if (i > 0) Hairline(c, 14.dp)
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                LabText(res.name, c.text, 14.sp, FontWeight.Medium, maxLines = 1)
                                LabText(res.detail, c.text3, 12.sp, maxLines = 2, lineHeight = 16.sp)
                            }
                            Spacer(Modifier.width(10.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Badge(res.outcome.title, revivalColor(c, res.outcome))
                                res.latencyMs?.let { LabText("$it ms", c.text2, 11.5.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1) }
                            }
                        }
                    }
                }
            }
        } else if (!r.running) item {
            Panel(c) {
                Column(Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LabText("No revival run yet", c.text, 14.5.sp, FontWeight.SemiBold, maxLines = 1)
                    LabText("Turn the VPN off and tap Revive. Configs that already work are left alone.", c.text3, 12.5.sp,
                        modifier = Modifier.padding(top = 4.dp), maxLines = 3, lineHeight = 17.sp)
                }
            }
        }
        item {
            Footnote(c, "Saved configs are never changed. A revived config uses its working recipe the next time it connects, " +
                "and goes back to its saved settings if the recipe stops working. Certificates are always checked.")
        }
    }
}
