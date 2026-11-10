package com.example.aiuseagent.domain.attachment

data class AttachmentScope(val draftId:String,val sessionId:Long?,val mode:String,val contextEpoch:Long,
    val workspaceId:String?,val workspaceVersion:Long,val profileId:String?,val profileRevision:Long,val selectionRevision:Long)
data class AttachmentHandle(val id:String,val localToken:String,val sha256:String,val mimeType:String,val sizeBytes:Long) {
    override fun toString()="AttachmentHandle(id=$id,bytes=$sizeBytes)"
}
data class AttachmentLimits(val maxCount:Int=10,val maxFileBytes:Long=20L*1024*1024,val maxTotalBytes:Long=50L*1024*1024,
    val maxPixels:Long=20_000_000,val maxDocumentCharacters:Int=200_000,val maxMessageCharacters:Int=400_000,
    val parseTimeoutMillis:Long=30_000)
enum class AttachmentState { READING, PROCESSING, READY, NEEDS_CONFIRMATION, FAILED }
data class DraftAttachment(val id:String,val scope:AttachmentScope,val name:String,val state:AttachmentState,
    val handle:AttachmentHandle?=null,val error:String?=null) {
    override fun toString()="DraftAttachment(id=$id,state=$state)"
}
data class AttachmentSelection(val attachmentId:String,val sourceSha256:String,val parserRevision:String,
    val selectedPartIds:List<String>,val selectionConfirmed:Boolean)
class FrozenAttachmentInput(val handle:AttachmentHandle,val parserRevision:String,selectedPartIds:List<String>,
    val selectedContentSha256:String,val selectionConfirmed:Boolean,val modelBudgetRevision:String) {
    val selectedPartIds:List<String> = java.util.Collections.unmodifiableList(ArrayList(selectedPartIds))
    override fun toString()="FrozenAttachmentInput(id=${handle.id})"
}
class AttachmentFailure(val code:String):Exception(code)
enum class AttachmentPickerKind { FILE, PHOTO, CAMERA }
data class PickerTicket(val requestId:String,val scope:AttachmentScope,val kind:AttachmentPickerKind,val outputUri:String?=null) {
    override fun toString()="PickerTicket(id=$requestId,kind=$kind)"
}

/** Parser-owned identity and source; UI may select IDs but cannot supply replacement content. */
data class AttachmentPart(val id:String,val source:String,val text:String?=null,val image:AttachmentHandle?=null) {
    init {require((text!=null)!=(image!=null))}
    override fun toString()="AttachmentPart(id=$id,content=<omitted>)"
}
class AttachmentParseResult(val sourceSha256:String,val parserRevision:String,parts:List<AttachmentPart>,val complete:Boolean=true) {
    val parts:List<AttachmentPart> = java.util.Collections.unmodifiableList(ArrayList(parts))
}
