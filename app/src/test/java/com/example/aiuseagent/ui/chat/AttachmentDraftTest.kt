package com.example.aiuseagent.ui.chat

import com.example.aiuseagent.data.*
import com.example.aiuseagent.domain.attachment.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentDraftTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){Dispatchers.resetMain()}
    private val scope=AttachmentScope("draft",null,"CHAT",0,null,0,"profile",0,0)
    private val handle=AttachmentHandle("id","token","a".repeat(64),"text/plain",3)
    private val ready=DraftAttachment("id",scope,"a.txt",AttachmentState.READY,handle)
    @Test fun readyAttachmentAllowsEmptyTextButAnyBlockingCardPreventsSilentPartialSend() {
        val state=ChatUiState(isReady=true,attachments=listOf(ready))
        assertTrue(state.canSendMessage)
        assertFalse(state.copy(attachments=emptyList()).canSendMessage)
        assertTrue(state.copy(attachments=emptyList(),input="text").canSendMessage)
        for(status in listOf(AttachmentState.READING,AttachmentState.PROCESSING,AttachmentState.FAILED,AttachmentState.NEEDS_CONFIRMATION)) {
            assertFalse(state.copy(input="text",attachments=listOf(ready,ready.copy(id="other",state=status))).canSendMessage)
        }
        assertFalse(state.copy(preparingAttachments=true).canSendMessage)
        assertFalse(state.copy(isGenerating=true).canSendMessage)
    }
    @Test fun partialAttachmentNeedsConfirmationBoundToSameSource() {
        val partial=ready.copy(state=AttachmentState.NEEDS_CONFIRMATION)
        val state=ChatUiState(isReady=true,attachments=listOf(partial),attachmentSelections=mapOf("id" to
            AttachmentSelection("id",handle.sha256,"text:1",listOf("part:1"),true)))
        assertTrue(state.canSendMessage)
        assertFalse(state.copy(attachmentSelections=mapOf("id" to state.attachmentSelections.getValue("id").copy(sourceSha256="b".repeat(64)))).canSendMessage)
    }
    @Test fun realViewModelBlankDraftReplacementAndModeRoundTripRejectOldPickerTickets()=runTest(dispatcher) {
        val store=FakeDaoStore();val vm=ChatViewModel(SessionRepository(store.sessionDao,store.messageDao))
        advanceUntilIdle()
        val first=vm.beginAttachmentPick(AttachmentPickerKind.FILE)
        assertTrue(vm.acceptsAttachmentTicket(first));assertNull(first.scope.sessionId)
        vm.newSession();advanceUntilIdle()
        assertNotEquals(first.scope.draftId,vm.attachmentScope().draftId)
        assertFalse(vm.acceptsAttachmentTicket(first))
        val second=vm.beginAttachmentPick(AttachmentPickerKind.PHOTO)
        vm.setMode(ChatMode.BUILD);advanceUntilIdle()
        assertEquals("BUILD",vm.attachmentScope().mode)
        vm.setMode(ChatMode.CONVERSATION);advanceUntilIdle()
        assertFalse(vm.acceptsAttachmentTicket(second))
        assertEquals(0,store.sessionCount());assertEquals(0,store.messageCount())
    }
}
