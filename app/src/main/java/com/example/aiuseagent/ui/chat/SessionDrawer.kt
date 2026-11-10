package com.example.aiuseagent.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.aiuseagent.data.local.SessionEntity
import com.example.aiuseagent.ui.components.EmptyState
import com.example.aiuseagent.ui.theme.LocalDisper
import com.example.aiuseagent.ui.theme.Spacing
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.aiuseagent.ui.i18n.tr

/**
 * 会话抽屉。列表内容 **100% 来自 Room 数据库**（[ChatUiState.sessions] 是 DAO 的 Flow），
 * 没有任何硬编码示例会话。
 */
@Composable
fun SessionDrawer(
    state: ChatUiState,
    onNewSession: () -> Unit,
    onSelect: (Long) -> Unit,
    onRequestRename: (SessionEntity) -> Unit,
    onRequestDelete: (SessionEntity) -> Unit,
) {
    val palette = LocalDisper.current
    var history by remember(state.mode) { mutableStateOf(false) }
    val sessions=state.sessions.filter { if(history) it.mode=="LEGACY" else it.mode==if(state.mode==ChatMode.BUILD) "BUILD" else "CHAT" }
    Column(modifier = Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 18.dp)) {
            Text("DISPER", style = MaterialTheme.typography.labelSmall, color = palette.accent)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if(history) tr("历史记录", "History") else state.mode.label + tr("会话", "Sessions"),
                    style = MaterialTheme.typography.headlineSmall,
                    color = palette.ink,
                    modifier = Modifier.weight(1f),
                )
                Surface(
                    onClick = { history=false;onNewSession() },
                    enabled = state.isReady,
                    shape = CircleShape,
                    color = if (state.isReady) palette.ink else palette.canvasAlt,
                    contentColor = if (state.isReady) palette.canvas else palette.inkSubtle,
                ) {
                    Text(
                        tr("新建", "New"),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = tr("共 ", "Total ") + sessions.size + tr(" 个会话 · 已保存到本机", " sessions · Saved on this device"),
                style = MaterialTheme.typography.labelSmall,
                color = palette.inkSubtle,
            )
            TextButton(onClick={history=!history}, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(if(history) tr("返回当前模式", "Back to current mode") else tr("历史记录", "History"),
                    style = MaterialTheme.typography.labelMedium, color = palette.accent)
            }
        }
        if (sessions.isEmpty()) {
            EmptyState(
                title = tr("还没有会话", "No sessions yet"),
                description = tr("点击右上角新建，发送第一条消息后会保存在这里。", "Tap New in the top right; after you send your first message, it will be saved here."),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items = sessions, key = { it.id }) { session ->
                    SessionRow(
                        session = session,
                        selected = session.id == state.activeSessionId,
                        onSelect = { onSelect(session.id) },
                        onRename = { onRequestRename(session) },
                        onDelete = { onRequestDelete(session) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionRow(
    session: SessionEntity,
    selected: Boolean,
    onSelect: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    val palette = LocalDisper.current
    Surface(
        color = if (selected) palette.canvasAlt else palette.canvas,
        shape = RoundedCornerShape(12.dp),
        border = if (selected) BorderStroke(1.dp, palette.hairline) else null,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = session.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = tr("更新于 ", "Updated ") + formatTime(session.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.inkSubtle,
                )
            }
            IconButton(onClick = onRename) {
                Icon(Icons.Filled.Edit, contentDescription = tr("重命名会话", "Rename session"), tint = palette.inkSubtle)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = tr("删除会话", "Delete session"), tint = palette.inkSubtle)
            }
        }
    }
}

private fun formatTime(millis: Long): String =
    SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
