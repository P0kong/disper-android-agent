package com.example.aiuseagent.data.execution

import com.example.aiuseagent.data.SessionContextScope
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OperationJournalTest {
    private val scope=SessionContextScope(1,"BUILD","A",1,1,"p",1)
    private fun operation(digest: String="digest")=ExecutionOperation(ApprovalBinding("g","call",scope,digest),OperationFacts(Effect.WRITE),"Write synthetic file")
    @Test fun approvalActuallyWaitsAndRepeatedCallCannotRepeatSideEffect()=runTest {
        val approvals=ApprovalRepository { testScheduler.currentTime }
        val journal=MemoryOperationJournal()
        val broker=ExecutionBroker(journal,approvals,{ PermissionSnapshot(PermissionLevel.VIEW_ONLY,1) },{true},{it.facts})
        var effects=0
        val first=async { broker.execute(operation()) { effects++;ExecutionOutcome("APPLIED","receipt") } }
        runCurrent();assertEquals(0,effects);assertFalse(first.isCompleted)
        assertTrue(approvals.resolveFromUser(approvals.pending.value.single().id,true))
        assertEquals("APPLIED",first.await().state);assertEquals(1,effects)
        assertEquals("APPLIED",broker.execute(operation()) { effects++;ExecutionOutcome("APPLIED") }.state)
        assertEquals(1,effects)
        assertEquals("CONFLICT",broker.execute(operation("changed")) { effects++;ExecutionOutcome("APPLIED") }.state)
        assertEquals(1,effects)
    }
    @Test fun receiptLossAndRestartAreUnknownAndNeverAutomaticallyReplayed()=runTest {
        val journal=MemoryOperationJournal();var effects=0
        fun broker()=ExecutionBroker(journal,ApprovalRepository(),{ PermissionSnapshot(PermissionLevel.FULL,1) },{true},{it.facts})
        assertEquals("UNKNOWN",broker().execute(operation()) { effects++;error("receipt lost after side effect") }.state)
        val reopened=broker();reopened.recover()
        assertEquals("UNKNOWN",reopened.execute(operation()) { effects++;ExecutionOutcome("APPLIED") }.state)
        assertEquals(1,effects)
    }
    @Test fun changedPermissionScopeOrGrantAfterApprovalPreventsExecution()=runTest {
        for(change in listOf("scope","permission","grant")) {
            val approvals=ApprovalRepository { testScheduler.currentTime };var valid=true;var revision=1L;var writable=true;var effects=0
            val broker=ExecutionBroker(MemoryOperationJournal(),approvals,{PermissionSnapshot(PermissionLevel.VIEW_ONLY,revision)},
                {valid},{it.facts.copy(writable=writable)})
            val request=async { broker.execute(operation()) { effects++;ExecutionOutcome("APPLIED") } }
            runCurrent();val id=approvals.pending.value.single().id
            when(change){"scope"->valid=false;"permission"->revision++;else->writable=false}
            approvals.resolveFromUser(id,true)
            assertNotEquals("APPLIED",request.await().state);assertEquals(0,effects)
        }
    }
    @Test fun cancellationWhileWaitingHasNoSideEffectAndNoReusableApproval()=runTest {
        val approvals=ApprovalRepository { testScheduler.currentTime };var effects=0
        val broker=ExecutionBroker(MemoryOperationJournal(),approvals,{PermissionSnapshot(PermissionLevel.VIEW_ONLY,0)},{true},{it.facts})
        val request=launch { broker.execute(operation()) { effects++;ExecutionOutcome("APPLIED") } }
        runCurrent();val id=approvals.pending.value.single().id;request.cancelAndJoin()
        assertEquals(0,effects);assertFalse(approvals.resolveFromUser(id,true))
    }
    @Test fun approvalCanBeRevokedOrExpireWhileTheRunningReservationIsPersisted()=runTest {
        for(expire in listOf(false,true)) {
            val approvals=ApprovalRepository { testScheduler.currentTime }
            val memory=MemoryOperationJournal();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
            val journal=object: OperationJournal by memory {
                override suspend fun transition(id: String,expected: String,state: String,receipt: String?,updatedAt: Long): Boolean {
                    if(state=="RUNNING") { entered.complete(Unit);release.await() }
                    return memory.transition(id,expected,state,receipt,updatedAt)
                }
            }
            val broker=ExecutionBroker(journal,approvals,{PermissionSnapshot(PermissionLevel.VIEW_ONLY,0)},{true},{it.facts})
            var effects=0
            val request=async { broker.execute(operation()) { effects++;ExecutionOutcome("APPLIED") } }
            runCurrent();approvals.resolveFromUser(approvals.pending.value.single().id,true)
            entered.await()
            if(expire) advanceTimeBy(ApprovalRepository.TTL_MILLIS) else approvals.invalidateAll()
            release.complete(Unit)
            assertNotEquals("APPLIED",request.await().state);assertEquals(0,effects)
        }
    }
}
