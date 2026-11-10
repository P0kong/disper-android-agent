package com.example.aiuseagent.data.execution

import com.example.aiuseagent.data.SessionContextScope
import com.example.aiuseagent.data.local.OperationJournalDao
import com.example.aiuseagent.data.local.OperationJournalEntity

data class JournalRecord(val id: String,val binding: ApprovalBinding,val kind: String,val state: String,
    val receipt: String?,val createdAt: Long,val updatedAt: Long)
interface OperationJournal {
    suspend fun find(generationId: String,callId: String): JournalRecord?
    suspend fun insert(record: JournalRecord): Boolean
    suspend fun transition(id: String,expected: String,state: String,receipt: String?,updatedAt: Long): Boolean
    suspend fun recover(now: Long)
}
class RoomOperationJournal(private val dao: OperationJournalDao): OperationJournal {
    suspend fun uncertain(sessionId:Long):List<JournalRecord> = dao.uncertain(sessionId).map(::record)
    override suspend fun find(generationId: String,callId: String): JournalRecord? = dao.findCall(generationId,callId)?.let(::record)
    private fun record(it:OperationJournalEntity):JournalRecord =
        JournalRecord(it.operationId,ApprovalBinding(requireNotNull(it.generationId),it.callId,
            SessionContextScope(it.sessionId,"BUILD",it.workspaceId,it.workspaceVersion,it.contextEpoch,it.profileId,it.profileRevision),it.digest),
            it.kind,it.state,it.receipt,it.createdAt,it.updatedAt)
    override suspend fun insert(record: JournalRecord): Boolean {
        val b=record.binding;val s=b.scope
        val row=OperationJournalEntity(record.id,b.generationId,b.callId,s.sessionId,s.contextEpoch,
            requireNotNull(s.workspaceId),s.workspaceVersion,s.profileId,s.profileRevision,b.digest,
            record.kind,record.state,record.createdAt,record.updatedAt,record.receipt)
        return try { dao.insert(row);true } catch(failure: Exception) {
            if(dao.findCall(b.generationId,b.callId)!=null) false else throw failure
        }
    }
    override suspend fun transition(id: String,expected: String,state: String,receipt: String?,updatedAt: Long): Boolean =
        dao.transition(id,expected,state,updatedAt,receipt)==1
    override suspend fun recover(now: Long) { dao.recoverUnknown(now);dao.recoverWaiting(now) }
}
