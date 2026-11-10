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

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentSendLeaseTest {
    private class Fixture(test:TestScope,val cancellationBarrier:Boolean=false) {
        val registry=AttachmentLeaseRegistry()
        val token="94692707-3c0b-4d15-9070-d3f345ee68b1"
        val networkStarted=CompletableDeferred<Unit>();val networkExit=CompletableDeferred<Unit>()
        val store=FakeDaoStore();var settled=false
        val scope=CoroutineScope(SupervisorJob()+StandardTestDispatcher(test.testScheduler))
        private val provider=object:ModelProvider {
            override val type=ProviderType.MOCK
            override fun stream(request:ChatRequest,config:ProviderConfig,credential:ProviderCredential)=flow {
                networkStarted.complete(Unit)
                if(cancellationBarrier) {
                    try {awaitCancellation()} finally {withContext(NonCancellable) {networkExit.await()}}
                } else {
                    networkExit.await();emit(ProviderEvent.Started(type,config.model));emit(ProviderEvent.Completed())
                }
            }
        }
        val engine=GenerationCoordinator(SessionRepository(store.sessionDao,store.messageDao,store.generationPersistence),
            ProviderRepository(ProviderRegistry(mapOf(ProviderType.MOCK to provider))),scope,{_,_,_,_->error("No tools")},{false})
        private val bound=AttachmentScope("draft",null,"CHAT",0,null,0,"builtin:MOCK",0,0)
        private val handle=AttachmentHandle("c10dd430-4fb6-4cb1-a098-6054e6ddf435",token,"a".repeat(64),"text/plain",3)
        private val commit=AttachmentCommit(bound,listOf(AttachmentCommitRecord(handle,"synthetic.txt")),{true}) {settled=true}
            .also {it.holdSending(registry.acquire(listOf(token)))}
        private val prepared=PreparedAttachmentInput(listOf(ProviderContentPart.Text("synthetic")),ProviderAttachmentPayloads(),"")
        fun submit()=engine.submit(GenerationSubmission(null,"",attachments=AttachmentGenerationInput(bound,prepared,commit,{true}) {emptyMap()}))
        fun assertReleased() {assertNotNull(registry.beginDelete(token)?.also {it.close()})}
    }
    @Test fun databaseSettlementDoesNotReleaseSendingLeaseButRequestCompletionDoes()=runTest {
        val f=Fixture(this)
        try {
            runCurrent();assertTrue(f.submit());runCurrent()
            assertTrue(f.settled);assertTrue(f.networkStarted.isCompleted);assertNull(f.registry.beginDelete(f.token))
            f.networkExit.complete(Unit);runCurrent();assertFalse(f.engine.state.value.busy);f.assertReleased()
        } finally {f.networkExit.complete(Unit);f.scope.cancel()}
    }
    @Test fun cancellationKeepsLeaseUntilTransportActuallyExits()=runTest {
        val f=Fixture(this,true)
        try {
            runCurrent();assertTrue(f.submit());runCurrent();assertTrue(f.networkStarted.isCompleted)
            f.engine.stop("synthetic stop");runCurrent();assertNull(f.registry.beginDelete(f.token))
            f.networkExit.complete(Unit);runCurrent();f.assertReleased()
        } finally {f.networkExit.complete(Unit);f.scope.cancel()}
    }
    @Test fun cancelledBeforeLazyGenerationStartsAlsoReleasesSendingLease()=runTest {
        val f=Fixture(this)
        try {
            runCurrent();assertTrue(f.submit());f.engine.stop("synthetic early stop");runCurrent()
            assertFalse(f.networkStarted.isCompleted);assertTrue(f.store.attachments.value.isEmpty());f.assertReleased()
        } finally {f.networkExit.complete(Unit);f.scope.cancel()}
    }
}
