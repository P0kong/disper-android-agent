package com.example.aiuseagent.data.execution

class MemoryOperationJournal:OperationJournal {
    val rows=linkedMapOf<String,JournalRecord>()
    override suspend fun find(generationId:String,callId:String)=rows.values.firstOrNull { it.binding.generationId==generationId && it.binding.callId==callId }
    override suspend fun insert(record:JournalRecord):Boolean {
        if(find(record.binding.generationId,record.binding.callId)!=null) return false
        rows[record.id]=record;return true
    }
    override suspend fun transition(id:String,expected:String,state:String,receipt:String?,updatedAt:Long):Boolean {
        val old=rows[id] ?: return false;if(old.state!=expected) return false
        rows[id]=old.copy(state=state,receipt=receipt,updatedAt=updatedAt);return true
    }
    override suspend fun recover(now:Long) {
        rows.replaceAll { _,row -> when(row.state) { "RUNNING"->row.copy(state="UNKNOWN",updatedAt=now);"WAITING"->row.copy(state="CANCELLED",updatedAt=now);else->row } }
    }
}
