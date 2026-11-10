package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.AttachmentScope
import com.example.aiuseagent.data.local.AttachmentEntity

/** Trusted process-owned access to exact committed selections; never supplied by a model. */
class AttachmentGenerationInput(
    val scope:AttachmentScope,
    val current:PreparedAttachmentInput?,
    val commit:AttachmentCommit?,
    val isCurrent:()->Boolean,
    val restore:suspend (List<AttachmentEntity>)->Map<Long,PreparedAttachmentInput>,
) :AutoCloseable {
    private val leases=mutableListOf<AutoCloseable>()
    private var closed=false
    fun hold(lease:AutoCloseable?) {
        if(lease==null) return
        synchronized(leases) {if(closed) {lease.close();error("Attachment input already closed")};leases+=lease}
    }
    override fun close() {
        val held=synchronized(leases) {if(closed) emptyList() else {closed=true;leases.toList().also {leases.clear()}}}
        try {held.forEach {it.close()}} finally {commit?.releaseSending()}
    }
    override fun toString()="AttachmentGenerationInput(<omitted>)"
}
