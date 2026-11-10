package com.example.aiuseagent.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** 间距与圆角的统一来源，避免各页面随手写魔法数字。 */
internal object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** 窄屏下页面左右留白；配合系统栏 padding 使用。 */
    val screenHorizontal = 20.dp

    /** 内容区最大留白，浅色模式用它换取呼吸感。 */
    val screenVertical = 20.dp
}

/**
 * 浅色模式圆角更饱满（12–24dp），深色模式更克制（8–14dp），
 * 与两套参考风格各自的气质一致；页面只引用 MaterialTheme.shapes。
 */
internal val LightShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

internal val DarkShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(18.dp),
)
