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
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.LabText
import com.example.vpn.lab.ServiceCheck

/** LAB "Service check": the last check's results, and the live state of a running one. */
data class ServicesUi(
    val running: Boolean = false,
    /** Results so far in a running check. */
    val partial: List<ServiceCheck.Result> = emptyList(),
    val report: ServiceCheck.Report? = null
)

private fun verdictColor(c: LabColors, v: ServiceCheck.Verdict) = when (v) {
    ServiceCheck.Verdict.WORKS -> c.good
    ServiceCheck.Verdict.REGION_BLOCKED, ServiceCheck.Verdict.REFUSED -> c.okay
    ServiceCheck.Verdict.FILTERED, ServiceCheck.Verdict.NO_CONNECTION -> c.bad
    ServiceCheck.Verdict.NOT_RUN -> c.text3
}

@Composable
internal fun ServicesPage(c: LabColors, s: ServicesUi, vpnOn: Boolean, onRun: () -> Unit, onStop: () -> Unit, relativeTime: (Long) -> String) {
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel(c) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    LabText("Do the sites you use really open?", c.text, 15.sp, FontWeight.SemiBold, maxLines = 1)
                    LabText("A ping can pass while Gemini refuses the exit's country or the filter answers instead of YouTube. " +
                        "This opens each site like a browser and reads what came back.",
                        c.text2, 12.5.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 4, lineHeight = 17.sp)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = Gutter)) {
                if (s.running) SecondaryButton(c, "Stop", Icons.Rounded.Stop, Modifier.fillMaxWidth(), onClick = onStop)
                else PrimaryButton(c, if (vpnOn) "Check through the VPN" else "Check without the VPN", Icons.Rounded.Public, Modifier.fillMaxWidth(), onClick = onRun)
                LabText(
                    if (vpnOn) "Uses the connected config. Configs run by a separate engine (Psiphon, Tor, DNS tunnel) leave this app outside the tunnel, so the check shows the direct network."
                    else "Shows what this network allows directly. Connect first to check a config.",
                    c.text3, 12.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp), maxLines = 3, lineHeight = 16.sp
                )
            }
        }
        val report = s.report
        val rows = if (s.running) s.partial else report?.results.orEmpty()
        if (report != null && !s.running) {
            item { GroupLabel(c, (if (report.throughVpn) "Through the VPN" else "Without the VPN") + " · ${relativeTime(report.at)}") }
            item {
                Panel(c) {
                    KeyValue(c, "Exit", report.exit?.let { "${ServiceCheck.flag(it.country)} ${it.country} · ${it.ip}" } ?: "unknown")
                }
            }
        } else if (s.running) item { GroupLabel(c, "Checking ${rows.size + 1} of ${ServiceCheck.SERVICES.size}") }
        if (rows.isNotEmpty()) item {
            Panel(c) {
                rows.forEachIndexed { i, r ->
                    if (i > 0) Hairline(c, 14.dp)
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            LabText(r.service.title, c.text, 14.sp, FontWeight.Medium, maxLines = 1)
                            LabText(r.detail, c.text3, 12.sp, maxLines = 2, lineHeight = 16.sp)
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Badge(r.verdict.title, verdictColor(c, r.verdict))
                            r.ms?.let { LabText("$it ms", c.text2, 11.5.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 1) }
                        }
                    }
                }
            }
        } else if (!s.running) item {
            Panel(c) {
                Column(Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LabText("No check yet", c.text, 14.5.sp, FontWeight.SemiBold, maxLines = 1)
                    LabText("Connect a config and tap Check to see which sites really open through it.", c.text3, 12.5.sp,
                        modifier = Modifier.padding(top = 4.dp), maxLines = 3, lineHeight = 17.sp)
                }
            }
        }
        item {
            Footnote(c, "Signed out: a site that sets your region from your account (Gemini can) may still differ for you. " +
                "Only each site's front page is requested; nothing about you or your configs is sent.")
        }
    }
}
