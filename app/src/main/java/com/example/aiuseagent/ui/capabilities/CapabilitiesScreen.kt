package com.example.aiuseagent.ui.capabilities

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.example.aiuseagent.domain.CapabilityCatalog
import com.example.aiuseagent.platform.AndroidStatusInspector
import com.example.aiuseagent.ui.components.FeatureRow
import com.example.aiuseagent.ui.components.InfoRow
import com.example.aiuseagent.ui.components.NoticeBanner
import com.example.aiuseagent.ui.components.NoticeTone
import com.example.aiuseagent.ui.components.SectionCard
import com.example.aiuseagent.ui.components.SectionDivider
import com.example.aiuseagent.ui.components.VerticalGap
import com.example.aiuseagent.ui.theme.Spacing
import com.example.aiuseagent.ui.i18n.tr

/**
 * 权限与能力状态页。所有条目要么来自 Android 系统 API 的真实读取，
 * 要么来自工程内的静态事实（CapabilityCatalog）。没有任何装饰性绿勾。
 */
@Composable
fun CapabilitiesScreen(
    inspector: AndroidStatusInspector,
    modifier: Modifier = Modifier,
) {
    val permissions = remember { inspector.permissionEntries() }
    val device = remember { inspector.deviceSummary() }
    val planned = remember { CapabilityCatalog.planned() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        NoticeBanner(
            text = tr("本页只显示真实状态：授权读的是系统返回值，未实现的功能一律标为「尚未实现」，", "This page shows real status only: permissions come from system values, and unimplemented features are always marked \"Not implemented\",") +
                tr("不会用固定图标表示可用。", "and availability is never shown with a fixed icon."),
            tone = NoticeTone.INFO,
        )

        VerticalGap(Spacing.md)

        SectionCard(title = tr("Android 授权与服务状态（实时读取）", "Android permission & service status (live)")) {
            permissions.forEachIndexed { index, entry ->
                if (index > 0) SectionDivider()
                FeatureRow(entry)
            }
        }

        VerticalGap(Spacing.md)

        SectionCard(title = tr("设备信息", "Device info")) {
            device.forEach { (label, value) ->
                InfoRow(label = label, value = value)
                VerticalGap(Spacing.xs)
            }
        }

        VerticalGap(Spacing.md)

        SectionCard(title = tr("功能能力表（本阶段）", "Capability table (this stage)")) {
            Text(
                text = tr("以下状态由代码事实决定，不是用户可切换的开关。", "These states are determined by code facts, not user-toggleable switches."),
                style = androidx.compose.material3.MaterialTheme.typography.labelSmall,
                color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
            )
            VerticalGap(Spacing.sm)
            planned.forEachIndexed { index, entry ->
                if (index > 0) SectionDivider()
                FeatureRow(entry)
            }
        }

        VerticalGap(Spacing.lg)
    }
}
