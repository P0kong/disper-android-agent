package com.example.aiuseagent.data.execution

data class FrozenDeleteEntry(val path:String,val directory:Boolean,val hash:String)
/** Trusted, process-local approval input. A restarted process must prepare a new inventory. */
class FrozenDeletion internal constructor(val target:String,entries:List<FrozenDeleteEntry>,internal val owner:Any) {
    val entries:List<FrozenDeleteEntry> = java.util.Collections.unmodifiableList(ArrayList(entries))
    val token:String = java.util.UUID.randomUUID().toString()
}
data class DeleteMemberReceipt(val entry:FrozenDeleteEntry,val state:String,val recoveryToken:String,val error:String?=null)
data class DeleteBatchReceipt(val token:String,val target:String,val state:String,val members:List<DeleteMemberReceipt>,val reason:String?=null)
