package com.example.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Enterprise design palette tokens for Maximus VPN.
 * Designed for high-contrast legibility, executive sophistication, and vivid telemetry indicators.
 */
data class AppColors(
    val isDark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceCard: Color,
    val surfaceCardHighlight: Color,
    val surfaceInput: Color,
    val surfaceElevated: Color,
    val surfacePill: Color,
    val borderSubtle: Color,
    val borderMedium: Color,
    val borderFocused: Color,
    val textPrimary: Color,
    val textLight: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val textFaint: Color,
    val primary: Color,
    val primaryLight: Color,
    val primaryDark: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val statusConnected: Color,
    val statusWarning: Color,
    val statusError: Color,
    val metricDownload: Color,
    val metricUpload: Color,
    val consoleBackground: Color,
    val consoleText: Color,
    val headerBadgeBackground: Color,
    val headerBadgeBorder: Color,
    val toggleTrack: Color,
    val toggleThumb: Color
)

val DarkAppColors = AppColors(
    isDark = true,
    background = Color(0xFF060711),
    surface = Color(0xFF101022),
    surfaceCard = Color(0xFF17172C),
    surfaceCardHighlight = Color(0xFF24203D),
    surfaceInput = Color(0xFF14151D),
    surfaceElevated = Color(0xFF1E202B),
    surfacePill = Color(0xFF282A38),
    borderSubtle = Color(0xFF262837),
    borderMedium = Color(0xFF383B4E),
    borderFocused = Color(0xFFC7A9FF),
    textPrimary = Color(0xFFF3F4F8),
    textLight = Color(0xFFE2E4EE),
    textSecondary = Color(0xFF989AA8),
    textMuted = Color(0xFF686A7A),
    textFaint = Color(0xFF424454),
    primary = Color(0xFFC7A9FF),
    primaryLight = Color(0xFFE8DEF8),
    primaryDark = Color(0xFF9A82DB),
    onPrimary = Color(0xFF201336),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFE8DEF8),
    statusConnected = Color(0xFF4ADE80),
    statusWarning = Color(0xFFFBBF24),
    statusError = Color(0xFFF87171),
    metricDownload = Color(0xFFA8C7FA),
    metricUpload = Color(0xFFF2B8B5),
    consoleBackground = Color(0xFF0D0E14),
    consoleText = Color(0xFFC7A9FF),
    headerBadgeBackground = Color(0xFF1E202B),
    headerBadgeBorder = Color(0xFF383B4E),
    toggleTrack = Color(0xFF1A1C26),
    toggleThumb = Color(0xFF2B2D3C)
)

val LightAppColors = AppColors(
    isDark = false,
    background = Color(0xFFF5F3FF),
    surface = Color(0xFFFFFFFF),
    surfaceCard = Color(0xFFFCFAFF),
    surfaceCardHighlight = Color(0xFFF0ECFF),
    surfaceInput = Color(0xFFF1F5F9),
    surfaceElevated = Color(0xFFEEF2F6),
    surfacePill = Color(0xFFE2E8F0),
    borderSubtle = Color(0xFFDEDBEE),
    borderMedium = Color(0xFFCBD5E1),
    borderFocused = Color(0xFF4433AD),
    textPrimary = Color(0xFF0F172A),
    textLight = Color(0xFF1E293B),
    textSecondary = Color(0xFF475569),
    textMuted = Color(0xFF8290A4),
    textFaint = Color(0xFFCBD5E1),
    primary = Color(0xFF4433AD),
    primaryLight = Color(0xFF6550D1),
    primaryDark = Color(0xFF30247D),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEDE9FE),
    onPrimaryContainer = Color(0xFF4C1D95),
    statusConnected = Color(0xFF059669),
    statusWarning = Color(0xFFD97706),
    statusError = Color(0xFFDC2626),
    metricDownload = Color(0xFF2563EB),
    metricUpload = Color(0xFFE11D48),
    consoleBackground = Color(0xFF0F172A),
    consoleText = Color(0xFFE2E8F0),
    headerBadgeBackground = Color(0xFFEDE9FE),
    headerBadgeBorder = Color(0xFFDDD6FE),
    toggleTrack = Color(0xFFE2E8F0),
    toggleThumb = Color(0xFFFFFFFF)
)

val LocalAppColors = staticCompositionLocalOf { DarkAppColors }

object AppTheme {
    val colors: AppColors
        @Composable
        @ReadOnlyComposable
        get() = LocalAppColors.current
}

// Direct static token aliases for backwards compatibility
val SophisticatedPurple = Color(0xFFC7A9FF)
val SophisticatedPurpleLight = Color(0xFFE8DEF8)
val SophisticatedPurpleDark = Color(0xFF9A82DB)

val MetricDownloadBlue = Color(0xFFA8C7FA)
val MetricUploadRose = Color(0xFFF2B8B5)
val StatusConnectedGreen = Color(0xFF4ADE80)
val StatusWarningAmber = Color(0xFFFBBF24)
val StatusErrorRed = Color(0xFFF87171)

val BackgroundDark = Color(0xFF060711)
val SurfaceDark = Color(0xFF101022)
val SurfaceCard = Color(0xFF17172C)
val SurfaceCardHighlight = Color(0xFF24203D)
val SurfaceInput = Color(0xFF14151D)
val SurfaceElevated = Color(0xFF1E202B)
val SurfacePill = Color(0xFF282A38)
val BorderSubtle = Color(0xFF262837)
val BorderMedium = Color(0xFF383B4E)

val TextPrimary = Color(0xFFF3F4F8)
val TextLight = Color(0xFFE2E4EE)
val TextSecondary = Color(0xFF989AA8)
val TextMuted = Color(0xFF686A7A)
val TextFaint = Color(0xFF424454)

val StatusConnected = StatusConnectedGreen
val StatusConnecting = StatusWarningAmber
val StatusDisconnected = Color(0xFF808080)
val StatusFailed = StatusErrorRed
