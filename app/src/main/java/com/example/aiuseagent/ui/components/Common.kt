package com.example.aiuseagent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aiuseagent.domain.FeatureState
import com.example.aiuseagent.domain.label
import com.example.aiuseagent.ui.theme.LocalDisper
import com.example.aiuseagent.ui.theme.Spacing

/** 状态徽标：颜色只跟随**真实**状态，不做装饰性使用。 */
@Composable
fun StatusChip(state: FeatureState, modifier: Modifier = Modifier) {
    val palette = LocalDisper.current
    val (bg, fg) = when (state) {
        FeatureState.IMPLEMENTED -> palette.success.copy(alpha = 0.10f) to palette.success
        FeatureState.NOT_IMPLEMENTED -> palette.inkSubtle.copy(alpha = 0.10f) to palette.inkSubtle
        FeatureState.BLOCKED_BY_PERMISSION -> palette.warning.copy(alpha = 0.12f) to palette.warning
        FeatureState.NOT_CONNECTED -> palette.warning.copy(alpha = 0.12f) to palette.warning
        FeatureState.UNKNOWN -> palette.inkSubtle.copy(alpha = 0.10f) to palette.inkSubtle
    }
    Text(
        text = state.label(),
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        maxLines = 1,
        modifier = modifier
            .background(color = bg, shape = RoundedCornerShape(6.dp))
            .padding(horizontal = Spacing.sm, vertical = 3.dp),
    )
}

/**
 * 通用内容卡片：浅色模式下是白底 + 发丝线，深色模式下是炭黑面板 + 发丝线；
 * 不使用投影，层级由表面色区分。
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit,
) {
    val palette = LocalDisper.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = palette.surface,
        shape = MaterialTheme.shapes.large,
        border = BorderStroke(1.dp, palette.hairline),
    ) {
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = palette.inkSubtle,
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            content()
        }
    }
}

/** 一行「标签 : 值」，值过长时换行显示。 */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = LocalDisper.current.inkSubtle,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun FeatureRow(entry: com.example.aiuseagent.domain.FeatureEntry, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = entry.detail,
                style = MaterialTheme.typography.bodySmall,
                color = LocalDisper.current.inkSubtle,
            )
        }
        Spacer(modifier = Modifier.width(Spacing.sm))
        StatusChip(state = entry.state)
    }
}

@Composable
fun SectionDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = 1.dp,
        color = LocalDisper.current.hairline,
    )
}

@Composable
fun EmptyState(title: String, description: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.xl, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = LocalDisper.current.inkSubtle,
            overflow = TextOverflow.Visible,
        )
    }
}

/** 提示条：用于「不会生成模型回复」这类必须让用户看见的说明，颜色克制、不抢正文。 */
@Composable
fun NoticeBanner(text: String, tone: NoticeTone, modifier: Modifier = Modifier) {
    val palette = LocalDisper.current
    val (bg, fg) = when (tone) {
        NoticeTone.INFO -> palette.surfaceAlt to palette.inkMuted
        NoticeTone.WARN -> palette.warning.copy(alpha = 0.10f) to palette.warning
        NoticeTone.ERROR -> palette.danger.copy(alpha = 0.10f) to palette.danger
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = fg,
        modifier = modifier
            .fillMaxWidth()
            .background(color = bg, shape = MaterialTheme.shapes.medium)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

enum class NoticeTone { INFO, WARN, ERROR }

@Composable
fun VerticalGap(height: androidx.compose.ui.unit.Dp = Spacing.md) {
    Spacer(modifier = Modifier.padding(top = height))
}

@Composable
fun dot(color: Color): Color = color
