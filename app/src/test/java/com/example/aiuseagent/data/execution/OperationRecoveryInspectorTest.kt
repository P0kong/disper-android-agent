package com.example.aiuseagent.data.execution

import com.example.aiuseagent.data.SessionContextScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class OperationRecoveryInspectorTest {
    private val scope=SessionContextScope(1,"BUILD","default",3,1,"profile",1)
    private val token="7d3536c0-eec8-4167-aad2-ce4b6eeb5cc2"
    private val row=JournalRecord("op",ApprovalBinding("g","call",scope,"digest"),"WRITE","UNKNOWN","recovery:$token",1,1)
    private val observed=FileRecoveryInspection(FileRecoveryTarget("a.txt","MISSING","a".repeat(64)),"a".repeat(64),true,true,null,null)

    @Test fun usedTokenCanBeReviewedWithoutAnyRestoreOrJournalTransition()=runTest {
        var reads=0
        val inspector=OperationRecoveryInspector({listOf(row)},{scope},{_,requested -> assertEquals(token,requested);reads++;observed})
        val result=inspector.review(1).single()
        assertEquals("OBSERVED",result.code)
        assertEquals(observed,result.inspection)
        assertEquals("UNKNOWN",result.state)
        assertEquals(1,reads)
    }
    @Test fun unrelatedSessionOrChangedWorkspaceCannotReadRecoveryStore()=runTest {
        var reads=0
        val inspector=OperationRecoveryInspector({listOf(row,row.copy(id="other",binding=row.binding.copy(scope=scope.copy(sessionId=2))))},
            {scope.copy(workspaceVersion=4)},{_,_->reads++;observed})
        val result=inspector.review(1)
        assertEquals(1,result.size);assertEquals("SCOPE_CHANGED",result.single().code)
        assertEquals(0,reads)
    }
    @Test fun lateScopeChangeAndSecretPathAreNotReturnedToUi()=runTest {
        var scopeReads=0
        var fileReads=0
        val late=OperationRecoveryInspector({listOf(row)},
            {if(scopeReads++<2)scope else scope.copy(contextEpoch=2)}, {_,_->fileReads++;observed})
        assertNull(late.review(1).single().inspection)
        assertEquals(1,fileReads)
        val secret=OperationRecoveryInspector({listOf(row)},{scope},
            {_,_->observed.copy(target=observed.target.copy(path="PRIVATE_CANARY.txt"))},{it.contains("PRIVATE_CANARY")})
        val hidden=secret.review(1).single()
        assertEquals("SENSITIVE_PATH",hidden.code);assertNull(hidden.inspection)
    }
    @Test fun missingLocatorIsExplicitAndReadFailuresNeverTriggerActions()=runTest {
        val missing=OperationRecoveryInspector({listOf(row.copy(receipt=null))},{scope},{_,_->error("must not inspect")})
        assertEquals("NO_RECOVERY_REFERENCE",missing.review(1).single().code)
        val failed=OperationRecoveryInspector({listOf(row)},{scope},{_,_->throw FileMutationFailure("RECOVERY_INVALID")})
        assertEquals("RECOVERY_UNAVAILABLE",failed.review(1).single().code)
    }
    @Test fun scopeChangeDuringJournalLoadCannotOpenOldRecoveryStore()=runTest {
        var current=scope
        var reads=0
        val inspector=OperationRecoveryInspector({current=scope.copy(contextEpoch=2);listOf(row)},
            {current},{_,_->reads++;observed})
        assertEquals("SCOPE_CHANGED",inspector.review(1).single().code)
        assertEquals(0,reads)
    }
    private val batch=DeleteBatchReceipt(token,"tree","PARTIAL",listOf(
        DeleteMemberReceipt(FrozenDeleteEntry("tree/a.txt",false,"a".repeat(64)),"SUCCEEDED",token),
        DeleteMemberReceipt(FrozenDeleteEntry("tree",true,"DIRECTORY"),"NOT_EXECUTED",token)),"SCOPE_CHANGED")
    @Test fun successfulDeletionRemainsInspectableButUnrelatedSuccessfulReadsDoNot()=runTest {
        val inspector=OperationRecoveryInspector({listOf(row.copy(state="APPLIED",receipt="deletion:$token"),row.copy(state="APPLIED"))},{scope},
            {_,_->error("not a file")},inspectDeletion={_,_->batch.copy(state="SUCCEEDED")})
        val result=inspector.review(1).single()
        assertEquals("APPLIED",result.state);assertEquals("SUCCEEDED",result.batch!!.state)
    }
    @Test fun partialBatchCanBeReviewedWithIndividualCompletedAndUnexecutedMembers()=runTest {
        var reads=0
        val inspector=OperationRecoveryInspector({listOf(row.copy(state="PARTIAL",receipt="deletion:$token"))},{scope},
            {_,_->error("not a file recovery")},inspectDeletion={_,requested ->assertEquals(token,requested);reads++;batch})
        val result=inspector.review(1).single()
        assertEquals("OBSERVED",result.code);assertEquals(batch,result.batch);assertNull(result.inspection)
        assertEquals(1,reads)
    }
    @Test fun batchMetadataIsHiddenWhenAnyMemberIsSensitiveOrScopeChangedDuringRead()=runTest {
        val record=row.copy(receipt="deletion:$token")
        val hidden=OperationRecoveryInspector({listOf(record)},{scope},{_,_->error("not a file")},
            {it.contains("a.txt")},inspectDeletion={_,_->batch})
        assertEquals("SENSITIVE_PATH",hidden.review(1).single().code)
        assertNull(hidden.review(1).single().batch)
        var current=scope
        val changed=OperationRecoveryInspector({listOf(record)},{current},{_,_->error("not a file")},
            inspectDeletion={_,_->current=scope.copy(contextEpoch=2);batch})
        val changedResult=changed.review(1).single()
        assertEquals("SCOPE_CHANGED",changedResult.code)
        assertNull(changedResult.batch)
    }
}
