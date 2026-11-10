package com.example.aiuseagent.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * 品牌配色：
 * - 浅色模式参照「极简白」：纯白画布、近黑正文、一个链接蓝强调色、发丝级分隔线。
 * - 深色模式参照「深色 SaaS」：近黑画布、逐级提亮的炭黑面板、唯一薰衣草蓝强调色。
 *
 * 两套都由同一组语义 token 驱动，页面只使用 MaterialTheme.colorScheme 或 [LocalDisper]，
 * 不直接写死颜色，因此主题切换不需要改任何页面。
 */
data class DisperPalette(
    val canvas: Color,
    val canvasAlt: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val raised: Color,
    val hairline: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkSubtle: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
    val isDark: Boolean,
)

internal val LightPalette = DisperPalette(
    canvas = Color(0xFFFFFFFF),
    canvasAlt = Color(0xFFF5F5F7),
    surface = Color(0xFFFFFFFF),
    surfaceAlt = Color(0xFFF5F5F7),
    raised = Color(0xFFFFFFFF),
    hairline = Color(0xFFD2D2D7),
    ink = Color(0xFF1D1D1F),
    inkMuted = Color(0xFF6E6E73),
    inkSubtle = Color(0xFF86868B),
    accent = Color(0xFF0066CC),
    accentSoft = Color(0xFFEAF2FC),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF1D7A46),
    warning = Color(0xFF8A5A00),
    danger = Color(0xFFD70015),
    isDark = false,
)

internal val DarkPalette = DisperPalette(
    canvas = Color(0xFF010102),
    canvasAlt = Color(0xFF141516),
    surface = Color(0xFF0F1011),
    surfaceAlt = Color(0xFF141516),
    raised = Color(0xFF1E2023),
    hairline = Color(0xFF23252A),
    ink = Color(0xFFF7F8F8),
    inkMuted = Color(0xFFD0D6E0),
    inkSubtle = Color(0xFF8A8F98),
    accent = Color(0xFF5E6AD2),
    accentSoft = Color(0xFF191A2E),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF27A644),
    warning = Color(0xFFD9A433),
    danger = Color(0xFFE5484D),
    isDark = true,
)

val LocalDisper = staticCompositionLocalOf { LightPalette }

private val LightScheme = lightColorScheme(
    primary = LightPalette.accent,
    onPrimary = LightPalette.onAccent,
    primaryContainer = LightPalette.canvasAlt,
    onPrimaryContainer = LightPalette.ink,
    inversePrimary = Color(0xFF7FB4F0),
    secondary = LightPalette.inkMuted,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEFEFF1),
    onSecondaryContainer = LightPalette.ink,
    tertiary = LightPalette.inkMuted,
    onTertiary = Color.White,
    background = LightPalette.canvas,
    onBackground = LightPalette.ink,
    surface = LightPalette.surface,
    onSurface = LightPalette.ink,
    surfaceVariant = LightPalette.canvasAlt,
    onSurfaceVariant = LightPalette.inkMuted,
    surfaceTint = LightPalette.accent,
    inverseSurface = Color(0xFF1D1D1F),
    inverseOnSurface = Color(0xFFF5F5F7),
    error = LightPalette.danger,
    onError = Color.White,
    errorContainer = Color(0xFFFCE9EA),
    onErrorContainer = Color(0xFF8A0009),
    outline = LightPalette.hairline,
    outlineVariant = Color(0xFFE8E8ED),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFFF5F5F7),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFAFC),
    surfaceContainer = Color(0xFFF5F5F7),
    surfaceContainerHigh = Color(0xFFEFEFF2),
    surfaceContainerHighest = Color(0xFFE9E9ED),
)

private val DarkScheme = darkColorScheme(
    primary = DarkPalette.accent,
    onPrimary = DarkPalette.onAccent,
    primaryContainer = Color(0xFF23263F),
    onPrimaryContainer = Color(0xFFDDE1FF),
    inversePrimary = Color(0xFF3D4795),
    secondary = DarkPalette.inkMuted,
    onSecondary = Color(0xFF0B0B0C),
    secondaryContainer = Color(0xFF191A1B),
    onSecondaryContainer = DarkPalette.ink,
    tertiary = DarkPalette.inkSubtle,
    onTertiary = Color(0xFF0B0B0C),
    background = DarkPalette.canvas,
    onBackground = DarkPalette.ink,
    surface = DarkPalette.surface,
    onSurface = DarkPalette.ink,
    surfaceVariant = DarkPalette.canvasAlt,
    onSurfaceVariant = DarkPalette.inkSubtle,
    surfaceTint = DarkPalette.accent,
    inverseSurface = Color(0xFFF7F8F8),
    inverseOnSurface = Color(0xFF0B0B0C),
    error = DarkPalette.danger,
    onError = Color.White,
    errorContainer = Color(0xFF2A1112),
    onErrorContainer = Color(0xFFF2B8B5),
    outline = DarkPalette.hairline,
    outlineVariant = DarkPalette.hairline,
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFF010102),
    surfaceBright = Color(0xFF1E2023),
    surfaceContainerLowest = Color(0xFF010102),
    surfaceContainerLow = Color(0xFF0F1011),
    surfaceContainer = Color(0xFF141516),
    surfaceContainerHigh = Color(0xFF18191A),
    surfaceContainerHighest = Color(0xFF1E2023),
)

internal fun colorSchemeFor(dark: Boolean) = if (dark) DarkScheme else LightScheme
