package com.example.aiuseagent.ui.skills

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.aiuseagent.domain.CapabilityCatalog
import com.example.aiuseagent.ui.components.EmptyState
import com.example.aiuseagent.ui.components.FeatureRow
import com.example.aiuseagent.ui.components.NoticeBanner
import com.example.aiuseagent.ui.components.NoticeTone
import com.example.aiuseagent.ui.components.SectionCard
import com.example.aiuseagent.ui.components.SectionDivider
import com.example.aiuseagent.ui.components.VerticalGap
import com.example.aiuseagent.ui.theme.Spacing
import com.example.aiuseagent.ui.i18n.tr

/** Skills 占位页：没有任何加载、安装或执行逻辑。 */
@Composable
fun SkillsScreen(modifier: Modifier = Modifier) {
    val planned = CapabilityCatalog.planned().filter {
        it.name.contains("Skills") || it.name.contains("Python")
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
    ) {
        NoticeBanner(
            text = tr("Skills 尚未实现：本页只是占位。应用不会加载、安装或执行任何外部脚本与技能包。", "Skills aren't implemented: this page is a placeholder. The app won't load, install, or run any external scripts or skill packs."),
            tone = NoticeTone.WARN,
        )
        VerticalGap(Spacing.md)
        EmptyState(
            title = tr("暂无可用 Skills", "No Skills available"),
            description = tr("后续阶段才会设计技能清单、权限边界与执行沙箱；当前版本没有任何执行入口。", "Skill lists, permission boundaries, and execution sandboxes are planned for later stages; this version has no execution entry point."),
        )
        VerticalGap(Spacing.md)
        SectionCard(title = tr("相关能力现状", "Related capability status")) {
            planned.forEachIndexed { index, entry ->
                if (index > 0) SectionDivider()
                FeatureRow(entry)
            }
        }
        VerticalGap(Spacing.lg)
    }
}
