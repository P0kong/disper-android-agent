package com.example.aiuseagent.ui.theme

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import com.example.aiuseagent.ui.i18n.tr

/** 主题偏好：由设置页写入，再由 AppRoot 决定实际使用的配色。 */
enum class ThemePreference(private val labelZh: String, private val labelEn: String) {
    FOLLOW_SYSTEM("跟随系统", "Follow system"),
    LIGHT("浅色", "Light"),
    DARK("深色", "Dark"),
    ;
    val label: String get() = tr(labelZh, labelEn)

    companion object {
        fun fromKey(key: String?): ThemePreference =
            entries.firstOrNull { it.name == key } ?: FOLLOW_SYSTEM
    }
}

/**
 * 主题入口：浅色 = 极简白，深色 = 深色 SaaS。
 * 两套方案的字体、圆角与系统栏图标都随模式切换，但页面代码完全共用。
 */
@Composable
fun AiUseAgentTheme(
    preference: ThemePreference = ThemePreference.FOLLOW_SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (preference) {
        ThemePreference.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    val palette = if (darkTheme) DarkPalette else LightPalette
    val window = LocalActivity.current?.window
    SideEffect {
        window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }
    CompositionLocalProvider(LocalDisper provides palette) {
        MaterialTheme(
            colorScheme = colorSchemeFor(darkTheme),
            typography = AppTypography,
            shapes = if (darkTheme) DarkShapes else LightShapes,
            content = content,
        )
    }
}
