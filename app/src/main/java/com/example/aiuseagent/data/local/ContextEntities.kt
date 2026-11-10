package com.example.aiuseagent.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Only non-secret connection configuration; credentials are never a Room column. */
@Entity(tableName="connection_profiles")
data class ConnectionProfileEntity(
    @PrimaryKey val id: String, val brand: String, val protocol: String, val baseUrl: String,
    val model: String, val revision: Long, val capabilityRevision: String,
    val profileJson: String? = null,
)

@Entity(tableName="attachments",foreignKeys=[
    ForeignKey(entity=SessionEntity::class,parentColumns=["id"],childColumns=["sessionId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=MessageEntity::class,parentColumns=["id"],childColumns=["messageId"],onDelete=ForeignKey.CASCADE),
],indices=[Index("sessionId"),Index("messageId")])
data class AttachmentEntity(
    @PrimaryKey val id: String, val sessionId: Long,val messageId: Long,val contextEpoch: Long,
    val profileId: String,val profileRevision: Long,val name: String,val mimeType: String,
    val sizeBytes: Long,val localToken: String,val sha256: String,val status: String,
    val selectionJson:String?=null,
    val snapshotToken:String?=null,val snapshotSha256:String?=null,val snapshotSizeBytes:Long?=null,
) { override fun toString()="AttachmentEntity(id=$id,status=$status)" }

/** Durable ownership intent and cleanup candidate. Never contains a picker URI or absolute path. */
@Entity(tableName="attachment_objects")
data class AttachmentObjectEntity(@PrimaryKey val token:String,val sha256:String?,val sizeBytes:Long?,val state:String)

@Entity(tableName="attachment_object_refs",primaryKeys=["attachmentId","token"],foreignKeys=[
    ForeignKey(entity=AttachmentEntity::class,parentColumns=["id"],childColumns=["attachmentId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=AttachmentObjectEntity::class,parentColumns=["token"],childColumns=["token"],onDelete=ForeignKey.NO_ACTION),
],indices=[Index("token")])
data class AttachmentObjectReferenceEntity(val attachmentId:String,val token:String)

@Dao interface AttachmentObjectDao {
    @Query("SELECT * FROM attachment_objects WHERE token=:token") suspend fun find(token:String):AttachmentObjectEntity?
    @Query("SELECT token FROM attachment_objects ORDER BY token") suspend fun candidates():List<String>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(value:AttachmentObjectEntity)
    @Query("UPDATE attachment_objects SET sha256=:sha256,sizeBytes=:sizeBytes,state='READY' WHERE token=:token AND state='ALLOCATED'")
    suspend fun seal(token:String,sha256:String,sizeBytes:Long):Int
    @Query("UPDATE attachment_objects SET state=:state WHERE token=:token") suspend fun state(token:String,state:String):Int
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun reference(value:AttachmentObjectReferenceEntity)
    @Query("SELECT o.* FROM attachment_objects o JOIN attachment_object_refs r ON r.token=o.token WHERE r.attachmentId=:id ORDER BY o.token")
    suspend fun forAttachment(id:String):List<AttachmentObjectEntity>
    @Query("SELECT * FROM attachments WHERE id=:id") suspend fun attachment(id:String):AttachmentEntity?
    @Query("UPDATE attachments SET snapshotToken=:token,snapshotSha256=:sha256,snapshotSizeBytes=:sizeBytes WHERE id=:id AND selectionJson=:receipt AND snapshotToken IS NULL AND snapshotSha256 IS NULL AND snapshotSizeBytes IS NULL")
    suspend fun legacySnapshot(id:String,receipt:String,token:String,sha256:String,sizeBytes:Long):Int
    @Query("SELECT (SELECT COUNT(*) FROM attachment_object_refs WHERE token=:token) + (SELECT COUNT(*) FROM attachments WHERE localToken=:token OR snapshotToken=:token)")
    suspend fun references(token:String):Int
    @Query("DELETE FROM attachment_objects WHERE token=:token AND state='DELETING' AND NOT EXISTS(SELECT 1 FROM attachment_object_refs WHERE token=:token) AND NOT EXISTS(SELECT 1 FROM attachments WHERE localToken=:token OR snapshotToken=:token)")
    suspend fun finish(token:String):Int
}

/** Receipts and digests only: no command text, file bodies, API secrets or raw stdout. */
@Entity(tableName="operation_journal",foreignKeys=[
    ForeignKey(entity=SessionEntity::class,parentColumns=["id"],childColumns=["sessionId"],onDelete=ForeignKey.CASCADE),
    ForeignKey(entity=GenerationEntity::class,parentColumns=["id"],childColumns=["generationId"],onDelete=ForeignKey.CASCADE),
],indices=[Index("sessionId"),Index(value=["generationId","callId"],unique=true)])
data class OperationJournalEntity(
    @PrimaryKey val operationId: String,val generationId: String?,val callId: String,val sessionId: Long,
    val contextEpoch: Long,val workspaceId: String,val workspaceVersion: Long,
    val profileId: String,val profileRevision: Long,val digest: String,val kind: String,
    val state: String,val createdAt: Long,val updatedAt: Long,val receipt: String?,
) { override fun toString()="OperationJournalEntity(id=$operationId,kind=$kind,state=$state)" }

@Dao interface ConnectionProfileDao {
    @Query("SELECT * FROM connection_profiles ORDER BY id") fun observe(): Flow<List<ConnectionProfileEntity>>
    @Query("SELECT * FROM connection_profiles WHERE id=:id") suspend fun find(id: String): ConnectionProfileEntity?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(profile: ConnectionProfileEntity)
    @Query("UPDATE connection_profiles SET brand=:brand, protocol=:protocol, baseUrl=:baseUrl, model=:model, revision=:revision, capabilityRevision=:capabilityRevision, profileJson=:profileJson WHERE id=:id AND revision=:expectedRevision")
    suspend fun update(id: String,expectedRevision: Long,brand: String,protocol: String,baseUrl: String,model: String,revision: Long,capabilityRevision: String,profileJson: String?): Int
}

@Dao interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE sessionId=:sessionId ORDER BY messageId,id") fun observeInSession(sessionId: Long): Flow<List<AttachmentEntity>>
    @Query("SELECT * FROM attachments WHERE sessionId=:sessionId ORDER BY messageId,id") suspend fun inSession(sessionId: Long): List<AttachmentEntity>
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(attachment: AttachmentEntity)
    @Query("DELETE FROM attachments WHERE id=:id AND sessionId=:sessionId") suspend fun delete(id: String,sessionId: Long): Int
}

@Dao interface OperationJournalDao {
    @Query("SELECT * FROM operation_journal WHERE sessionId=:sessionId AND generationId IS NOT NULL AND (state IN ('RUNNING','UNKNOWN','PARTIAL') OR (state='APPLIED' AND receipt LIKE 'deletion:%')) ORDER BY updatedAt DESC,operationId LIMIT 50")
    suspend fun uncertain(sessionId:Long):List<OperationJournalEntity>
    @Query("SELECT * FROM operation_journal WHERE operationId=:id") suspend fun find(id: String): OperationJournalEntity?
    @Query("SELECT * FROM operation_journal WHERE generationId=:generationId AND callId=:callId") suspend fun findCall(generationId: String,callId: String): OperationJournalEntity?
    @Insert(onConflict=OnConflictStrategy.ABORT) suspend fun insert(operation: OperationJournalEntity)
    @Query("UPDATE operation_journal SET state=:state,updatedAt=:updatedAt,receipt=:receipt WHERE operationId=:id AND state=:expected")
    suspend fun transition(id: String,expected: String,state: String,updatedAt: Long,receipt: String?): Int
    @Query("UPDATE operation_journal SET state='UNKNOWN',updatedAt=:updatedAt WHERE state='RUNNING'")
    suspend fun recoverUnknown(updatedAt: Long): Int
    @Query("UPDATE operation_journal SET state='CANCELLED',updatedAt=:updatedAt WHERE state='WAITING'")
    suspend fun recoverWaiting(updatedAt: Long): Int
}
