package com.example.aiuseagent.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.aiuseagent.data.execution.OperationRecoveryReview
import com.example.aiuseagent.ui.i18n.tr

@Composable
fun OperationRecoveryDialog(rows:List<OperationRecoveryReview>?,failed:Boolean=false,onDismiss:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,title={Text(tr("操作核查", "Operation check"))},
        text={Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(tr("这里只核查当前状态，不会自动重试、恢复或改写文件。哈希一致也不代表操作只执行过一次。", "This only checks the current state; it won't retry, recover, or rewrite files. Matching hashes don't prove an operation ran only once."))
            when {
                failed -> Text(tr("暂时无法读取核查记录，请关闭后重试查看。", "Can't read the check records right now. Close and try again."))
                rows==null -> Text(tr("正在读取核查记录…", "Reading check records…"))
                rows.isEmpty() -> Text(tr("此会话没有待核查的操作。", "No operations to check in this session."))
                else -> rows.forEach { row ->
                    HorizontalDivider()
                    Text(tr("操作 ${row.operationId.take(8)} · ${row.state}", "Operation ${row.operationId.take(8)} · ${row.state}"))
                    val observation=row.inspection
                    val batch=row.batch
                    if(batch!=null) {
                        Text(tr("批量目标：${batch.target}", "Batch target: ${batch.target}"))
                        Text(tr("以下为逐项回执；不会自动继续未执行项。目录恢复须先于其中的文件，恢复前仍需核验当前状态和备份。", "Per-item receipts follow; unexecuted items won't continue automatically. Directories must be recovered before their files, and the current state and backups still need verification first."))
                        batch.members.forEach { member ->
                            val status=when(member.state) {
                                "SUCCEEDED"->tr("已完成", "Done");"NOT_EXECUTED"->tr("未执行", "Not run");else->tr("结果不明", "Unknown result")
                            }
                            Text("$status · ${member.entry.path}")
                            if(member.state!="NOT_EXECUTED") Text(tr("恢复关联：${member.recoveryToken}", "Recovery link: ${member.recoveryToken}"))
                        }
                        row.reference?.let { Text(tr("批次关联：$it", "Batch link: $it")) }
                    } else if(observation==null) Text(when(row.code) {
                        "SCOPE_CHANGED" -> tr("工作区或会话状态已变化，未读取原目标。", "The workspace or session state changed, so the original target wasn't read.")
                        "BACKEND_UNSUPPORTED" -> tr("此工作区尚不支持恢复核查。", "Recovery checks aren't supported in this workspace yet.")
                        "NO_RECOVERY_REFERENCE" -> tr("旧记录缺少恢复关联，不能据此自动重试。", "The old record has no recovery link, so it can't be retried automatically.")
                        "SENSITIVE_PATH" -> tr("目标名称含敏感信息，已隐藏。", "The target name contains sensitive info and was hidden.")
                        else -> tr("恢复材料暂不可核验；不能据此判断操作未发生。", "Recovery material can't be verified yet; you can't conclude from this that nothing happened.")
                    }) else {
                        Text(tr("目标：${observation.target.path}", "Target: ${observation.target.path}"))
                        Text(tr("备份：${if(!observation.backupVerified) "缺失或校验失败" else if(observation.target.directory) "空目录结构记录已核对" else "完整性已核对"}",
                            "Backup: ${if(!observation.backupVerified) "Missing or failed verification" else if(observation.target.directory) "Empty directory structure verified" else "Integrity verified"}"))
                        Text(tr("恢复令牌：${if(observation.consumed) "已占用，不能重放" else "尚未占用"}",
                            "Recovery token: ${if(observation.consumed) "Consumed and can't be replayed" else "Not yet consumed"}"))
                        Text(tr("变更前：${observation.target.beforeHash}", "Before: ${observation.target.beforeHash}"))
                        Text(tr("预期变更后：${observation.target.afterHash}", "Expected after: ${observation.target.afterHash}"))
                        Text(tr("当前：${observation.currentHash ?: "无法读取"}",
                            "Current: ${observation.currentHash ?: "Unreadable"}"))
                        row.reference?.let { Text(tr("关联：$it", "Link: $it")) }
                        observation.followupToken?.let { Text(tr("后续恢复关联：$it", "Next recovery link: $it")) }
                    }
                }
            }
        }},confirmButton={TextButton(onClick=onDismiss){Text(tr("关闭", "Close"))}})
}
