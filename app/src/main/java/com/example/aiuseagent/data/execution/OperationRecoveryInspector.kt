package com.example.aiuseagent.data.execution

import com.example.aiuseagent.data.SessionContextScope
import kotlinx.coroutines.CancellationException

data class OperationRecoveryReview(val operationId:String,val state:String,val reference:String?,
    val code:String,val inspection:FileRecoveryInspection?=null,val batch:DeleteBatchReceipt?=null)

/** User-initiated read-only inspection. It has no executor, restore or journal-write dependency. */
class OperationRecoveryInspector(
    private val load:suspend (Long)->List<JournalRecord>,
    private val currentScope:suspend (Long)->SessionContextScope?,
    private val inspect:(SessionContextScope,String)->FileRecoveryInspection,
    private val containsSecret:(String)->Boolean={false},
    private val inspectDeletion:((SessionContextScope,String)->DeleteBatchReceipt)?=null,
) {
    suspend fun review(sessionId:Long):List<OperationRecoveryReview> {
        val scope=currentScope(sessionId) ?: return emptyList()
        if(scope.sessionId!=sessionId || scope.mode!="BUILD") return emptyList()
        return load(sessionId).asSequence().filter { it.binding.scope.sessionId==sessionId &&
            (it.state in setOf("RUNNING","UNKNOWN","PARTIAL") || (it.state=="APPLIED" && it.receipt?.startsWith("deletion:")==true)) }
            .take(50).toList().map { row ->
                fun unavailable(code:String)=OperationRecoveryReview(row.id,row.state,null,code)
                val bound=row.binding.scope
                if(bound.mode!="BUILD" || bound.workspaceId!=scope.workspaceId || bound.workspaceVersion!=scope.workspaceVersion)
                    return@map unavailable("SCOPE_CHANGED")
                if(scope.workspaceId!="default") return@map unavailable("BACKEND_UNSUPPORTED")
                val reference=row.receipt?.takeIf { REFERENCE.matches(it) }
                    ?: return@map unavailable("NO_RECOVERY_REFERENCE")
                try {
                    if(currentScope(sessionId)!=scope) return@map unavailable("SCOPE_CHANGED")
                    if(reference.startsWith("deletion:")) {
                        val batch=inspectDeletion?.invoke(scope,reference.removePrefix("deletion:"))
                            ?: return@map unavailable("BACKEND_UNSUPPORTED")
                        if(currentScope(sessionId)!=scope) return@map unavailable("SCOPE_CHANGED")
                        if(containsSecret(batch.target) || batch.members.any { containsSecret(it.entry.path) })
                            return@map unavailable("SENSITIVE_PATH")
                        return@map OperationRecoveryReview(row.id,row.state,reference,"OBSERVED",batch=batch)
                    }
                    val observation=inspect(scope,reference.removePrefix("recovery:"))
                    if(currentScope(sessionId)!=scope) return@map unavailable("SCOPE_CHANGED")
                    if(containsSecret(observation.target.path)) return@map unavailable("SENSITIVE_PATH")
                    OperationRecoveryReview(row.id,row.state,reference,"OBSERVED",observation)
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(_:Exception) { unavailable("RECOVERY_UNAVAILABLE") }
            }
    }
    companion object {
        private val REFERENCE=Regex("(?:recovery|deletion):[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    }
}
