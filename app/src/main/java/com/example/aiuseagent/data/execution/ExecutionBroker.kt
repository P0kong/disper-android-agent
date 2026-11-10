package com.example.aiuseagent.data.execution

import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class PermissionSnapshot(val level: PermissionLevel,val revision: Long)

/** One durable reservation per tool call. Effects may begin only after the last scope/grant check. */
class ExecutionBroker(
    private val journal: OperationJournal,private val approvals: ApprovalRepository,
    private val permission: suspend (Long)->PermissionSnapshot,
    private val scopeIsCurrent: suspend (ApprovalBinding)->Boolean,
    private val resolveFacts: suspend (ExecutionOperation)->OperationFacts,
    private val now: ()->Long = System::currentTimeMillis,
) {
    private val mutex=Mutex()
    suspend fun recover()=journal.recover(now())
    suspend fun execute(operation: ExecutionOperation,action: suspend ()->ExecutionOutcome): ExecutionOutcome=
        executeGuarded(operation) { action() }
    suspend fun executeGuarded(operation:ExecutionOperation,
        action:suspend (suspend ()->Boolean)->ExecutionOutcome):ExecutionOutcome=mutex.withLock {
        val binding=operation.binding
        if(binding.scope.mode!="BUILD" || !scopeIsCurrent(binding)) return@withLock ExecutionOutcome("DENIED")
        fun existing(record: JournalRecord): ExecutionOutcome {
            if(record.binding!=binding) return ExecutionOutcome("CONFLICT")
            return when(record.state) {
                "WAITING" -> ExecutionOutcome("CONFLICT","OPERATION_PENDING")
                "RUNNING" -> ExecutionOutcome("UNKNOWN")
                else -> ExecutionOutcome(record.state,record.receipt)
            }
        }
        journal.find(binding.generationId,binding.callId)?.let { return@withLock existing(it) }
        val id=UUID.randomUUID().toString()
        if(!journal.insert(JournalRecord(id,binding,operation.facts.effect.name,"WAITING",operation.recoveryReference,now(),now()))) {
            return@withLock journal.find(binding.generationId,binding.callId)?.let(::existing) ?: ExecutionOutcome("UNKNOWN")
        }
        var phase="WAITING"
        var approvalId: String?=null
        suspend fun finish(outcome: ExecutionOutcome): ExecutionOutcome {
            // Keep the pre-effect locator even when the action or response is lost.
            val receipt=operation.recoveryReference ?: outcome.receipt?.takeIf { it.matches(Regex("[A-Za-z0-9:_-]{1,240}")) }
            val saved=withContext(NonCancellable) { journal.transition(id,phase,outcome.state,receipt,now()) }
            return if(saved) outcome.copy(receipt=receipt) else ExecutionOutcome("UNKNOWN")
        }
        try {
            val initial=permission(binding.scope.sessionId)
            val facts=resolveFacts(operation)
            if(facts!=operation.facts) return@withLock finish(ExecutionOutcome("CONFLICT"))
            val decision=PermissionGate.decide(initial.level,facts)
            var approved=false
            when(decision) {
                GateDecision.DENY -> return@withLock finish(ExecutionOutcome("DENIED"))
                GateDecision.UNSUPPORTED -> return@withLock finish(ExecutionOutcome("UNSUPPORTED"))
                GateDecision.SYSTEM_GRANT_REQUIRED -> return@withLock finish(ExecutionOutcome("SYSTEM_GRANT_REQUIRED"))
                GateDecision.APPROVAL_REQUIRED -> {
                    approvalId=approvals.request(binding,operation.preview)
                    approved=approvals.awaitDecision(approvalId)
                    if(!approved) return@withLock finish(ExecutionOutcome("DENIED"))
                }
                GateDecision.ALLOW -> Unit
            }
            suspend fun stillAllowed(): Boolean {
                if(!scopeIsCurrent(binding) || permission(binding.scope.sessionId)!=initial || resolveFacts(operation)!=facts) return false
                return PermissionGate.decide(initial.level,facts)==GateDecision.ALLOW || approved
            }
            if(!stillAllowed()) return@withLock finish(ExecutionOutcome("DENIED"))
            if(!journal.transition(id,"WAITING","RUNNING",operation.recoveryReference,now())) return@withLock ExecutionOutcome("UNKNOWN")
            phase="RUNNING"
            currentCoroutineContext().ensureActive()
            if(!stillAllowed()) return@withLock finish(ExecutionOutcome("CANCELLED"))
            // Keep the receipt revocable across all suspending persistence and resolver calls.
            // Consume at the final action boundary; expiry/background invalidation still applies.
            if(approved && !approvals.consume(requireNotNull(approvalId),binding))
                return@withLock finish(ExecutionOutcome("DENIED"))
            finish(action { currentCoroutineContext().ensureActive();stillAllowed() })
        } catch(failure: CancellationException) {
            withContext(NonCancellable) { journal.transition(id,phase,if(phase=="RUNNING") "UNKNOWN" else "CANCELLED",operation.recoveryReference,now()) }
            throw failure
        } catch(_: Exception) {
            // After RUNNING a failed response is not proof that the file operation did not happen.
            withContext(NonCancellable) { journal.transition(id,phase,if(phase=="RUNNING") "UNKNOWN" else "FAILED",operation.recoveryReference,now()) }
            ExecutionOutcome(if(phase=="RUNNING") "UNKNOWN" else "FAILED",operation.recoveryReference)
        } finally { approvalId?.let(approvals::discard) }
    }
}
