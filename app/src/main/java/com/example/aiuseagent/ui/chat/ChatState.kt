package com.example.aiuseagent.ui.chat

import com.example.aiuseagent.data.local.SessionEntity
import com.example.aiuseagent.ui.i18n.tr

enum class MessageRole(val raw: String) {
    USER("user"),
    ASSISTANT("assistant"),
    ;

    companion object {
        fun fromRaw(raw: String): MessageRole = entries.firstOrNull { it.raw == raw } ?: ASSISTANT
    }
}

data class ChatAttachment(val id: String, val name: String, val mimeType: String, val sizeBytes: Long)

data class ChatMessage(
    val id: Long,
    val role: MessageRole,
    val content: String,
    val createdAt: Long,
    /** true = 本地演示数据，不是模型回复；界面必须显示标记。 */
    val isDemo: Boolean,
    val status: String = "COMPLETED",
    val providerType: String? = null,
    val generationId: String? = null,
    val attachments: List<ChatAttachment> = emptyList(),
)

enum class ChatMode(private val labelZh: String, private val labelEn: String) {
    CONVERSATION("交流", "Chat"),
    BUILD("构建", "Build"),
    ;
    val label: String get() = tr(labelZh, labelEn)
}

class AttachmentPreview(val id:String,val name:String,val text:String?,val image:ByteArray?,val partial:Boolean) {
    override fun toString()="AttachmentPreview(id=$id)"
}
class HistoryAttachmentPreview(val id:String,val name:String,
    val snapshot:com.example.aiuseagent.data.attachment.HistoryAttachmentSnapshot,
    private val image:(String)->ByteArray?) {
    fun imageBytes(partId:String)=image(partId)
    override fun toString()="HistoryAttachmentPreview(id=$id)"
}

data class ChatUiState(
    val mode: ChatMode = ChatMode.CONVERSATION,
    val isReady: Boolean = false,
    val sessions: List<SessionEntity> = emptyList(),
    val activeSessionId: Long? = null,
    val messages: List<ChatMessage> = emptyList(),
    val input: String = "",
    val notice: String? = null,
    /** 非空表示正在等待用户确认删除；确认前不会真的删除。 */
    val pendingDeleteSession: SessionEntity? = null,
    val pendingRenameSession: SessionEntity? = null,
    val renameDraft: String = "",
    val renameError: String? = null,
    val isGenerating: Boolean = false,
    val generationStatus: String? = null,
    val generationText: String? = null,
    val generationError: String? = null,
    val toolActivities: List<String> = emptyList(),
    val canRetry: Boolean = false,
    val modelLabel: String = tr("模型未配置", "Model not configured"),
    val workspaceId: String = "default",
    val workspaceName: String = tr("默认工作区", "Default workspace"),
    val workspaceAvailable: Boolean = true,
    val viewingLegacy: Boolean = false,
    val attachments:List<com.example.aiuseagent.domain.attachment.DraftAttachment> = emptyList(),
    val attachmentSelections:Map<String,com.example.aiuseagent.domain.attachment.AttachmentSelection> = emptyMap(),
    val preparingAttachments:Boolean=false,
    val attachmentPreview:AttachmentPreview?=null,
    val historyAttachmentPreview:HistoryAttachmentPreview?=null,
) {
    val canSendMessage: Boolean get() {
        if (!isReady || viewingLegacy || isGenerating || preparingAttachments) return false
        if (input.isBlank() && attachments.isEmpty()) return false
        return attachments.all { card ->
            val handle = card.handle ?: return@all false
            when (card.state) {
                com.example.aiuseagent.domain.attachment.AttachmentState.READY -> true
                com.example.aiuseagent.domain.attachment.AttachmentState.NEEDS_CONFIRMATION ->
                    attachmentSelections[card.id]?.let { selection ->
                        selection.attachmentId == card.id && selection.sourceSha256 == handle.sha256 &&
                            selection.selectionConfirmed && selection.selectedPartIds.isNotEmpty()
                    } == true
                else -> false
            }
        }
    }
}
