package com.example.aiuseagent.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.aiuseagent.data.workspace.WorkspaceKind
import com.example.aiuseagent.data.workspace.WorkspaceState
import com.example.aiuseagent.data.workspace.WorkspaceStatus
import com.example.aiuseagent.data.workspace.WorkspaceSummary
import com.example.aiuseagent.ui.components.NoticeBanner
import com.example.aiuseagent.ui.components.NoticeTone
import com.example.aiuseagent.ui.components.SectionCard
import com.example.aiuseagent.ui.theme.LocalDisper
import com.example.aiuseagent.ui.theme.Spacing
import com.example.aiuseagent.ui.i18n.tr

/** 所有文件操作由明确按钮触发；系统文件选择器由 AppRoot 承接。 */
@Composable
fun WorkspaceScreen(
    state: WorkspaceState,
    currentWorkspaceId: String,
    onSelect: (String) -> Unit,
    onChoose: () -> Unit,
    onRemove: (String, String?) -> Unit,
    onImport: () -> Unit,
    onExport: (String, String) -> Unit,
    isBusy: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var pendingRemoval by remember { mutableStateOf<WorkspaceSummary?>(null) }
    var replacementId by remember { mutableStateOf("default") }
    var exportPath by remember { mutableStateOf("") }
    var exportError by remember { mutableStateOf<String?>(null) }
    val selected = state.workspaces.firstOrNull { it.id == currentWorkspaceId }
    val available = selected?.status == WorkspaceStatus.AVAILABLE
    val writable = available && selected?.writable == true

    Column(
        modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Text(tr("当前会话工作区", "Current session workspace"), style = MaterialTheme.typography.titleMedium)
        Text(tr("此处切换只影响当前会话。默认工作区由应用管理；自定义工作区只访问您通过系统选择并授权的目录。", "Switching here affects only the current session. The default workspace is managed by the app; custom workspaces access only folders you selected and authorized through the system."),
            style = MaterialTheme.typography.bodyMedium)
        state.notice?.let { NoticeBanner(it, NoticeTone.INFO) }
        if (isBusy) Text(tr("正在处理…", "Processing…"), style = MaterialTheme.typography.bodySmall)
        state.workspaces.forEach { workspace ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = LocalDisper.current.surface,
                border = BorderStroke(1.dp, LocalDisper.current.hairline),
            ) {
                Column(Modifier.padding(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row(
                        Modifier.fillMaxWidth().selectable(
                            selected = workspace.id == currentWorkspaceId,
                            enabled = !isBusy && workspace.status == WorkspaceStatus.AVAILABLE,
                            role = Role.RadioButton,
                            onClick = { onSelect(workspace.id) },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = workspace.id == currentWorkspaceId, onClick = null,
                            enabled = !isBusy && workspace.status == WorkspaceStatus.AVAILABLE)
                        Column(Modifier.weight(1f)) {
                            Text(workspace.name, style = MaterialTheme.typography.titleSmall)
                            Text(if (workspace.kind == WorkspaceKind.DEFAULT) tr("应用默认工作区", "App default workspace") else tr("已注册系统目录", "Registered system folder"),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (workspace.status == WorkspaceStatus.RESELECT) {
                        Text(tr("授权已失效或目录不可用，请重新选择同一目录恢复授权。", "Permission expired or folder unavailable. Reselect the same folder to restore access."),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onChoose, enabled = !isBusy) { Text(tr("重新选择目录", "Reselect folder")) }
                    } else if (!workspace.writable) {
                        Text(tr("只读授权：可以读取或导出，无法导入文件。", "Read-only permission: you can read or export, but can't import files."), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(tr("已绑定 ", "Bound ") + workspace.referencedSessions + tr(" 个会话", " sessions"), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (workspace.kind != WorkspaceKind.DEFAULT) {
                        TextButton(onClick = { pendingRemoval = workspace; replacementId = "default" }, enabled = !isBusy) {
                            Text(tr("移除工作区登记", "Remove workspace entry"))
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onChoose, enabled = !isBusy, modifier = Modifier.fillMaxWidth()) {
            Text(tr("通过系统选择自定义目录", "Choose a custom folder via system"))
        }
        SectionCard(title = tr("导入文本文件", "Import text file")) {
            Text(tr("将明确选择的 TXT / Markdown 文件复制到当前工作区，使用源文件名。仅支持 UTF-8 文本，单文件最大 8 KiB；不会自动扫描目录。", "Copies the explicitly selected TXT / Markdown file into the current workspace using its original name. Only UTF-8 text is supported, max 8 KiB per file; folders are never scanned automatically."),
                style = MaterialTheme.typography.bodySmall)
            Button(onClick = onImport, enabled = writable && !isBusy, modifier = Modifier.fillMaxWidth()) {
                Text(tr("选择并导入 TXT / MD", "Select and import TXT / MD"))
            }
        }
        SectionCard(title = tr("导出文本文件", "Export text file")) {
            Text(tr("填写当前工作区内的相对路径，再通过系统选择导出位置。", "Enter a relative path within the current workspace, then choose the export location via the system."),
                style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = exportPath,
                onValueChange = { exportPath = it; exportError = null },
                label = { Text(tr("工作区内相对路径", "Relative path in workspace")) },
                placeholder = { Text("notes.md") },
                singleLine = true,
                isError = exportError != null,
                modifier = Modifier.fillMaxWidth(),
            )
            exportError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    val path = exportPath.trim()
                    if (path.isEmpty() || !(path.endsWith(".txt", true) || path.endsWith(".md", true))) {
                        exportError = tr("请输入 TXT 或 MD 文件的相对路径。", "Enter the relative path of a TXT or MD file.")
                    } else {
                        onExport(path, path.substringAfterLast('/').substringAfterLast('\\'))
                    }
                },
                enabled = available && !isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(tr("选择位置并导出", "Choose location and export")) }
        }
    }

    pendingRemoval?.let { removed ->
        val replacements = state.workspaces.filter { it.id != removed.id && it.status == WorkspaceStatus.AVAILABLE }
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(tr("移除工作区登记？", "Remove workspace entry?")) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text(removed.name)
                    Text(tr("只移除应用中的登记和目录授权，不删除目录内文件。", "Only the app's entry and folder permission are removed; files in the folder are kept."))
                    Text(tr("旧会话保留原目录记录；切换替代目录会开始新的构建会话。", "Old sessions keep their original folder records; switching to a replacement folder starts a new build session."))
                    if (removed.referencedSessions > 0 || removed.id == currentWorkspaceId) {
                        Text(tr("此工作区仍被会话使用，请确认将这些会话改绑到：", "This workspace is still used by sessions. Rebind them to:"))
                        replacements.forEach { replacement ->
                            Row(Modifier.fillMaxWidth().selectable(replacementId == replacement.id,
                                role = Role.RadioButton, onClick = { replacementId = replacement.id }),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = replacementId == replacement.id, onClick = null)
                                Text(replacement.name, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onRemove(removed.id, if (removed.referencedSessions > 0 || removed.id == currentWorkspaceId) replacementId else null)
                        pendingRemoval = null
                    },
                    enabled = !isBusy && (removed.referencedSessions == 0 || replacements.any { it.id == replacementId }),
                ) { Text(tr("确认移除", "Remove")) }
            },
            dismissButton = { TextButton(onClick = { pendingRemoval = null }) { Text(tr("取消", "Cancel")) } },
        )
    }
}
