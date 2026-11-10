package com.example.aiuseagent.ui.diagnostics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiuseagent.ui.components.FeatureRow
import com.example.aiuseagent.ui.components.InfoRow
import com.example.aiuseagent.ui.components.NoticeBanner
import com.example.aiuseagent.ui.components.NoticeTone
import com.example.aiuseagent.ui.components.SectionCard
import com.example.aiuseagent.ui.components.SectionDivider
import com.example.aiuseagent.ui.components.VerticalGap
import com.example.aiuseagent.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.aiuseagent.ui.i18n.tr

/**
 * 诊断页：只展示真实读取到的值，以及明确标注为「未验证」的检测项。
 */
@Composable
fun DiagnosticsScreen(
    diagnosticsViewModel: DiagnosticsViewModel,
    modifier: Modifier = Modifier,
) {
    val state by diagnosticsViewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        NoticeBanner(
            text = tr("本页数值都是打开页面时实时读取的；标「未验证」的条目表示应用没有做过该检测，", "All values on this page were read live when it opened; entries marked \"Unverified\" mean the app never ran that check,") +
                tr("不能理解成「通过」。构建成功也不代表真机运行成功。", "which doesn't mean \"passed\". A successful build doesn't mean it runs on a real device."),
            tone = NoticeTone.INFO,
        )

        VerticalGap(Spacing.md)

        SectionCard(title = tr("运行环境", "Runtime")) {
            InfoRow(label = tr("应用版本", "App version"), value = state.appVersion + " (" + state.appVersionCode + ")")
            VerticalGap(Spacing.xs)
            InfoRow(label = "Android", value = state.androidVersion)
            VerticalGap(Spacing.xs)
            InfoRow(label = tr("设备", "Device"), value = state.deviceModel)
            VerticalGap(Spacing.xs)
            InfoRow(label = tr("支持的 ABI", "Supported ABI"), value = state.abis)
        }

        VerticalGap(Spacing.md)

        SectionCard(title = tr("本地存储（Room）", "Local storage (Room)")) {
            state.database.forEach { (label, value) ->
                InfoRow(label = label, value = value)
                VerticalGap(Spacing.xs)
            }
        }

        VerticalGap(Spacing.md)

        SectionCard(title = tr("Python 运行时（Chaquopy）", "Python runtime (Chaquopy)")) {
            when (val python = state.python) {
                is PythonStatus.Checking -> {
                    NoticeBanner(text = tr("检测中…", "Checking…"), tone = NoticeTone.INFO)
                }

                is PythonStatus.Ready -> {
                    NoticeBanner(
                        text = tr("已初始化：", "Initialized: ") + python.summary,
                        tone = NoticeTone.INFO,
                    )
                }

                is PythonStatus.Failed -> {
                    NoticeBanner(
                        text = tr("检测失败（原始异常）：", "Check failed (raw exception): ") + python.message,
                        tone = NoticeTone.WARN,
                    )
                }
            }
            VerticalGap(Spacing.sm)
            Text(
                text = tr("目前仅支持固定问候与解释器诊断功能，不支持任意 Python 代码执行。", "Only a fixed greeting and interpreter diagnostics are supported; arbitrary Python code execution is not.") +
                    tr("对应 hello.py 的 greet() 与 interpreter_summary()；应用没有提供「输入代码并执行」的界面或接口。", "Maps to greet() and interpreter_summary() in hello.py; the app provides no UI or API for entering and running code."),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        VerticalGap(Spacing.md)

        SectionCard(title = tr("Android 授权与服务状态（实时读取）", "Android permission & service status (live)")) {
            state.permissions.forEachIndexed { index, entry ->
                if (index > 0) SectionDivider()
                FeatureRow(entry)
            }
        }

        VerticalGap(Spacing.md)

        SectionCard(title = tr("能力表（含未验证项）", "Capability table (incl. unverified)")) {
            state.features.forEachIndexed { index, entry ->
                if (index > 0) SectionDivider()
                FeatureRow(entry)
            }
        }

        VerticalGap(Spacing.md)

        Text(
            text = tr("刷新时间：", "Refreshed: ") + formatTime(state.refreshedAt),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        VerticalGap(Spacing.lg)
    }
}

private fun formatTime(millis: Long): String =
    if (millis <= 0L) tr("尚未刷新", "Not refreshed yet") else SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(millis))
