package com.example.aiuseagent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.aiuseagent.data.*
import com.example.aiuseagent.data.local.SessionDao
import com.example.aiuseagent.data.local.SessionEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatStartupTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeDaoStore()
    private var time = 1L
    private val repository = SessionRepository(store.sessionDao, store.messageDao) { time++ }
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }
    private suspend fun history(title: String): Long = repository.createSession(title).also {
        repository.appendMessage(it, "user", "only-$title")
    }

    @Test fun defaultEnabled_twoColdStartsPreserveHistoryWithoutEmptyRows() = runTest(dispatcher) {
        history("A")
        repeat(2) {
            val vm = ChatViewModel(repository); advanceUntilIdle()
            assertTrue(vm.uiState.value.isReady); assertNull(vm.uiState.value.activeSessionId)
            assertTrue(vm.uiState.value.messages.isEmpty()); assertEquals(1, store.sessionCount())
            assertEquals(listOf("only-A"), store.messages.value.map { it.content })
        }
    }
    @Test fun disabled_restoresLastSelectedRatherThanMostRecent() = runTest(dispatcher) {
        val a = history("A"); history("B")
        val prefs = MemoryChatPreferences(ChatLaunchPreferences(false, a))
        val vm = ChatViewModel(repository, prefs); advanceUntilIdle()
        assertEquals(a, vm.uiState.value.activeSessionId); assertEquals(listOf("only-A"), vm.uiState.value.messages.map { it.content })
    }
    @Test fun disabled_deletedSelectionFallsBackToMostRecent() = runTest(dispatcher) {
        val a = history("A"); val b = history("B"); repository.deleteSession(a)
        val prefs = MemoryChatPreferences(ChatLaunchPreferences(false, a))
        val vm = ChatViewModel(repository, prefs); advanceUntilIdle()
        assertEquals(b, vm.uiState.value.activeSessionId); assertEquals(b, prefs.chatLaunch.value?.lastSessionId)
    }
    @Test fun disabled_withoutHistoryRemainsBlankUntilSend() = runTest(dispatcher) {
        val vm = ChatViewModel(repository, MemoryChatPreferences(ChatLaunchPreferences(false, 999)))
        advanceUntilIdle(); assertNull(vm.uiState.value.activeSessionId); assertEquals(0, store.sessionCount())
        vm.onInputChange("first"); vm.send(); advanceUntilIdle()
        assertEquals(1, store.sessionCount()); assertEquals(listOf("first"), vm.uiState.value.messages.map { it.content })
    }
    @Test fun startupWaitsForPreferencesAndFirstDatabaseEmission() = runTest(dispatcher) {
        val a = history("A")
        val ready = CompletableDeferred<Unit>()
        val dao = object : SessionDao by store.sessionDao {
            override fun observeSessions() = flow { ready.await(); emit(store.sessions.value) }
        }
        val prefs = object : ChatPreferences {
            override val chatLaunch = MutableStateFlow<ChatLaunchPreferences?>(null)
            override fun rememberSession(id: Long?) {}
        }
        val vm = ChatViewModel(SessionRepository(dao, store.messageDao), prefs)
        advanceUntilIdle(); assertFalse(vm.uiState.value.isReady)
        vm.onInputChange("premature"); vm.send(); assertEquals(1, store.messageCount())
        prefs.chatLaunch.value = ChatLaunchPreferences(false, a); advanceUntilIdle(); assertFalse(vm.uiState.value.isReady)
        ready.complete(Unit); advanceUntilIdle(); assertEquals(a, vm.uiState.value.activeSessionId); assertTrue(vm.uiState.value.isReady)
    }
    @Test fun preferenceAndDatabaseUpdatesDoNotRepeatStartupChoice() = runTest(dispatcher) {
        val a = history("A"); val prefs = MemoryChatPreferences()
        val vm = ChatViewModel(repository, prefs); advanceUntilIdle(); vm.selectSession(a)
        vm.onInputChange("keep draft"); vm.setMode(ChatMode.BUILD)
        prefs.chatLaunch.value = ChatLaunchPreferences(true, a); history("B"); advanceUntilIdle()
        assertNull(vm.uiState.value.activeSessionId); assertEquals("", vm.uiState.value.input); assertEquals(ChatMode.BUILD, vm.uiState.value.mode)
        vm.setMode(ChatMode.CONVERSATION);assertEquals(a,vm.uiState.value.activeSessionId);assertEquals("keep draft",vm.uiState.value.input)
    }
    @Test fun sameProcessStoreReusesSelectionDraftAndMode_butNewProcessStartsConversation() = runTest(dispatcher) {
        val a = history("A"); val stateStore = ViewModelStore()
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = ChatViewModel(repository) as T
        }
        val first = ViewModelProvider(stateStore, factory)[ChatViewModel::class.java]; advanceUntilIdle()
        first.selectSession(a); first.onInputChange("draft"); first.setMode(ChatMode.BUILD)
        first.onInputChange("build draft")
        val reopened = ViewModelProvider(stateStore, factory)[ChatViewModel::class.java]
        assertSame(first, reopened); assertNull(reopened.uiState.value.activeSessionId); assertEquals("build draft", reopened.uiState.value.input)
        assertEquals(ChatMode.BUILD, reopened.uiState.value.mode)
        val cold = ChatViewModel(repository); advanceUntilIdle()
        assertEquals(ChatMode.CONVERSATION, cold.uiState.value.mode); assertEquals("", cold.uiState.value.input); assertNull(cold.uiState.value.activeSessionId)
        stateStore.clear()
    }
    @Test fun rapidSendsDuringFirstInsert_shareOneSessionAndDoNotDuplicateSameInput() = runTest(dispatcher) {
        val a = history("A"); val gate = CompletableDeferred<Unit>()
        val dao = object : SessionDao by store.sessionDao {
            override suspend fun insert(session: SessionEntity): Long { gate.await(); return store.sessionDao.insert(session) }
        }
        val vm = ChatViewModel(SessionRepository(dao, store.messageDao)); advanceUntilIdle()
        vm.onInputChange("first"); vm.send(); vm.send(); vm.send(); runCurrent()
        vm.onInputChange("second"); vm.send(); runCurrent(); assertEquals(1, store.sessionCount())
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(2, store.sessionCount()); val target = vm.uiState.value.activeSessionId!!
        assertNotEquals(a, target); assertEquals(listOf("first", "second"), store.messages.value.filter { it.sessionId == target }.map { it.content })
        assertEquals(listOf("only-A"), store.messages.value.filter { it.sessionId == a }.map { it.content })
    }
    @Test fun blankSelectionSurvivesHistoryUpdatesAndExplicitNewClearsOnlyDraft() = runTest(dispatcher) {
        val a = history("A"); val vm = ChatViewModel(repository); advanceUntilIdle()
        vm.onInputChange("clear me"); vm.newSession(); history("B"); advanceUntilIdle()
        assertNull(vm.uiState.value.activeSessionId); assertEquals("", vm.uiState.value.input); assertTrue(vm.uiState.value.messages.isEmpty())
        assertEquals(2, store.sessionCount()); assertEquals(1, repository.messageCount(a))
    }
    @Test fun deletingActiveSessionFallsBack_andDoesNotReapplyEnabledStartup() = runTest(dispatcher) {
        val a = history("A"); val b = history("B"); val vm = ChatViewModel(repository); advanceUntilIdle()
        vm.selectSession(a); vm.requestDelete(store.sessions.value.first { it.id == a }); vm.confirmDelete(); advanceUntilIdle()
        assertEquals(b, vm.uiState.value.activeSessionId); assertEquals(listOf("only-B"), vm.uiState.value.messages.map { it.content })
    }
    @Test fun existingDemoHistoryRetainsItsMark_whileNewConversationHasNone() = runTest(dispatcher) {
        val a = repository.createSession("legacy"); repository.seedDemoMessageIfEmpty(a)
        val vm = ChatViewModel(repository); advanceUntilIdle(); vm.selectSession(a); advanceUntilIdle()
        assertTrue(vm.uiState.value.messages.single().isDemo)
        vm.newSession(); vm.onInputChange("new user"); vm.send(); advanceUntilIdle()
        assertFalse(vm.uiState.value.messages.single().isDemo); assertTrue(store.messages.value.first { it.sessionId == a }.isDemo)
    }
}
