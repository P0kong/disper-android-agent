package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.data.*
import com.example.aiuseagent.data.generation.*
import com.example.aiuseagent.data.provider.ProviderRepository
import com.example.aiuseagent.domain.attachment.*
import com.example.aiuseagent.domain.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Explicit barriers reproduce ordering without wall-clock sleeps or repeated lucky runs. */
@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentDispatchOrderingTest {
    private class Fixture(test: TestScope) {
        val store=FakeDaoStore()
        val events=mutableListOf<String>()
        val committed=CompletableDeferred<Unit>()
        val release=CompletableDeferred<Unit>()
        val requests=mutableListOf<ChatRequest>()
        val scope=CoroutineScope(SupervisorJob()+StandardTestDispatcher(test.testScheduler))
        val provider=object:ModelProvider {
            override val type=ProviderType.MOCK
            override fun stream(request:ChatRequest,config:ProviderConfig,credential:ProviderCredential)=flow {
                events+="request";requests+=request
                emit(ProviderEvent.Started(type,config.model))
                emit(ProviderEvent.Failed(ProviderError.of(ProviderErrorKind.NETWORK,type)))
            }
        }
        val engine=GenerationCoordinator(SessionRepository(store.sessionDao,store.messageDao,store.generationPersistence),
            ProviderRepository(ProviderRegistry(mapOf(ProviderType.MOCK to provider))),scope,{_,_,_,_->error("No tools")},{false})
        private val attachmentScope=AttachmentScope("draft",null,"CHAT",0,null,0,"builtin:MOCK",0,0)
        private val handle=AttachmentHandle("c10dd430-4fb6-4cb1-a098-6054e6ddf435","94692707-3c0b-4d15-9070-d3f345ee68b1","a".repeat(64),"text/plain",3)
        val input=PreparedAttachmentInput(listOf(ProviderContentPart.Text("selected synthetic text")),ProviderAttachmentPayloads(),"")
        private val commit=AttachmentCommit(attachmentScope,listOf(AttachmentCommitRecord(handle,"synthetic.txt")),{true}) { session ->
            if(session!=null) {events+="db-committed";committed.complete(Unit);release.await();events+="settled"}
        }
        fun submit():Boolean = engine.submit(GenerationSubmission(null,"",attachments=AttachmentGenerationInput(attachmentScope,input,commit,{true}) {emptyMap()}))
            .also {events+=if(it) "accepted" else "rejected"}
    }
    @Test fun doubleClickCommitsOnce()=runTest {
        val f=Fixture(this)
        try {
            runCurrent();assertTrue(f.submit());assertFalse(f.submit());runCurrent()
            assertTrue(f.committed.isCompleted);assertEquals(0,f.requests.size)
            f.release.complete(Unit);runCurrent()
            assertEquals(1,f.requests.size);assertEquals(1,f.store.attachments.value.size)
            assertEquals(1,f.store.messages.value.count {it.role=="user"})
            assertEquals(listOf("accepted","rejected","db-committed","settled","request"),f.events)
        } finally {f.scope.cancel()}
    }
    @Test fun cancelBeforeDispatchMakesZeroRequests()=runTest {
        val f=Fixture(this)
        try {
            runCurrent();assertTrue(f.submit());runCurrent();assertTrue(f.committed.isCompleted)
            f.engine.stop("synthetic cancel");f.release.complete(Unit);runCurrent()
            assertEquals(0,f.requests.size);assertEquals(1,f.store.attachments.value.size)
            assertFalse(f.engine.state.value.busy);assertEquals("CANCELLED",f.store.generations.value.single().status)
        } finally {f.scope.cancel()}
    }
    @Test fun virtualDeadlineWhileCommitSettlementWaitsProducesZeroRequests()=runTest {
        val f=Fixture(this)
        try {
            runCurrent();assertTrue(f.submit());runCurrent();assertTrue(f.committed.isCompleted)
            // runTest can take this jump while a real IO dispatcher is still finishing a copy.
            advanceTimeBy(5001);runCurrent();f.release.complete(Unit);runCurrent()
            assertEquals(5001,testScheduler.currentTime)
            assertEquals(0,f.requests.size);assertEquals(1,f.store.attachments.value.size)
            assertNotNull(f.engine.state.value.userMessageId)
            assertEquals("CANCELLED",f.store.generations.value.single().status)
        } finally {f.scope.cancel()}
    }
    @Test fun dispatchBeforeCancellationKeepsOriginalFrozenInput()=runTest {
        val f=Fixture(this)
        try {
            runCurrent();assertTrue(f.submit());runCurrent();f.release.complete(Unit);runCurrent()
            f.engine.stop("synthetic later cancel");runCurrent()
            assertEquals(1,f.requests.size)
            assertEquals(f.input.userMessage(),f.requests.single().messages.last())
            assertEquals(1,f.store.attachments.value.size)
        } finally {f.scope.cancel()}
    }
}
