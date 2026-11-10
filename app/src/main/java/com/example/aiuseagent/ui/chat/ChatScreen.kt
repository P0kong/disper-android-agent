package com.example.aiuseagent.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aiuseagent.ui.components.VerticalGap
import com.example.aiuseagent.ui.theme.LocalDisper
import com.example.aiuseagent.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.aiuseagent.ui.i18n.tr

/** 消息、流式正文和工具活动共用可滚动区域，键盘出现时保留消息阅读空间。 */
@Composable
fun ChatScreen(
    state: ChatUiState,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    onDismissNotice: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
    onRenameDraftChange: (String) -> Unit,
    onCancelRename: () -> Unit,
    onConfirmRename: () -> Unit,
    onModel: () -> Unit,
    onFolder: () -> Unit,
    modifier: Modifier = Modifier,
    onStop: () -> Unit = {},
    onRetry: () -> Unit = {},
    permission: com.example.aiuseagent.data.execution.PermissionLevel = com.example.aiuseagent.data.execution.PermissionLevel.VIEW_ONLY,
    onPermission: () -> Unit = {},
    onReviewOperations: () -> Unit = {},
    onAddAttachment: (com.example.aiuseagent.domain.attachment.AttachmentPickerKind) -> Unit = {},
    onRemoveAttachment: (String) -> Unit = {},
    onPreviewAttachment: (String) -> Unit = {},
    onDismissAttachmentPreview: () -> Unit = {},
    onConfirmAttachmentPreview: () -> Unit = {},
    onPreviewHistoryAttachment:(Long,String)->Unit={_,_->},
    onDismissHistoryAttachmentPreview:()->Unit={},
) {
    val palette = LocalDisper.current
    state.attachmentPreview?.let { AttachmentPreviewDialog(it,onDismissAttachmentPreview,onConfirmAttachmentPreview) }
    state.historyAttachmentPreview?.let {HistoryAttachmentPreviewDialog(it,onDismissHistoryAttachmentPreview)}
    var showStatusDetail by remember { mutableStateOf(false) }
    val messageList = rememberLazyListState()
    val status = if(state.viewingLegacy) tr("历史记录只读；点击交流或构建开始新会话，旧内容不会自动发送。", "History is read-only; tap Chat or Build to start a new session. Old content is not sent automatically.") else state.generationError ?: state.generationStatus ?: state.notice
    val title = state.sessions.firstOrNull { it.id == state.activeSessionId }?.title ?: tr("新对话", "New chat")

    // Follow a new/streaming message only while the reader is already near the end.
    LaunchedEffect(state.messages.size, state.generationText) {
        val total = messageList.layoutInfo.totalItemsCount
        val lastVisible = messageList.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (total > 0 && lastVisible >= total - 3) messageList.scrollToItem(total - 1)
    }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = Spacing.screenHorizontal, end = Spacing.md, top = 6.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = palette.inkSubtle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (state.mode == ChatMode.BUILD) {
                TextButton(onClick = onFolder, modifier = Modifier.weight(1.5f)) {
                    Text(
                        tr("工作区 · ", "Workspace · ") + state.workspaceName + if (state.workspaceAvailable) "" else tr(" · 需重选", " · Reselect required"),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.accent,
                    )
                }
            }
        }
        if (status != null) {
            Surface(
                onClick = { showStatusDetail = true },
                color = if (state.generationError != null) MaterialTheme.colorScheme.errorContainer
                    else palette.canvasAlt,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        status,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.generationError != null) MaterialTheme.colorScheme.onErrorContainer
                            else palette.inkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(tr("详情", "Details"), style = MaterialTheme.typography.labelSmall, color = palette.inkSubtle)
                }
            }
        }

        LazyColumn(
            state = messageList,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (state.messages.isEmpty() && !state.isGenerating) {
                item {
                    Box(
                        Modifier.fillParentMaxHeight(0.78f).fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                if (state.mode == ChatMode.BUILD) tr("从工作区开始", "Start from workspace") else tr("今天想聊点什么？", "What's on your mind?"),
                                style = MaterialTheme.typography.headlineMedium,
                                color = palette.ink,
                            )
                            VerticalGap(Spacing.sm)
                            Text(
                                if (state.mode == ChatMode.BUILD) tr("描述任务，或在下方添加附件与工作区文件", "Describe your task, or add attachments and workspace files below")
                                else tr("写下消息开始对话", "Write a message to start"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.inkSubtle,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                }
            }
            items(items = state.messages, key = { it.id }) { message -> MessageBubble(message,onPreviewHistoryAttachment) }
            if (state.toolActivities.isNotEmpty()) {
                item(key = "tool_activity") {
                    Surface(
                        color = palette.canvasAlt,
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(tr("工具活动", "Tool activity"), style = MaterialTheme.typography.labelMedium, color = palette.inkMuted)
                            VerticalGap(Spacing.xs)
                            state.toolActivities.forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = palette.inkMuted)
                            }
                        }
                    }
                }
            }
            if (state.isGenerating) {
                item(key = "streaming_response") {
                    Surface(
                        color = palette.surface,
                        shape = MaterialTheme.shapes.large,
                        border = BorderStroke(1.dp, palette.hairline),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(state.modelLabel + tr(" · 生成中", " · Generating"), style = MaterialTheme.typography.labelMedium, color = palette.accent)
                            VerticalGap(Spacing.xs)
                            Text(state.generationText?.takeIf { it.isNotBlank() } ?: tr("正在等待回复…", "Waiting for reply…"),
                                style = MaterialTheme.typography.bodyMedium, color = palette.ink)
                        }
                    }
                }
            }
            if (!state.isGenerating && state.canRetry) {
                item(key = "retry_response") {
                    Column {
                        state.generationError?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = onRetry) { Text(tr("重试本次回复", "Retry this reply"), color = palette.accent) }
                    }
                }
            }
        }

        HorizontalDivider(thickness = 1.dp, color = palette.hairline)
        Surface(color = palette.canvas) {
            Column(Modifier.fillMaxWidth().padding(horizontal = Spacing.lg, vertical = 10.dp)) {
                AttachmentCards(state,onRemoveAttachment,onPreviewAttachment)
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    AttachmentAddButton(state.isReady && !state.viewingLegacy && !state.isGenerating && !state.preparingAttachments,onAddAttachment)
                    Spacer(Modifier.width(8.dp))
                    TextField(
                        value = state.input,
                        onValueChange = onInputChange,
                        modifier = Modifier.weight(1f),
                        placeholder = { Text(tr("输入消息", "Type a message"), color = palette.inkSubtle) },
                        maxLines = 4,
                        shape = MaterialTheme.shapes.extraLarge,
                        enabled = state.isReady && !state.viewingLegacy,
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = palette.canvasAlt,
                            unfocusedContainerColor = palette.canvasAlt,
                            disabledContainerColor = palette.canvasAlt,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                            cursorColor = palette.accent,
                            focusedTextColor = palette.ink,
                            unfocusedTextColor = palette.ink,
                            disabledTextColor = palette.inkSubtle,
                        ),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (state.isGenerating) {
                        TextButton(onClick = onStop) { Text(tr("停止", "Stop"), color = palette.accent) }
                    } else {
                        Surface(
                            onClick = onSend,
                            enabled = state.canSendMessage,
                            shape = CircleShape,
                            color = if (state.canSendMessage) palette.accent else palette.canvasAlt,
                            contentColor = if (state.canSendMessage) palette.onAccent else palette.inkSubtle,
                            modifier = Modifier.size(48.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = tr("发送到当前模型", "Send to current model"),
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if(state.mode==ChatMode.BUILD && !state.viewingLegacy) TextButton(onClick = onPermission, contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = 0.dp)) {
                        Text(permission.label(), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                            color=if(permission==com.example.aiuseagent.data.execution.PermissionLevel.FULL) MaterialTheme.colorScheme.error else palette.accent)
                    }
                    if(state.mode==ChatMode.BUILD && !state.viewingLegacy && state.activeSessionId!=null) TextButton(onClick=onReviewOperations,
                        contentPadding=PaddingValues(horizontal=Spacing.xs,vertical=0.dp)) {
                        Text(tr("操作核查", "Operation check"),style=MaterialTheme.typography.labelSmall, color = palette.inkSubtle)
                    }
                    TextButton(onClick = onModel, modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = 0.dp)) {
                        Text(state.modelLabel, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, color = palette.inkSubtle)
                    }
                }
            }
        }
    }

    if (showStatusDetail && status != null) {
        AlertDialog(
            onDismissRequest = { showStatusDetail = false },
            title = { Text(if (state.generationError != null) tr("回复未完成", "Reply incomplete") else tr("当前状态", "Current state")) },
            text = { Text(status, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = {
                TextButton(onClick = { showStatusDetail = false; onDismissNotice() }) { Text(tr("知道了", "Got it")) }
            },
            dismissButton = {
                if (state.canRetry && !state.isGenerating) {
                    TextButton(onClick = { showStatusDetail = false; onRetry() }) { Text(tr("重试", "Retry")) }
                }
            },
        )
    }

    state.pendingDeleteSession?.let { pendingDelete ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text(tr("删除会话？", "Delete session?")) },
            text = { Text(tr("将删除『", "This will delete \"") + pendingDelete.title + tr("』及其全部消息。此操作不可撤销。", "\" and all its messages. This can't be undone.")) },
            confirmButton = { TextButton(onClick = onConfirmDelete) { Text(tr("删除", "Delete")) } },
            dismissButton = { TextButton(onClick = onCancelDelete) { Text(tr("取消", "Cancel")) } },
        )
    }
    state.pendingRenameSession?.let {
        AlertDialog(
            onDismissRequest = onCancelRename,
            title = { Text(tr("重命名会话", "Rename session")) },
            text = {
                Column {
                    OutlinedTextField(
                        value = state.renameDraft, onValueChange = onRenameDraftChange,
                        label = { Text(tr("会话标题", "Session title")) }, singleLine = true,
                        isError = state.renameError != null, shape = MaterialTheme.shapes.medium,
                    )
                    state.renameError?.let { error ->
                        Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(tr("最多 ", "Up to ") + com.example.aiuseagent.data.SessionRepository.MAX_TITLE_LENGTH + tr(" 个字符。", " characters."),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = onConfirmRename) { Text(tr("保存", "Save")) } },
            dismissButton = { TextButton(onClick = onCancelRename) { Text(tr("取消", "Cancel")) } },
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage,onPreviewHistoryAttachment:(Long,String)->Unit) {
    val palette = LocalDisper.current
    val isUser = message.role == MessageRole.USER
    val provider = when {
        isUser -> tr("我", "Me")
        message.isDemo -> tr("本地演示数据（非模型回复）", "Local demo data (not a model reply)")
        message.providerType?.equals("mock", ignoreCase = true) == true -> tr("Mock · 本地模拟回复", "Mock · Local simulated reply")
        message.providerType?.equals("deepseek", ignoreCase = true) == true -> "DeepSeek"
        else -> tr("助手", "Assistant")
    }
    val status = when (message.status.uppercase()) {
        "STREAMING", "GENERATING" -> tr("生成中", "Generating")
        "CANCELLED", "CANCELED", "STOPPED" -> tr("已停止 · 部分内容", "Stopped · Partial content")
        "INTERRUPTED" -> tr("进程中断 · 部分内容", "Process interrupted · Partial content")
        "FAILED", "ERROR" -> tr("回复失败 · 部分内容", "Reply failed · Partial content")
        "PENDING" -> tr("等待回复", "Waiting for reply")
        "COMPLETED" -> if (isUser) tr("已发送", "Sent") else tr("已完成", "Done")
        else -> null
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (isUser) palette.canvasAlt else palette.surface,
            shape = if (isUser) RoundedCornerShape(20.dp) else MaterialTheme.shapes.large,
            border = if (isUser) null else BorderStroke(1.dp, palette.hairline),
            modifier = Modifier.fillMaxWidth(0.92f),
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(provider, style = MaterialTheme.typography.labelSmall, color = palette.inkSubtle)
                message.attachments.forEach { attachment ->
                    Column(Modifier.padding(vertical = Spacing.xs)) {
                        Text(attachment.name, style = MaterialTheme.typography.bodyMedium)
                        Text(tr("已附加 · ${attachment.mimeType} · ${attachment.sizeBytes} 字节", "Attached · ${attachment.mimeType} · ${attachment.sizeBytes} bytes"),
                            style = MaterialTheme.typography.labelSmall, color = palette.inkSubtle)
                        TextButton(onClick={onPreviewHistoryAttachment(message.id,attachment.id)},
                            contentPadding = PaddingValues(0.dp),
                            modifier=Modifier.semantics {contentDescription=tr("查看已发送附件", "View sent attachment")}) {
                            Text(tr("查看已发送内容", "View sent content"), style = MaterialTheme.typography.labelMedium, color = palette.accent)
                        }
                    }
                }
                if (message.content.isNotBlank() || message.attachments.isEmpty()) {
                    Text(message.content.ifBlank { status ?: tr("回复为空", "Empty reply") }, style = MaterialTheme.typography.bodyMedium)
                }
                VerticalGap(Spacing.xs)
                Text(listOfNotNull(status, formatTimestamp(message.createdAt)).joinToString(" · "),
                    style = MaterialTheme.typography.labelSmall, color = palette.inkSubtle)
            }
        }
    }
}

private fun formatTimestamp(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
