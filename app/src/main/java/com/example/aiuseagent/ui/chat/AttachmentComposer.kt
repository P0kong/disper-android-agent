package com.example.aiuseagent.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.aiuseagent.domain.attachment.*
import com.example.aiuseagent.ui.components.VerticalGap
import com.example.aiuseagent.ui.theme.LocalDisper
import com.example.aiuseagent.ui.i18n.tr

@Composable
fun AttachmentAddButton(enabled: Boolean, onAdd: (AttachmentPickerKind) -> Unit) {
    val palette = LocalDisper.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { expanded = true },
            enabled = enabled,
            shape = CircleShape,
            color = palette.canvasAlt,
            contentColor = if (enabled) palette.ink else palette.inkSubtle,
            modifier = Modifier.size(48.dp).semantics { contentDescription = tr("添加附件", "Add attachment") },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(22.dp))
            }
        }
        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
            containerColor = palette.surface,
            tonalElevation = 0.dp,
            shadowElevation = 6.dp,
            shape = MaterialTheme.shapes.medium,
            border = BorderStroke(1.dp, palette.hairline),
        ) {
            listOf(tr("文件", "File") to AttachmentPickerKind.FILE,tr("照片", "Photo") to AttachmentPickerKind.PHOTO,tr("拍照", "Camera") to AttachmentPickerKind.CAMERA).forEach { (label,kind) ->
                DropdownMenuItem(text={ Text(label) },onClick={ expanded=false;onAdd(kind) })
            }
        }
    }
}

@Composable
fun AttachmentCards(state: ChatUiState, onRemove: (String)->Unit, onPreview: (String)->Unit) {
    val palette = LocalDisper.current
    if (state.attachments.isEmpty()) return
    LazyRow(horizontalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.fillMaxWidth().heightIn(max=176.dp)) {
        items(state.attachments,key={it.id}) { card ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = palette.surface,
                border = BorderStroke(1.dp, palette.hairline),
                modifier = Modifier.widthIn(min=168.dp,max=248.dp),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(card.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
                    Text(card.handle?.let { tr("${it.mimeType} · ${it.sizeBytes} 字节", "${it.mimeType} · ${it.sizeBytes} bytes") } ?: tr("大小读取中", "Reading size"),
                        style=MaterialTheme.typography.labelSmall,color=palette.inkSubtle,maxLines=1,overflow=TextOverflow.Ellipsis)
                    VerticalGap(4.dp)
                    Text(when(card.state) {
                        AttachmentState.READING -> tr("正在读取", "Reading")
                        AttachmentState.PROCESSING -> tr("正在处理", "Processing")
                        AttachmentState.READY -> tr("已就绪 · 尚未发送", "Ready · Not sent yet")
                        AttachmentState.NEEDS_CONFIRMATION -> tr("部分内容 · 需要确认", "Partial content · Needs confirmation")
                        AttachmentState.FAILED -> tr("处理失败 · 请移除后重选", "Processing failed · Remove and reselect")
                    },style=MaterialTheme.typography.labelSmall,color=palette.inkMuted)
                    Row {
                        TextButton(onClick={onPreview(card.id)},enabled=card.state==AttachmentState.READY || card.state==AttachmentState.NEEDS_CONFIRMATION,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)) { Text(tr("预览", "Preview"), style=MaterialTheme.typography.labelMedium, color=palette.accent) }
                        TextButton(onClick={onRemove(card.id)},enabled=!state.preparingAttachments,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                            modifier=Modifier.semantics { contentDescription=tr("移除附件：${card.name}", "Remove attachment: ${card.name}") }) {
                            Text(tr("移除", "Remove"), style=MaterialTheme.typography.labelMedium, color=palette.inkSubtle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AttachmentPreviewDialog(preview:AttachmentPreview,onDismiss:()->Unit,onConfirm:()->Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(null,preview) {
        value=preview.image?.let { bytes->withContext(Dispatchers.Default) { android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size) } }
    }
    AlertDialog(onDismissRequest=onDismiss,title={Text(preview.name,maxLines=2,overflow=TextOverflow.Ellipsis)},
        text={Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
            bitmap?.let { Image(it.asImageBitmap(),contentDescription=tr("附件图片预览", "Attachment image preview"),modifier=Modifier.fillMaxWidth()) }
            preview.text?.let { Text(it) }
            if(preview.partial) Text(tr("文件超过本次文字预算，仅处理了标明范围的开头部分；确认后只发送该范围。预览最多显示前 8000 个字符。", "The file exceeds the text budget for this request, so only the marked leading range was processed; only that range is sent after confirmation. The preview shows at most the first 8000 characters."))
            Text(tr("内容尚未发送给模型。", "Content has not been sent to the model yet."))
        }},
        confirmButton={ if(preview.partial) TextButton(onClick=onConfirm) { Text(tr("确认处理范围", "Confirm range")) } else TextButton(onClick=onDismiss) { Text(tr("关闭", "Close")) } },
        dismissButton={ if(preview.partial) TextButton(onClick=onDismiss) { Text(tr("取消", "Cancel")) } })
}
