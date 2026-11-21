package com.example.ui.language

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.ui.theme.AppTheme

/**
 * First-launch language picker: shown once, before any other onboarding, until a language is chosen.
 * The texts follow the highlighted card, so a Russian speaker reads Russian before tapping Continue.
 */
@Composable
fun LanguagePickerScreen(
    initial: AppLanguage,
    onContinue: (AppLanguage) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = AppTheme.colors
    var selected by rememberSaveable { mutableStateOf(initial) }
    val context = LocalContext.current
    val localized = remember(selected) { LanguageStore.contextFor(context, selected) }

    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val appear by animateFloatAsState(if (shown) 1f else 0f, tween(650, easing = FastOutSlowInEasing), label = "appear")

    val glow = rememberInfiniteTransition(label = "glow")
    val pulse by glow.animateFloat(
        initialValue = 0.35f, targetValue = 0.75f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(
        modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        colors.primary.copy(alpha = if (colors.isDark) 0.20f else 0.14f),
                        colors.background,
                        colors.background,
                        colors.metricDownload.copy(alpha = if (colors.isDark) 0.10f else 0.08f)
                    )
                )
            )
            .testTag("language_picker")
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp)
                .alpha(appear),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(132.dp)
                        .clip(CircleShape)
                        .background(Brush.radialGradient(listOf(colors.primary.copy(alpha = pulse * 0.55f), Color.Transparent)))
                )
                Image(
                    painter = painterResource(R.drawable.img_maximus_emblem),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(92.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, Brush.linearGradient(listOf(colors.primary, colors.metricDownload)), CircleShape)
                )
            }
            Spacer(Modifier.height(18.dp))
            Text(
                "MAXIMUS VPN",
                color = colors.textPrimary,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )
            Spacer(Modifier.height(10.dp))
            AnimatedContent(
                targetState = selected,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(180)) },
                label = "texts"
            ) { shownLanguage ->
                val texts = remember(shownLanguage) { LanguageStore.contextFor(context, shownLanguage) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        texts.getString(R.string.lang_picker_welcome),
                        color = colors.textSecondary,
                        fontSize = 15.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 21.sp
                    )
                    Spacer(Modifier.height(28.dp))
                    Text(
                        texts.getString(R.string.lang_picker_title),
                        color = colors.textPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
            Spacer(Modifier.height(18.dp))
            Column(
                Modifier.widthIn(max = 460.dp).fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AppLanguage.entries.forEach { language ->
                    LanguageCard(
                        language = language,
                        caption = LanguageStore.contextFor(context, language).getString(R.string.lang_picker_card_caption),
                        selected = language == selected,
                        onSelect = { selected = language }
                    )
                }
            }
            Spacer(Modifier.height(28.dp))
            Button(
                onClick = { onContinue(selected) },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = colors.onPrimary),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                modifier = Modifier
                    .widthIn(max = 460.dp)
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("language_continue")
            ) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(colors.primary, colors.primaryDark))),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        localized.getString(R.string.lang_picker_continue),
                        color = colors.onPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text(
                localized.getString(R.string.lang_picker_hint),
                color = colors.textMuted,
                fontSize = 12.5.sp,
                textAlign = TextAlign.Center,
                lineHeight = 17.sp
            )
        }
    }
}

@Composable
private fun LanguageCard(language: AppLanguage, caption: String, selected: Boolean, onSelect: () -> Unit) {
    val colors = AppTheme.colors
    val scale by animateFloatAsState(if (selected) 1f else 0.97f, tween(220), label = "scale")
    val container by animateColorAsState(
        if (selected) colors.surfaceCardHighlight else colors.surfaceCard, tween(220), label = "container"
    )
    val border = if (selected) {
        BorderStroke(1.5.dp, Brush.linearGradient(listOf(colors.primary, colors.metricDownload)))
    } else {
        BorderStroke(1.dp, colors.borderSubtle)
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = container,
        border = border,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(20.dp))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .testTag("language_${language.tag}")
    ) {
        Row(
            Modifier.heightIn(min = 84.dp).padding(horizontal = 18.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (selected) Brush.linearGradient(listOf(colors.primary, colors.metricDownload))
                        else Brush.linearGradient(listOf(colors.surfaceElevated, colors.surfacePill))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    language.flag,
                    fontSize = 24.sp
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(language.nativeName, color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Language, null, tint = colors.textMuted, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(caption, color = colors.textSecondary, fontSize = 13.sp)
                }
            }
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(if (selected) colors.primary else Color.Transparent)
                    .border(1.5.dp, if (selected) colors.primary else colors.borderMedium, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (selected) Icon(Icons.Default.Check, null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
            }
        }
    }
}
