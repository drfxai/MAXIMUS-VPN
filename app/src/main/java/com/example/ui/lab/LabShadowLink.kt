package com.example.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.protocols.LabColors
import com.example.ui.protocols.LabText
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import com.example.vpn.connectivity.TransportCapabilityEngine.Transport
import com.example.vpn.coordinator.EngineCoordinator
import com.example.vpn.lab.ServiceCheck
import com.example.vpn.shadowlink.Egress
import com.example.vpn.shadowlink.ShadowLinkReport

/** LAB "ShadowLink": the last discovery pass — one fused read of the network and the path order it suggests. */
data class ShadowLinkUi(
    val running: Boolean = false,
    val report: ShadowLinkReport? = null,
    /** The user's saved configs, ordered to try for this network (empty when none are saved). */
    val plan: List<EngineCoordinator.PlannedConfig> = emptyList()
)

private fun egressColor(c: LabColors, e: Egress) = when (e) {
    Egress.OPEN -> c.good
    Egress.FILTERED -> c.okay
    Egress.TUNNEL_ONLY -> c.info
    Egress.BLOCKED -> c.bad
    Egress.UNKNOWN -> c.text3
}

private fun transportLabel(t: Transport) = when (t) {
    Transport.UDP -> "UDP / QUIC"
    Transport.TCP_TLS -> "TCP + TLS"
    Transport.TCP_PLAIN -> "Plain TCP"
}

@Composable
internal fun ShadowLinkPage(c: LabColors, s: ShadowLinkUi, vpnOn: Boolean, onRun: () -> Unit, onStop: () -> Unit, onSelect: (String, String) -> Unit, relativeTime: (Long) -> String) {
    LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Panel(c) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    LabText("One pass reads the network and picks a path", c.text, 15.sp, FontWeight.SemiBold, maxLines = 2)
                    LabText("ShadowLink fuses the network measurement, the state read, the service checks, the firewall and DNS honesty into a single verdict, then orders the transports to try.",
                        c.text2, 12.5.sp, modifier = Modifier.padding(top = 4.dp), maxLines = 5, lineHeight = 17.sp)
                }
            }
        }
        item {
            Column(Modifier.padding(horizontal = Gutter)) {
                if (s.running) SecondaryButton(c, "Stop", Icons.Rounded.Stop, Modifier.fillMaxWidth(), onClick = onStop)
                else PrimaryButton(c, if (s.report == null) "Run discovery" else "Run discovery again", Icons.Rounded.Shield, Modifier.fillMaxWidth(), onClick = onRun)
                LabText(
                    if (vpnOn) "The VPN is on, so this reads the path through it; the captive-portal check is skipped."
                    else "Reads what this network allows directly, with the VPN off.",
                    c.text3, 12.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp), maxLines = 3, lineHeight = 16.sp
                )
            }
        }

        val r = s.report
        if (r != null && !s.running) {
            // Summary
            item {
                Panel(c) {
                    Row(Modifier.fillMaxWidth().padding(14.dp)) {
                        Icon(Icons.Rounded.Shield, null, tint = c.accent, modifier = Modifier.size(16.dp).padding(top = 1.dp))
                        Spacer(Modifier.width(9.dp))
                        LabText(r.summary, c.text2, 12.5.sp, maxLines = 6, lineHeight = 17.sp)
                    }
                }
            }
            // Verdict + chips
            item {
                Panel(c) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            LabText(r.networkLabel, c.text, 14.5.sp, FontWeight.SemiBold, maxLines = 1)
                            LabText("measured ${relativeTime(r.at)}", c.text3, 11.5.sp, maxLines = 1)
                        }
                        Spacer(Modifier.width(10.dp))
                        Badge(r.egress.title, egressColor(c, r.egress))
                    }
                    Hairline(c)
                    FlowChips(c) {
                        Chip(c, c.okay, "State", r.state.title)
                        Chip(c, if (r.firewall == "Other network") c.text3 else c.bad, "Firewall", r.firewall)
                        Chip(c, if (r.captivePortal == "Open") c.good else c.text3, "Portal", r.captivePortal)
                        Chip(c, if (r.dnsHonest == true) c.good else if (r.dnsHonest == false) c.bad else c.text3,
                            "DNS", if (r.dnsHonest == true) "Trusted" else if (r.dnsHonest == false) "Tampered" else "Unknown")
                    }
                }
            }
            // Services
            if (r.workingServices.isNotEmpty() || r.blockedServices.isNotEmpty()) item {
                Column {
                    GroupLabel(c, "What reaches the internet")
                    Panel(c) {
                        Column(Modifier.padding(14.dp)) {
                            FlowChips(c) {
                                r.workingServices.forEach { ServicePill(c, it, true) }
                                r.blockedServices.forEach { ServicePill(c, it, false) }
                            }
                            r.exitCountry?.let {
                                Spacer(Modifier.size(10.dp))
                                LabText("Exit · ${ServiceCheck.flag(it)} $it", c.text3, 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
            // Suggested connect order (the user's own configs, ranked for this network)
            if (s.plan.isNotEmpty()) item {
                Column {
                    GroupLabel(c, "Suggested connect order")
                    Panel(c) {
                        s.plan.forEachIndexed { i, p ->
                            if (i > 0) Hairline(c, 14.dp)
                            Row(Modifier.fillMaxWidth().clickable { onSelect(p.id, p.label) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                                    LabText("${i + 1}", c.accent, 11.sp, FontWeight.Bold, maxLines = 1)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    LabText(p.label, c.text, 13.5.sp, FontWeight.SemiBold, maxLines = 1)
                                    LabText(p.reason, c.text3, 12.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 2, lineHeight = 16.sp)
                                }
                                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.text3, modifier = Modifier.padding(start = 4.dp).size(18.dp))
                            }
                        }
                    }
                    LabText("Tap one to select it on the Servers screen. Nothing connects until you tap Connect there.",
                        c.text3, 11.5.sp, modifier = Modifier.padding(start = Gutter + 4.dp, top = 8.dp), maxLines = 2, lineHeight = 16.sp)
                }
            }
            // Recommended path order
            if (r.recommendedTransports.isNotEmpty()) item {
                Column {
                GroupLabel(c, "Recommended path order")
                Panel(c) {
                    r.recommendedTransports.forEachIndexed { i, hint ->
                        if (i > 0) Hairline(c, 14.dp)
                        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(c.accent.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                                LabText("${i + 1}", c.accent, 11.sp, FontWeight.Bold, maxLines = 1)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                LabText(transportLabel(hint.transport), c.text, 13.5.sp, FontWeight.SemiBold, maxLines = 1)
                                LabText(hint.reason, c.text3, 12.sp, modifier = Modifier.padding(top = 2.dp), maxLines = 3, lineHeight = 16.sp)
                            }
                        }
                    }
                }
                }
            }
            // Memory
            if (r.priorWorkingKinds.isNotEmpty()) item {
                Column {
                    GroupLabel(c, "Worked here before")
                    Panel(c) {
                        FlowChips(c, Modifier.padding(14.dp)) {
                            r.priorWorkingKinds.forEach { kind ->
                                Box(Modifier.clip(RoundedCornerShape(7.dp)).background(c.good.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                    LabText(kind, c.good, 11.sp, FontWeight.SemiBold, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        } else if (!s.running) item {
            Panel(c) {
                Column(Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LabText("No discovery yet", c.text, 14.5.sp, FontWeight.SemiBold, maxLines = 1)
                    LabText("Tap Run discovery to read this network and get the path order to try.", c.text3, 12.5.sp,
                        modifier = Modifier.padding(top = 4.dp), maxLines = 3, lineHeight = 17.sp)
                }
            }
        }
        item {
            Footnote(c, "Everything is measured on this phone and kept to an anonymous network key — never the operator's name. Nothing about you or your configs is sent.")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(c: LabColors, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { content() }
}

@Composable
private fun Chip(c: LabColors, dot: Color, key: String, value: String) {
    Row(Modifier.clip(RoundedCornerShape(9.dp)).background(c.cardAlt).padding(horizontal = 9.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(6.dp))
        LabText(key, c.text3, 10.5.sp, maxLines = 1)
        Spacer(Modifier.width(5.dp))
        LabText(value, c.text, 11.5.sp, FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun ServicePill(c: LabColors, name: String, ok: Boolean) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(c.cardAlt).padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Close, null, tint = if (ok) c.good else c.bad, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(5.dp))
        LabText(name, if (ok) c.text else c.text2, 11.5.sp, maxLines = 1)
    }
}
