package com.example.ui.protocols

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.R
import com.example.ui.theme.AppTheme

/** Inter, bundled (Latin subset) so the screen looks the same on every device. */
val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

/** Palette of the Protocols screen, following the app's light/dark choice. */
data class LabColors(
    val dark: Boolean,
    val bg: Color, val glow: Color, val card: Color, val cardAlt: Color, val stroke: Color, val divider: Color,
    val text: Color, val text2: Color, val text3: Color,
    val accent: Color, val accent2: Color, val onAccent: Color, val accentSoft: Color,
    val good: Color, val okay: Color, val bad: Color, val info: Color
)

val DarkLabColors = LabColors(
    true, Color(0xFF0A0B10), Color(0xFF3B2A7A), Color(0xFF13141B), Color(0xFF1A1B24), Color(0x14FFFFFF), Color(0x0FFFFFFF),
    Color(0xFFF5F6FA), Color(0xFFA3A6B6), Color(0xFF6B6E80),
    Color(0xFFA78BFA), Color(0xFF6366F1), Color(0xFFFFFFFF), Color(0x26A78BFA),
    Color(0xFF34D399), Color(0xFFFBBF24), Color(0xFFF87171), Color(0xFF60A5FA)
)

val LightLabColors = LabColors(
    false, Color(0xFFF5F6FA), Color(0xFFDCD3FF), Color(0xFFFFFFFF), Color(0xFFF1F2F7), Color(0x140F172A), Color(0x0F0F172A),
    Color(0xFF0F1222), Color(0xFF545A70), Color(0xFF8C91A6),
    Color(0xFF7C3AED), Color(0xFF4F46E5), Color(0xFFFFFFFF), Color(0x1A7C3AED),
    Color(0xFF059669), Color(0xFFD97706), Color(0xFFDC2626), Color(0xFF2563EB)
)

val labColors: LabColors
    @Composable
    @ReadOnlyComposable
    get() = if (AppTheme.colors.isDark) DarkLabColors else LightLabColors
