package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.data.*
import com.example.aiuseagent.domain.attachment.*
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AttachmentCommitTest {
    private val store=FakeDaoStore()
    private val repository=SessionRepository(store.sessionDao,store.messageDao,store.generationPersistence)
    private val scope=AttachmentScope("draft",null,"CHAT",0,null,0,"builtin:MOCK",0,0)
    private val handle=AttachmentHandle(UUID.randomUUID().toString(),UUID.randomUUID().toString(),"a".repeat(64),"text/plain",5)
    private fun commit(bound:AttachmentScope=scope,guard:()->Boolean={true})=AttachmentCommit(bound,listOf(AttachmentCommitRecord(handle,"selected.txt")),guard)
    @Test fun attachmentOnlyBlankDraftBindsRealSessionMessageAndGenerationAtomically()=runTest {
        val turn=repository.prepareGeneration(null,"","MOCK",null,"g",attachments=commit()) {}
        assertTrue(turn.sessionId>0);assertTrue(turn.userMessageId>0)
        val row=store.attachments.value.single()
        assertEquals(turn.sessionId,row.sessionId);assertEquals(turn.userMessageId,row.messageId)
        assertEquals(handle.localToken,row.localToken);assertEquals(handle.sha256,row.sha256)
        assertEquals(1,store.generations.value.size);assertEquals(1,store.messages.value.size)
    }
    @Test fun attachmentInsertFailureRollsBackMessageSessionAndGeneration()=runTest {
        store.failAttachmentInsert=true
        var failed=false
        try {repository.prepareGeneration(null,"text","MOCK",null,"g",attachments=commit()) {}} catch(_:Exception) {failed=true}
        assertTrue(failed);assertTrue(store.sessions.value.isEmpty());assertTrue(store.messages.value.isEmpty())
        assertTrue(store.generations.value.isEmpty());assertTrue(store.attachments.value.isEmpty())
    }
    @Test fun staleDraftAndMismatchedConnectionCannotCommitAnyReference()=runTest {
        for(input in listOf(commit(guard={false}),commit(scope.copy(profileRevision=1)))) {
            var failed=false
            try {repository.prepareGeneration(null,"text","MOCK",null,"g",attachments=input) {}} catch(_:Exception) {failed=true}
            assertTrue(failed);assertTrue(store.sessions.value.isEmpty());assertTrue(store.messages.value.isEmpty())
        }
    }
    @Test fun retryRetainsOriginalAttachmentAndRejectsReplacementDraft()=runTest {
        val first=repository.prepareGeneration(null,"text","MOCK",null,"first",attachments=commit()) {}
        repository.persistGeneration("first","","FAILED")
        val retried=repository.prepareGeneration(first.sessionId,"ignored","MOCK",null,"retry",first.userMessageId) {}
        assertEquals(first.userMessageId,retried.userMessageId);assertEquals(1,store.attachments.value.size)
        var failed=false
        try {repository.prepareGeneration(first.sessionId,"replacement","MOCK",null,"invalid",first.userMessageId,attachments=commit()) {}} catch(_:Exception) {failed=true}
        assertTrue(failed);assertEquals(1,store.attachments.value.size)
    }
}
