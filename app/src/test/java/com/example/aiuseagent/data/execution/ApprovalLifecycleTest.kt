package com.example.aiuseagent.data.execution

import com.example.aiuseagent.data.SessionContextScope
import org.junit.Assert.*
import org.junit.Test

class ApprovalLifecycleTest {
    private val scope=SessionContextScope(1,"BUILD","A",1,1,"profile-a",1)
    private fun binding()=ApprovalBinding("generation-a","call-a",scope,"digest-a")
    @Test fun receiptIsSingleUseAndEveryScopeOrDigestChangeInvalidatesIt() {
        var now=100L
        val approvals=ApprovalRepository { now }
        val original=binding()
        for(changed in listOf(original.copy(digest="other"),original.copy(generationId="other"),
            original.copy(scope=scope.copy(contextEpoch=2)),original.copy(scope=scope.copy(workspaceId="B")),
            original.copy(scope=scope.copy(workspaceVersion=2)),original.copy(scope=scope.copy(profileId="profile-b")),
            original.copy(scope=scope.copy(profileRevision=2)))) {
            val id=approvals.request(original,"synthetic write")
            assertTrue(approvals.resolveFromUser(id,true))
            assertFalse(approvals.consume(id,changed))
            assertFalse(approvals.consume(id,original))
        }
        val id=approvals.request(original,"synthetic write")
        assertTrue(approvals.resolveFromUser(id,true));assertFalse(approvals.resolveFromUser(id,true))
        assertTrue(approvals.consume(id,original));assertFalse(approvals.consume(id,original))
    }

    @Test fun denialExpiryInvalidationAndNewProcessNeverGrantPermission() {
        var now=0L
        val approvals=ApprovalRepository { now }
        val denied=approvals.request(binding(),"fixture");assertTrue(approvals.resolveFromUser(denied,false));assertFalse(approvals.consume(denied,binding()))
        val expired=approvals.request(binding(),"fixture");now=300_001
        assertFalse(approvals.resolveFromUser(expired,true));assertFalse(approvals.consume(expired,binding()))
        val invalidated=approvals.request(binding(),"fixture");approvals.invalidateAll()
        assertFalse(approvals.resolveFromUser(invalidated,true))
        assertFalse(ApprovalRepository { now }.consume(invalidated,binding()))
    }
}
