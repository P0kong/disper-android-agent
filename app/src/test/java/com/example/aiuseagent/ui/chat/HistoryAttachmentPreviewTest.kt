package com.example.aiuseagent.ui.chat

import com.example.aiuseagent.data.*
import com.example.aiuseagent.data.attachment.*
import com.example.aiuseagent.data.local.*
import com.example.aiuseagent.domain.attachment.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryAttachmentPreviewTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){Dispatchers.resetMain()}
    private class Fixture {
        val store=FakeDaoStore();val sessions=SessionRepository(store.sessionDao,store.messageDao,store.generationPersistence)
        val attachmentId=UUID.randomUUID().toString();var sessionId=0L;var messageId=0L;var closes=0;var opens=0
        val handle=AttachmentHandle(attachmentId,UUID.randomUUID().toString(),"a".repeat(64),"text/plain",3)
        val snapshot=HistoryAttachmentSnapshot(FrozenAttachmentInput(handle,"historic-parser",listOf("selected"),"b".repeat(64),true,"old-budget"),listOf(HistorySnapshotPart("selected","confirmed range",text="only selected text")))
        suspend fun seed() {
            sessionId=store.sessionDao.insert(SessionEntity(title="synthetic history",createdAt=1,updatedAt=1,mode="CHAT"))
            messageId=store.messageDao.insert(MessageEntity(sessionId=sessionId,role="user",content="",createdAt=1,mode="CHAT"))
            requireNotNull(store.generationPersistence.attachments).insert(AttachmentEntity(attachmentId,sessionId,messageId,0,"historic",1,"synthetic.txt","text/plain",3,handle.localToken,handle.sha256,"PREPARED"))
        }
        fun readable()=HistoryPreviewResult.Readable(snapshot,emptyMap(),AttachmentReadLease {closes++})
        fun vm(open:suspend ()->HistoryPreviewResult={readable()})=ChatViewModel(sessions,historyReader={session,message,id ->
            assertEquals(sessionId,session);assertEquals(messageId,message);assertEquals(attachmentId,id);opens++;open()
        })
    }
    @Test fun opensOnlyAnAttachmentOnTheCurrentMessageAndDoesNotCreateASendSelection()=runTest(dispatcher) {
        val f=Fixture();f.seed();val vm=f.vm();advanceUntilIdle();vm.selectSession(f.sessionId);advanceUntilIdle()
        vm.previewHistoryAttachment(f.messageId+1,f.attachmentId);vm.previewHistoryAttachment(f.messageId,UUID.randomUUID().toString());advanceUntilIdle()
        assertEquals(0,f.opens)
        vm.previewHistoryAttachment(f.messageId,f.attachmentId);advanceUntilIdle()
        assertEquals(1,f.opens);assertEquals("only selected text",vm.uiState.value.historyAttachmentPreview!!.snapshot.parts.single().text)
        assertTrue(vm.uiState.value.attachments.isEmpty());assertTrue(vm.uiState.value.attachmentSelections.isEmpty())
        vm.dismissHistoryAttachmentPreview();vm.dismissHistoryAttachmentPreview();assertEquals(1,f.closes)
    }
    @Test fun switchingDraftClosesOpenPreviewAndLateUncooperativeResultIsDiscarded()=runTest(dispatcher) {
        val f=Fixture();f.seed();val barrier=CompletableDeferred<Unit>()
        val vm=f.vm {withContext(NonCancellable) {barrier.await();f.readable()}}
        advanceUntilIdle();vm.selectSession(f.sessionId);advanceUntilIdle()
        vm.previewHistoryAttachment(f.messageId,f.attachmentId);runCurrent();assertEquals(1,f.opens)
        vm.newSession();runCurrent();barrier.complete(Unit);advanceUntilIdle()
        assertNull(vm.uiState.value.historyAttachmentPreview);assertEquals(1,f.closes)
    }
    @Test fun newSessionClosesAlreadyVisiblePreviewLease()=runTest(dispatcher) {
        val f=Fixture();f.seed();val vm=f.vm();advanceUntilIdle();vm.selectSession(f.sessionId);advanceUntilIdle()
        vm.previewHistoryAttachment(f.messageId,f.attachmentId);advanceUntilIdle()
        assertNotNull(vm.uiState.value.historyAttachmentPreview)
        vm.newSession();advanceUntilIdle()
        assertNull(vm.uiState.value.historyAttachmentPreview);assertEquals(1,f.closes)
    }
    @Test fun corruptCopyProducesUnavailableNoticeWithoutInventingPreviewContent()=runTest(dispatcher) {
        val f=Fixture();f.seed();val vm=f.vm {HistoryPreviewResult.Corrupt};advanceUntilIdle();vm.selectSession(f.sessionId);advanceUntilIdle()
        vm.previewHistoryAttachment(f.messageId,f.attachmentId);advanceUntilIdle()
        assertNull(vm.uiState.value.historyAttachmentPreview);assertTrue(vm.uiState.value.notice.orEmpty().contains("不可用"));assertEquals(1,f.opens)
    }
}
