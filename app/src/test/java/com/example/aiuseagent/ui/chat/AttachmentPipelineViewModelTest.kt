package com.example.aiuseagent.ui.chat

import com.example.aiuseagent.data.*
import com.example.aiuseagent.data.attachment.*
import com.example.aiuseagent.domain.attachment.*
import androidx.lifecycle.viewModelScope
import java.io.ByteArrayInputStream
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentPipelineViewModelTest {
    @get:Rule val temp=TemporaryFolder(java.io.File("build/test-task-files").absoluteFile.apply {mkdirs()})
    private val dispatcher=StandardTestDispatcher()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){Dispatchers.resetMain()}
    @Test fun attachmentOnlySendUsesProductionCoordinatorAndRetryKeepsFrozenText() {
        // This integration uses real file IO: its save deadline must use the same real clock.
        val main=java.util.concurrent.Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        Dispatchers.setMain(main)
        try { runBlocking(main) {
        val store=FakeDaoStore()
        val sessions=SessionRepository(store.sessionDao,store.messageDao,store.generationPersistence)
        val requests=mutableListOf<com.example.aiuseagent.domain.provider.ChatRequest>()
        val provider=object:com.example.aiuseagent.domain.provider.ModelProvider {
            override val type=com.example.aiuseagent.domain.provider.ProviderType.MOCK
            override fun stream(request:com.example.aiuseagent.domain.provider.ChatRequest,config:com.example.aiuseagent.domain.provider.ProviderConfig,credential:com.example.aiuseagent.domain.provider.ProviderCredential)=kotlinx.coroutines.flow.flow {
                requests+=request
                emit(com.example.aiuseagent.domain.provider.ProviderEvent.Started(type,config.model))
                emit(com.example.aiuseagent.domain.provider.ProviderEvent.Failed(com.example.aiuseagent.domain.provider.ProviderError.of(com.example.aiuseagent.domain.provider.ProviderErrorKind.NETWORK,type)))
            }
        }
        val providers=com.example.aiuseagent.data.provider.ProviderRepository(com.example.aiuseagent.domain.provider.ProviderRegistry(mapOf(provider.type to provider)))
        val engineScope=CoroutineScope(SupervisorJob()+main)
        val engine=com.example.aiuseagent.data.generation.GenerationCoordinator(sessions,providers,engineScope,{_,_,_,_->error("No workspace tools")},{false})
        val vm=ChatViewModel(sessions,coordinator=engine,providers=providers,attachmentRepositoryFactory={current ->
            AttachmentRepository(temp.newFolder().toPath(),object:AttachmentSource {
                override suspend fun describe(uri:String)=AttachmentSourceInfo("selected.md","text/markdown")
                override suspend fun open(uri:String)=ByteArrayInputStream("frozen synthetic attachment".toByteArray())
            },current)
        })
        try {
            withTimeout(10000) {vm.uiState.first {it.isReady};engine.state.first {it.ready}}
            vm.acceptAttachmentResult(vm.prepareAttachmentPick(AttachmentPickerKind.FILE)!!,listOf("content://synthetic/file"))
            withContext(Dispatchers.Default) {withTimeout(10000) {vm.uiState.first {it.attachments.singleOrNull()?.state==AttachmentState.READY}}}
            vm.send();vm.send()
            withContext(Dispatchers.Default) {withTimeout(10000) {engine.state.first {!it.busy && it.userMessageId!=null}}}
            assertEquals("status=${engine.state.value.status}, error=${engine.state.value.error}",1,requests.size);assertEquals(1,store.attachments.value.size)
            assertEquals("",requests.single().messages.last().content)
            assertEquals("frozen synthetic attachment",(requests.single().messages.last().parts.single() as com.example.aiuseagent.domain.provider.ProviderContentPart.Text).text)
            assertNotNull(store.attachments.value.single().selectionJson)
            withTimeout(10000) {vm.uiState.first {state -> state.messages.any {it.attachments.isNotEmpty()}}}
            assertEquals("selected.md",vm.uiState.value.messages.first {it.role==MessageRole.USER}.attachments.single().name)
            vm.onInputChange("new unsent draft")
            vm.retryGeneration()
            withContext(Dispatchers.Default) {withTimeout(10000) {engine.state.first {!it.busy && store.generations.value.size==2}}}
            assertEquals(2,requests.size);assertEquals(requests[0].messages.last(),requests[1].messages.last())
            assertEquals(1,store.attachments.value.size)
            withTimeout(10000) {vm.uiState.first {state -> state.messages.any {it.attachments.isNotEmpty()}}}
            assertEquals("selected.md",vm.uiState.value.messages.first {it.role==MessageRole.USER}.attachments.single().name)
            vm.acceptAttachmentResult(vm.prepareAttachmentPick(AttachmentPickerKind.FILE)!!,listOf("content://synthetic/second"))
            withContext(Dispatchers.Default) {withTimeout(10000) {vm.uiState.first {it.attachments.singleOrNull()?.state==AttachmentState.READY}}}
            vm.send()
            withContext(Dispatchers.Default) {withTimeout(10000) {engine.state.first {!it.busy && store.generations.value.size==3}}}
            assertEquals(3,requests.size);assertEquals(2,store.attachments.value.size)
            assertEquals("new unsent draft",requests.last().messages.last().content)
        } finally {
            requireNotNull(vm.viewModelScope.coroutineContext[Job]).cancelAndJoin()
            requireNotNull(engineScope.coroutineContext[Job]).cancelAndJoin()
        }
        } } finally {Dispatchers.setMain(dispatcher);main.close()}
    }
    @Test fun realViewModelPickerResultStagesTextWithoutMessagesAndRejectsCallbackReplay()=runTest(dispatcher) {
        val store=FakeDaoStore()
        var opens=0
        val vm=ChatViewModel(SessionRepository(store.sessionDao,store.messageDao),attachmentRepositoryFactory={ current ->
            AttachmentRepository(temp.newFolder().toPath(),object:AttachmentSource {
                override suspend fun describe(uri:String)=AttachmentSourceInfo("synthetic.md","text/markdown")
                override suspend fun open(uri:String)=ByteArrayInputStream("# local synthetic".toByteArray()).also { opens++ }
            },current)
        })
        try {
        advanceUntilIdle()
        val ticket=vm.prepareAttachmentPick(AttachmentPickerKind.FILE)!!
        vm.acceptAttachmentResult(ticket,listOf("content://synthetic/file"))
        val terminal=withContext(Dispatchers.Default) { withTimeout(10000) { vm.uiState.first {
            it.attachments.singleOrNull()?.state in setOf(AttachmentState.READY,AttachmentState.FAILED)
        } } }
        assertEquals(terminal.attachments.single().error,AttachmentState.READY,terminal.attachments.single().state)
        assertTrue(vm.uiState.value.canSendMessage)
        vm.acceptAttachmentResult(ticket,listOf("content://synthetic/file"));advanceUntilIdle()
        assertEquals(1,opens);assertEquals(0,store.messageCount());assertEquals(0,store.sessionCount())
        vm.newSession();advanceUntilIdle()
        assertTrue(vm.uiState.value.attachments.isEmpty())
        } finally { requireNotNull(vm.viewModelScope.coroutineContext[Job]).cancelAndJoin() }
    }
    @Test fun modeRoundTripRejectsOldPlatformCallbackBeforeOpeningSource()=runTest(dispatcher) {
        val store=FakeDaoStore()
        var opens=0
        val vm=ChatViewModel(SessionRepository(store.sessionDao,store.messageDao),attachmentRepositoryFactory={ current ->
            AttachmentRepository(temp.newFolder().toPath(),object:AttachmentSource {
                override suspend fun describe(uri:String)=AttachmentSourceInfo("synthetic.txt","text/plain")
                override suspend fun open(uri:String)=ByteArrayInputStream(byteArrayOf(1)).also { opens++ }
            },current)
        })
        try {
        advanceUntilIdle()
        val ticket=vm.prepareAttachmentPick(AttachmentPickerKind.FILE)!!
        vm.setMode(ChatMode.BUILD);vm.setMode(ChatMode.CONVERSATION);advanceUntilIdle()
        vm.acceptAttachmentResult(ticket,listOf("content://synthetic/file"));advanceUntilIdle()
        assertEquals(0,opens);assertTrue(vm.uiState.value.attachments.isEmpty())
        } finally { requireNotNull(vm.viewModelScope.coroutineContext[Job]).cancelAndJoin() }
    }
}
