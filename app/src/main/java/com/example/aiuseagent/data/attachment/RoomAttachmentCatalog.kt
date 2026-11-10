package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.data.local.AppDatabase
import com.example.aiuseagent.data.local.AttachmentObjectEntity
import com.example.aiuseagent.data.local.AttachmentObjectReferenceEntity
import androidx.room.withTransaction

class RoomAttachmentCatalog(private val database:AppDatabase):AttachmentOwnershipJournal,AttachmentObjectPersistence,AttachmentCleanupCatalog,HistoryAttachmentLookup {
    suspend fun bindLegacy(expected:HistoryAttachmentRecord,prepared:PreparedHistoryAttachment):Boolean = database.withTransaction {
        check(expected.snapshot==null && expected.objects.isEmpty())
        val row=expected.attachment
        // Deletion, another upgrade, or any owner/receipt change wins over this stale attempt.
        if(find(row.id)!=expected) return@withTransaction false
        check(row.snapshotToken==null && row.snapshotSha256==null && row.snapshotSizeBytes==null)
        val original=OwnedAttachmentObject(row.localToken,row.sha256,row.sizeBytes).also(::validate)
        check(prepared.objects.size==2 && prepared.objects.toSet()==setOf(original,prepared.snapshot) && original!=prepared.snapshot)
        val current=dao.find(original.token)
        if(current==null) dao.insert(AttachmentObjectEntity(original.token,original.sha256,original.sizeBytes,"READY"))
        else check(current.state=="READY" && current.toOwned()==original) {"Legacy object ownership unavailable"}
        check(dao.legacySnapshot(row.id,checkNotNull(row.selectionJson),prepared.snapshot.token,
            prepared.snapshot.sha256,prepared.snapshot.sizeBytes)==1)
        bind(row.id,prepared.objects)
        true
    }
    private val dao get()=database.attachmentObjectDao()
    override suspend fun allocate(token:String) {
        requireAttachmentObjectToken(token)
        dao.insert(AttachmentObjectEntity(token,null,null,"ALLOCATED"))
    }
    override suspend fun seal(value:OwnedAttachmentObject) {
        validate(value)
        database.withTransaction {
            val current=checkNotNull(dao.find(value.token))
            if(current.state=="READY" && current.toOwned()==value) return@withTransaction
            check(dao.seal(value.token,value.sha256,value.sizeBytes)==1)
        }
    }
    override suspend fun register(values:List<OwnedAttachmentObject>) {
        require(values.size in 1..4096 && values.map {it.token}.distinct().size==values.size)
        values.forEach(::validate)
        database.withTransaction {values.forEach {dao.insert(AttachmentObjectEntity(it.token,it.sha256,it.sizeBytes,"READY"))}}
    }
    override suspend fun candidates():List<String> = dao.candidates()
    override suspend fun claim(token:String):AttachmentCleanupClaim {
        requireAttachmentObjectToken(token)
        return database.withTransaction {
            val current=dao.find(token) ?: return@withTransaction AttachmentCleanupClaim.Retained
            if(dao.references(token)>0) return@withTransaction AttachmentCleanupClaim.Retained
            // ALLOCATED has no trustworthy final hash. It is inspectable, never guessed or age-swept.
            if(current.state !in setOf("READY","DELETING")) return@withTransaction AttachmentCleanupClaim.Review
            val value=runCatching {current.toOwned().also(::validate)}.getOrNull() ?: return@withTransaction AttachmentCleanupClaim.Review
            check(dao.state(token,"DELETING")==1)
            AttachmentCleanupClaim.Deleting(value)
        }
    }
    override suspend fun finish(token:String) {
        requireAttachmentObjectToken(token)
        database.withTransaction {check(dao.finish(token)==1 || dao.find(token)==null)}
    }
    override suspend fun referencedObjects(attachmentId:String):List<OwnedAttachmentObject> = database.withTransaction {
        dao.forAttachment(attachmentId).map {row -> require(row.state=="READY");row.toOwned().also(::validate)}
    }
    override suspend fun find(attachmentId:String):HistoryAttachmentRecord? = database.withTransaction {
        val row=dao.attachment(attachmentId) ?: return@withTransaction null
        val message=database.messageDao().findById(row.messageId) ?: return@withTransaction null
        val saved=if(row.snapshotToken==null && row.snapshotSha256==null && row.snapshotSizeBytes==null) null
            else OwnedAttachmentObject(requireNotNull(row.snapshotToken),requireNotNull(row.snapshotSha256),requireNotNull(row.snapshotSizeBytes)).also(::validate)
        HistoryAttachmentRecord(row,message,saved,referencedObjects(attachmentId))
    }
    override suspend fun bind(attachmentId:String,objects:List<OwnedAttachmentObject>) {
        requireAttachmentObjectToken(attachmentId)
        require(objects.size in 2..2050 && objects.map {it.token}.distinct().size==objects.size)
        objects.forEach(::validate)
        database.withTransaction {
            val row=checkNotNull(dao.attachment(attachmentId))
            val message=checkNotNull(database.messageDao().findById(row.messageId))
            check(message.sessionId==row.sessionId && message.role=="user" && message.contextEpoch==row.contextEpoch &&
                message.profileId==row.profileId && message.profileRevision==row.profileRevision)
            check(row.status=="PREPARED")
            val original=OwnedAttachmentObject(row.localToken,row.sha256,row.sizeBytes)
            val snapshot=OwnedAttachmentObject(checkNotNull(row.snapshotToken),checkNotNull(row.snapshotSha256),checkNotNull(row.snapshotSizeBytes))
            check(original in objects && snapshot in objects && original.token!=snapshot.token)
            for(value in objects) {
                val owned=checkNotNull(dao.find(value.token))
                check(owned.state=="READY" && owned.toOwned()==value) {"Object not available for binding"}
            }
            objects.forEach {dao.reference(AttachmentObjectReferenceEntity(attachmentId,it.token))}
        }
    }
    override suspend fun prepareSessionDeletion(sessionId:Long) {
        database.withTransaction {
            for(row in database.attachmentDao().inSession(sessionId)) {
                val values=mutableListOf(OwnedAttachmentObject(row.localToken,row.sha256,row.sizeBytes))
                if(row.snapshotToken!=null && row.snapshotSha256!=null && row.snapshotSizeBytes!=null)
                    values+=OwnedAttachmentObject(row.snapshotToken,row.snapshotSha256,row.snapshotSizeBytes)
                for(value in values) {
                    // Invalid legacy metadata never becomes filesystem deletion authority.
                    if(runCatching {validate(value)}.isFailure) continue
                    val current=dao.find(value.token)
                    if(current==null) dao.insert(AttachmentObjectEntity(value.token,value.sha256,value.sizeBytes,"READY"))
                    else if(runCatching {current.toOwned()==value && current.state=="READY"}.getOrDefault(false).not())
                        check(dao.state(value.token,"REVIEW")==1)
                }
            }
        }
    }
    private fun AttachmentObjectEntity.toOwned()=OwnedAttachmentObject(token,requireNotNull(sha256),requireNotNull(sizeBytes))
    private fun validate(value:OwnedAttachmentObject) {
        requireAttachmentObjectToken(value.token)
        require(value.sha256.matches(Regex("[a-f0-9]{64}")) && value.sizeBytes in 1..20L*1024*1024)
    }
}
