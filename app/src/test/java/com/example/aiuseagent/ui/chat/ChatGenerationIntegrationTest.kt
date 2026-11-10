package com.example.aiuseagent.ui.chat

import androidx.lifecycle.ViewModelStore
import com.example.aiuseagent.data.ChatLaunchPreferences
import com.example.aiuseagent.data.FakeDaoStore
import com.example.aiuseagent.data.MemoryChatPreferences
import com.example.aiuseagent.data.SessionRepository
import com.example.aiuseagent.data.generation.GenerationCoordinator
import com.example.aiuseagent.data.generation.GenerationLimits
import com.example.aiuseagent.data.local.SessionDao
import com.example.aiuseagent.data.local.SessionEntity
import com.example.aiuseagent.data.provider.ProviderRepository
import com.example.aiuseagent.domain.provider.ChatRequest
import com.example.aiuseagent.domain.provider.ModelProvider
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderCredential
import com.example.aiuseagent.domain.provider.ProviderEvent
import com.example.aiuseagent.domain.provider.ProviderRegistry
import com.example.aiuseagent.domain.provider.ProviderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real ChatViewModel -> coordinator -> repository route, with fake transport and transactional DAO store. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatGenerationIntegrationTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun before() = Dispatchers.setMain(dispatcher)
    @After fun after() = Dispatchers.resetMain()

    @Test fun blankFirstSendCreatesOnlyOneSessionAndNoDemoWithProductionDependencies() = runTest(dispatcher) {
        val h = Harness(dispatcher) { _, _ -> answer("first answer") }
        try {
            val vm = h.newViewModel()
            runCurrent()
            assertTrue(vm.uiState.value.isReady)
            assertEquals("default", vm.uiState.value.workspaceId)
            assertNull(vm.uiState.value.activeSessionId)
            assertTrue(h.store.sessions.value.isEmpty())
            vm.onInputChange("   "); vm.send(); runCurrent()
            assertTrue(h.provider.requests.isEmpty()); assertTrue(h.store.sessions.value.isEmpty())
            vm.onInputChange("first question")
            vm.send(); vm.send() // Synchronous reservation rejects the second tap.
            runCurrent()
            val id = vm.uiState.value.activeSessionId!!
            assertEquals(1,h.store.sessionCount())
            assertEquals(1,h.provider.requests.size)
            assertEquals(listOf("first question","first answer"),vm.uiState.value.messages.map { it.content })
            assertTrue(h.store.messages.value.all { it.sessionId == id && !it.isDemo })
            assertEquals("",vm.uiState.value.input)
            assertFalse(vm.uiState.value.isGenerating)
            assertTrue(h.provider.requests.single().tools.isEmpty())
        } finally { h.close() }
    }

    @Test fun firstSendKeepsNewSessionSelectedAcrossDelayedAndStaleDaoLists() = runTest(dispatcher) {
        val h = Harness(dispatcher) { _, _ -> answer("new answer") }
        try {
            val old = h.repository.createSession("existing")
            h.repository.appendMessage(old,"user","existing history")
            val delayed = MutableStateFlow(h.store.sessions.value)
            h.sessionRows = delayed
            val vm = h.newViewModel(ChatLaunchPreferences(startNewChat=false,lastSessionId=old))
            runCurrent(); assertEquals(old,vm.uiState.value.activeSessionId)
            vm.newSession(); runCurrent()
            assertNull(vm.uiState.value.activeSessionId); assertEquals(1,h.store.sessionCount())
            vm.onInputChange("new question"); vm.send(); runCurrent()
            val created = h.store.sessions.value.single { it.id != old }.id
            assertEquals(created,vm.uiState.value.activeSessionId)
            // A new emission still missing the committed ID must not restore the old session.
            delayed.value = listOf(h.store.sessions.value.first { it.id == old }.copy(updatedAt=9_999))
            runCurrent(); assertEquals(created,vm.uiState.value.activeSessionId)
            delayed.value = h.store.sessions.value
            runCurrent()
            assertEquals(created,vm.uiState.value.activeSessionId)
            assertEquals(listOf("new question","new answer"),vm.uiState.value.messages.map { it.content })
            assertEquals(listOf("existing history"),h.store.messages.value.filter { it.sessionId == old }.map { it.content })
            assertEquals(1,h.provider.requests.size)
        } finally { h.close() }
    }

    @Test fun switchingSessionStopsGenerationAndKeepsBothMessageStreamsIsolated() = runTest(dispatcher) {
        val h = Harness(dispatcher) { _, _ -> flow {
            emit(ProviderEvent.TextDelta("partial from A")); awaitCancellation()
        } }
        try {
            val first = h.repository.createSession("A")
            h.repository.appendMessage(first,"user","only A")
            val second = h.repository.createSession("B")
            h.repository.appendMessage(second,"user","only B")
            val vm = h.newViewModel(ChatLaunchPreferences(startNewChat=false,lastSessionId=first))
            runCurrent()
            vm.onInputChange("question for A"); vm.send(); runCurrent()
            assertTrue(vm.uiState.value.isGenerating)
            assertTrue(vm.uiState.value.messages.any { it.content == "question for A" })
            assertEquals("partial from A",vm.uiState.value.generationText)
            vm.selectSession(second)
            vm.onInputChange("later draft")
            runCurrent()
            assertEquals(second,vm.uiState.value.activeSessionId)
            assertEquals(listOf("only B"),vm.uiState.value.messages.map { it.content })
            assertEquals("",vm.uiState.value.input) // Input typed before selection completed stays with A.
            assertNull(vm.uiState.value.generationText)
            assertFalse(vm.uiState.value.isGenerating)
            assertEquals(0,h.provider.openCollectors)
            assertEquals("CANCELLED",h.store.generations.value.single().status)
            assertTrue(h.store.messages.value.filter { it.sessionId == second }.none { it.content.contains("A") })
            vm.selectSession(first); runCurrent()
            assertEquals("later draft",vm.uiState.value.input)
            assertEquals(listOf("only A","question for A","partial from A"),vm.uiState.value.messages.map { it.content })
            assertEquals("CANCELLED",vm.uiState.value.messages.last().status)
            assertEquals(1,h.provider.requests.size)
        } finally { h.close() }
    }

    @Test fun stoppingThenRetryingReusesOriginalUserAndExcludesFailedPartialContext() = runTest(dispatcher) {
        val h = Harness(dispatcher) { count, _ -> if(count == 1) flow {
            emit(ProviderEvent.TextDelta("held partial")); awaitCancellation()
        } else answer("retry complete") }
        try {
            val vm = h.newViewModel(); runCurrent()
            vm.onInputChange("retry question"); vm.send(); runCurrent()
            val id = vm.uiState.value.activeSessionId!!
            assertTrue(vm.uiState.value.messages.any { it.content == "retry question" })
            vm.stopGeneration(); runCurrent()
            assertFalse(vm.uiState.value.isGenerating)
            assertTrue(vm.uiState.value.canRetry)
            assertEquals("CANCELLED",h.store.generations.value.single().status)
            vm.retryGeneration(); runCurrent()
            assertEquals(id,vm.uiState.value.activeSessionId)
            assertEquals(1,h.store.messages.value.count { it.role == "user" })
            assertEquals(2,h.store.messages.value.count { it.role == "assistant" })
            assertEquals("retry complete",vm.uiState.value.messages.last().content)
            assertEquals("COMPLETED",h.store.generations.value.last().status)
            assertEquals(2,h.provider.requests.size)
            assertTrue(h.provider.requests.last().messages.none { it.content == "held partial" })
            assertFalse(vm.uiState.value.canRetry)
        } finally { h.close() }
    }

    @Test fun cancellingDeleteConfirmationDoesNotDeleteOrStopTheCurrentRequest() = runTest(dispatcher) {
        val h = Harness(dispatcher) { _, _ -> flow {
            emit(ProviderEvent.TextDelta("still streaming")); awaitCancellation()
        } }
        try {
            val vm = h.newViewModel(); runCurrent()
            vm.onInputChange("keep this"); vm.send(); runCurrent()
            val row = h.store.sessions.value.single()
            vm.requestDelete(row); vm.cancelDelete(); runCurrent()
            assertNull(vm.uiState.value.pendingDeleteSession)
            assertEquals(row.id,vm.uiState.value.activeSessionId)
            assertTrue(vm.uiState.value.isGenerating)
            assertEquals(1,h.provider.openCollectors)
            assertEquals(1,h.store.sessionCount())
            assertEquals("STREAMING",h.store.generations.value.single().status)
            vm.stopGeneration(); runCurrent()
            assertEquals("CANCELLED",h.store.generations.value.single().status)
        } finally { h.close() }
    }

    @Test fun startingNewBlankWhileStreamingStopsOldRequestWithoutCreatingAnotherSession() = runTest(dispatcher) {
        val h = Harness(dispatcher) { _, _ -> flow {
            emit(ProviderEvent.TextDelta("old partial")); awaitCancellation()
        } }
        try {
            val vm = h.newViewModel(); runCurrent()
            vm.onInputChange("old question"); vm.send(); runCurrent()
            vm.newSession(); runCurrent()
            assertNull(vm.uiState.value.activeSessionId)
            assertTrue(vm.uiState.value.messages.isEmpty())
            assertNull(vm.uiState.value.generationText)
            assertFalse(vm.uiState.value.isGenerating)
            assertEquals(1,h.store.sessionCount())
            assertEquals("CANCELLED",h.store.generations.value.single().status)
            assertEquals(0,h.provider.openCollectors)
            assertEquals(1,h.provider.requests.size)
        } finally { h.close() }
    }

    private class Harness(dispatcher: TestDispatcher, script: (Int,ChatRequest) -> Flow<ProviderEvent>) {
        val store = FakeDaoStore()
        var sessionRows: Flow<List<SessionEntity>>? = null
        private var clock = 100L
        private val dao = object : SessionDao by store.sessionDao {
            override fun observeSessions(): Flow<List<SessionEntity>> = sessionRows ?: store.sessionDao.observeSessions()
        }
        val repository = SessionRepository(dao,store.messageDao,store.generationPersistence) { clock++ }
        val provider = FakeProvider(script)
        val providers = ProviderRepository(ProviderRegistry(mapOf(ProviderType.DEEPSEEK to provider))).also {
            it.updateConfig(ProviderConfig(ProviderType.DEEPSEEK,"https://example.invalid","test-model"))
            it.putCredential(ProviderType.DEEPSEEK,"fake-ui-integration-credential")
            it.selectActive(ProviderType.DEEPSEEK)
        }
        private val scope = CoroutineScope(SupervisorJob()+dispatcher)
        private val views = ViewModelStore()
        val coordinator = GenerationCoordinator(repository,providers,scope,
            toolExecutor = { _,_,_,_ -> error("Conversation requests must not execute workspace tools") },
            workspaceAvailable = { it == "default" },
            limits = GenerationLimits.acceptance().copy(joinMillis=500,saveMillis=500))
        fun newViewModel(launch: ChatLaunchPreferences = ChatLaunchPreferences()): ChatViewModel =
            ChatViewModel(repository,MemoryChatPreferences(launch),coordinator,workspaces=null,providers=providers)
                .also { views.put("chat",it) }
        fun close() { views.clear(); scope.cancel() }
    }

    private class FakeProvider(private val script: (Int,ChatRequest) -> Flow<ProviderEvent>) : ModelProvider {
        override val type = ProviderType.DEEPSEEK
        val requests = mutableListOf<ChatRequest>()
        var openCollectors = 0
        override fun stream(request: ChatRequest, config: ProviderConfig, credential: ProviderCredential): Flow<ProviderEvent> = flow {
            requests += request; openCollectors++
            try { emit(ProviderEvent.Started(type,config.model)); emitAll(script(requests.size,request)) }
            finally { openCollectors-- }
        }
    }

    companion object {
        private fun answer(text: String): Flow<ProviderEvent> = flow {
            emit(ProviderEvent.TextDelta(text)); emit(ProviderEvent.Completed())
        }
    }
}
