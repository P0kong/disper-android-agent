package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.*

data class AttachmentCommitRecord(val handle:AttachmentHandle,val name:String,val selectionJson:String?=null,
    val snapshot:OwnedAttachmentObject?=null,val objects:List<OwnedAttachmentObject> = emptyList()) {
    override fun toString()="AttachmentCommitRecord(id=${handle.id})"
}
/** Produced after immutable copies/selections are prepared; guard is rechecked inside the DB transaction. */
class AttachmentCommit(val scope:AttachmentScope,records:List<AttachmentCommitRecord>,val isCurrent:()->Boolean,
    private val onFinish:suspend (Long?)->Unit={}) {
    val records:List<AttachmentCommitRecord> = java.util.Collections.unmodifiableList(ArrayList(records))
    private val sending=mutableListOf<AutoCloseable>()
    private var released=false
    internal fun holdSending(lease:AutoCloseable) {synchronized(sending) {check(!released);sending+=lease}}
    /** Settlement is not the end of a network send; only the generation/rejection owner calls this. */
    fun releaseSending() {
        val leases=synchronized(sending) {if(released) emptyList() else {released=true;sending.toList().also {sending.clear()}}}
        leases.forEach {it.close()}
    }
    suspend fun finish(sessionId:Long?)=onFinish(sessionId)
    override fun toString()="AttachmentCommit(draft=${scope.draftId},count=${records.size})"
}
