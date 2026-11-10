package com.example.aiuseagent.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.example.aiuseagent.data.execution.PendingApproval
import com.example.aiuseagent.data.execution.PermissionLevel
import com.example.aiuseagent.ui.i18n.tr

fun PermissionLevel.label(): String=when(this) {
    PermissionLevel.VIEW_ONLY -> tr("仅查看", "View only")
    PermissionLevel.WORKSPACE_EDIT -> tr("工作区修改", "Workspace changes")
    PermissionLevel.FULL -> tr("完全权限 · 本进程", "Full permission · This process")
}

@Composable
fun PermissionDialog(current: PermissionLevel,onSelect: (PermissionLevel,Boolean)->Unit,onDismiss: ()->Unit) {
    var confirmFull by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest=onDismiss,title={Text(if(confirmFull) tr("启用完全权限？", "Enable full permission?") else tr("构建会话权限", "Build session permission"))},
        text={ Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(if(confirmFull) tr("完全权限允许模型在 Android 已授权的任务资源范围内使用本版本支持的能力，不再逐次询问。当前仅开放文件读取，写入、删除和命令执行尚未开放；切换权限不会启用这些能力。此选择仅在当前进程生效。", "Full permission lets the model use the capabilities this version supports within Android-authorized task resources without asking every time. Only file reading is currently enabled; writes, deletes, and command execution are not available, and switching permissions won't enable them. This choice applies to the current process only.")
                else tr("当前：${current.label()}。目录授权仍由 Android 管理。当前仅开放文件读取，模型写入、删除和命令执行尚未开放。", "Current: ${current.label()}. Folder permission is still managed by Android. Only file reading is enabled; model writes, deletes, and command execution are not available yet."))
            if(!confirmFull) PermissionLevel.entries.forEach { level ->
                TextButton(onClick={ if(level==PermissionLevel.FULL) confirmFull=true else onSelect(level,false) }) { Text(level.label()) }
            }
        } },
        confirmButton={ if(confirmFull) TextButton(onClick={onSelect(PermissionLevel.FULL,true)}) {Text(tr("确认启用", "Enable"))} },
        dismissButton={TextButton(onClick=onDismiss) {Text(tr("取消", "Cancel"))}})
}

@Composable
fun OperationApprovalDialog(approval: PendingApproval,onDecision: (String,Boolean)->Unit) {
    AlertDialog(onDismissRequest={onDecision(approval.id,false)},title={Text(tr("允许这次操作？", "Allow this operation?"))},
        text={ Text(approval.preview,Modifier.verticalScroll(rememberScrollState())) },
        confirmButton={TextButton(onClick={onDecision(approval.id,true)}) {Text(tr("仅允许本次", "Allow once"))}},
        dismissButton={TextButton(onClick={onDecision(approval.id,false)}) {Text(tr("拒绝", "Deny"))}})
}
