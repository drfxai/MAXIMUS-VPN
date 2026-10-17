package com.example.ui.panels

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.panels.cloudflare.TokenTemplate
import com.example.ui.panels.servers.Sv

/**
 * The one-tap token steps shared by Smart domains and the Cloudflare tab: create the token on
 * Cloudflare's own page (everything filled in), paste it back, confirm. Each screen passes its colours.
 */
internal data class StepColors(
    val text: Color,
    val muted: Color,
    val dim: Color,
    val accent: Color,
    val accentSoft: Color,
    val raised: Color
)

/** The server screens' colours (Smart domains). */
internal val SvStepColors = StepColors(
    text = Sv.Text, muted = Sv.Muted, dim = Sv.Dim, accent = Sv.Blue, accentSoft = Sv.AccentSoft, raised = Sv.Raised
)

/** One numbered step: the number, the title, and what goes under it. */
@Composable
internal fun TokenStep(
    number: Int,
    title: String,
    active: Boolean,
    colors: StepColors = SvStepColors,
    done: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.Top) {
        Box(
            Modifier.size(26.dp).clip(CircleShape).background(if (active || done) colors.accentSoft else colors.raised),
            contentAlignment = Alignment.Center
        ) {
            Text(
                if (done) "✓" else number.toString(), color = if (active || done) colors.accent else colors.muted,
                fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
            content()
        }
    }
}

@Composable
internal fun StepText(text: String, colors: StepColors = SvStepColors) =
    Text(text, color = colors.muted, fontSize = 13.sp, lineHeight = 18.sp)

/** "Opens Cloudflare with everything filled in. You only tap Continue to summary and Create Token there." */
@Composable
internal fun CloudflarePageText(colors: StepColors = SvStepColors) {
    val strong = SpanStyle(color = colors.text, fontWeight = FontWeight.SemiBold)
    Text(
        buildAnnotatedString {
            append("Opens Cloudflare with everything filled in. You only tap ")
            withStyle(strong) { append("Continue to summary") }
            append(" and ")
            withStyle(strong) { append("Create Token") }
            append(" there, then copy it.")
        },
        color = colors.muted, fontSize = 13.sp, lineHeight = 18.sp
    )
}

/** The token's name and permissions as Cloudflare's page will show them. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TokenChips(name: String, labels: List<String>, colors: StepColors = SvStepColors) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf("Name: $name") + labels).forEach { label ->
            Text(
                label, color = colors.muted, fontSize = 12.5.sp, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(colors.raised).padding(horizontal = 9.dp, vertical = 5.dp)
            )
        }
    }
}

/** Why the last tap is the user's, and what the token can do. */
@Composable
internal fun TokenFootnote(extra: String, colors: StepColors = SvStepColors) {
    Text(
        "Cloudflare does not let any app create a token for you; the last tap on Cloudflare's own page is yours. $extra",
        color = colors.dim, fontSize = 12.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 2.dp)
    )
}

/** The clipboard text when it has the shape of a Cloudflare token, else null. Never logged. */
internal fun copiedToken(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    // Looking at the description first does not read the clip, so nothing is read unless it is text.
    if (cm?.primaryClipDescription?.hasMimeType("text/*") != true) null
    else cm.primaryClip?.getItemAt(0)?.text?.toString()?.trim()?.takeIf { TokenTemplate.looksLikeToken(it) }
}.getOrNull()
