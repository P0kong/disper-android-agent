package com.example.aiuseagent.data.attachment

/** Durable allocation precedes source writes; sealing precedes publication of a complete source. */
interface AttachmentOwnershipJournal {
    suspend fun allocate(token:String)
    suspend fun seal(value:OwnedAttachmentObject)
    suspend fun register(values:List<OwnedAttachmentObject>)
    suspend fun referencedObjects(attachmentId:String):List<OwnedAttachmentObject> = emptyList()
}

interface AttachmentObjectPersistence {
    suspend fun bind(attachmentId:String,objects:List<OwnedAttachmentObject>)
    suspend fun referencedObjects(attachmentId:String):List<OwnedAttachmentObject>
    suspend fun prepareSessionDeletion(sessionId:Long)
}
