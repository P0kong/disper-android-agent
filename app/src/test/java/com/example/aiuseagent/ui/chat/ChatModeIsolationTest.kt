package com.example.aiuseagent.ui.chat

import com.example.aiuseagent.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ChatModeIsolationTest {
    private val dispatcher=StandardTestDispatcher()
    private val store=FakeDaoStore()
    private val repository=SessionRepository(store.sessionDao,store.messageDao)
    @Before fun setup()=Dispatchers.setMain(dispatcher)
    @After fun teardown()=Dispatchers.resetMain()

    @Test fun modeSwitchRestoresOwnSelectionAndDraftWithoutMovingMessages()=runTest(dispatcher) {
        val vm=ChatViewModel(repository);advanceUntilIdle()
        vm.onInputChange("chat message");vm.send();advanceUntilIdle()
        val chat=vm.uiState.value.activeSessionId!!
        vm.onInputChange("chat draft")
        vm.setMode(ChatMode.BUILD);advanceUntilIdle()
        assertNull(vm.uiState.value.activeSessionId);assertEquals("",vm.uiState.value.input)
        assertTrue(vm.uiState.value.messages.isEmpty())
        vm.onInputChange("build message");vm.send();advanceUntilIdle()
        val build=vm.uiState.value.activeSessionId!!
        assertNotEquals(chat,build)
        assertEquals("BUILD",store.sessions.value.first { it.id==build }.mode)
        vm.onInputChange("build draft")
        vm.setMode(ChatMode.CONVERSATION);advanceUntilIdle()
        assertEquals(chat,vm.uiState.value.activeSessionId);assertEquals("chat draft",vm.uiState.value.input)
        assertEquals(listOf("chat message"),vm.uiState.value.messages.map { it.content })
        vm.setMode(ChatMode.BUILD);advanceUntilIdle()
        assertEquals(build,vm.uiState.value.activeSessionId);assertEquals("build draft",vm.uiState.value.input)
    }

    @Test fun selectingAnotherSessionKeepsEachDraftSeparate()=runTest(dispatcher) {
        val first=repository.createSession("first");val second=repository.createSession("second")
        val vm=ChatViewModel(repository);advanceUntilIdle()
        vm.selectSession(first);vm.onInputChange("only first")
        vm.selectSession(second);assertEquals("",vm.uiState.value.input)
        vm.onInputChange("only second");vm.selectSession(first)
        assertEquals("only first",vm.uiState.value.input)
    }
}
