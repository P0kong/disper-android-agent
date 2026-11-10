package com.example.aiuseagent.ui.chat

import com.example.aiuseagent.data.FakeDaoStore
import com.example.aiuseagent.data.SessionRepository
import com.example.aiuseagent.data.MemoryChatPreferences
import com.example.aiuseagent.data.ChatLaunchPreferences
import com.example.aiuseagent.data.local.SessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** 原有十项数据库/UI行为覆盖；新需求将空白新聊天改为首次有效发送才落库。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeDaoStore()
    private var clock = 1_000L
    private lateinit var repository: SessionRepository
    @Before fun setUp() { Dispatchers.setMain(dispatcher); repository = SessionRepository(store.sessionDao, store.messageDao) { clock } }
    @After fun tearDown() { Dispatchers.resetMain() }
    private fun activeSession(state: ChatUiState): SessionEntity = store.sessions.value.first { it.id == state.activeSessionId }
    private fun TestScope.stored(viewModel: ChatViewModel, title: String) {
        viewModel.newSession(title); viewModel.onInputChange("$title 的本地消息"); viewModel.send(); advanceUntilIdle()
    }

    @Test fun emptyDatabase_stateIsReadyWithoutAnySession() = runTest(dispatcher) {
        val vm = ChatViewModel(repository); advanceUntilIdle()
        assertTrue(vm.uiState.value.isReady); assertTrue(vm.uiState.value.sessions.isEmpty())
        assertNull(vm.uiState.value.activeSessionId); assertTrue(vm.uiState.value.messages.isEmpty()); assertEquals(0, store.sessionCount())
    }
    @Test fun newSession_staysBlankUntilFirstSendWithoutDemoMessage() = runTest(dispatcher) {
        val vm = ChatViewModel(repository); advanceUntilIdle(); vm.onInputChange("未发送草稿"); vm.newSession(); advanceUntilIdle()
        assertEquals(0, store.sessionCount()); assertNull(vm.uiState.value.activeSessionId)
        assertTrue(vm.uiState.value.messages.isEmpty()); assertEquals("", vm.uiState.value.input)
        vm.onInputChange("第一条"); vm.send(); advanceUntilIdle()
        assertEquals(1, store.sessionCount()); assertEquals(SessionRepository.DEFAULT_TITLE, activeSession(vm.uiState.value).title)
        assertEquals("第一条", vm.uiState.value.messages.single().content); assertFalse(vm.uiState.value.messages.single().isDemo)
    }
    @Test fun send_writesUserMessageAndNeverFabricatesReply() = runTest(dispatcher) {
        val vm = ChatViewModel(repository); advanceUntilIdle(); vm.newSession()
        vm.onInputChange("  你好，帮我看一下构建日志  "); vm.send(); advanceUntilIdle()
        val state = vm.uiState.value
        assertEquals(1, repository.messageCount(state.activeSessionId!!))
        assertEquals(SessionRepository.ROLE_USER, store.messages.value.single().role)
        assertEquals("你好，帮我看一下构建日志", state.messages.single().content)
        assertFalse(state.messages.single().isDemo); assertEquals("", state.input)
        assertEquals(ChatViewModel.MODEL_NOT_CONNECTED_NOTICE, state.notice)
    }
    @Test fun send_ignoresBlankInput() = runTest(dispatcher) {
        val vm = ChatViewModel(repository); advanceUntilIdle(); vm.onInputChange("   "); vm.send(); advanceUntilIdle()
        assertEquals(0, store.sessionCount()); assertEquals(0, store.messageCount()); assertNull(vm.uiState.value.notice)
    }
    @Test fun selectSession_switchesMessagesFromDatabase() = runTest(dispatcher) {
        val vm = ChatViewModel(repository); advanceUntilIdle(); stored(vm, "第一个")
        val first = vm.uiState.value.activeSessionId!!; clock += 1_000; stored(vm, "第二个")
        val second = vm.uiState.value.activeSessionId!!
        vm.selectSession(first); advanceUntilIdle()
        assertEquals(first, vm.uiState.value.activeSessionId); assertEquals(listOf("第一个 的本地消息"), vm.uiState.value.messages.map { it.content })
        vm.selectSession(second); advanceUntilIdle()
        assertEquals(second, vm.uiState.value.activeSessionId); assertEquals(listOf("第二个 的本地消息"), vm.uiState.value.messages.map { it.content })
    }
    @Test fun delete_requiresExplicitConfirmation() = runTest(dispatcher) {
        val vm = ChatViewModel(repository); advanceUntilIdle(); stored(vm, "待删除")
        val session = activeSession(vm.uiState.value)
        vm.requestDelete(session); assertNotNull(vm.uiState.value.pendingDeleteSession); assertEquals(1, store.sessionCount())
        vm.cancelDelete(); assertNull(vm.uiState.value.pendingDeleteSession); assertEquals(1, store.sessionCount())
        vm.requestDelete(session); vm.confirmDelete(); advanceUntilIdle()
        assertNull(vm.uiState.value.pendingDeleteSession); assertEquals(0, store.sessionCount()); assertEquals(0, store.messageCount())
        assertTrue(vm.uiState.value.sessions.isEmpty()); assertNull(vm.uiState.value.activeSessionId)
    }
    @Test fun rename_validatesThenUpdatesDatabase() = runTest(dispatcher) {
        val vm = ChatViewModel(repository); advanceUntilIdle(); stored(vm, "原标题")
        val session = activeSession(vm.uiState.value); vm.requestRename(session)
        assertEquals("原标题", vm.uiState.value.renameDraft)
        vm.updateRenameDraft("   "); vm.confirmRename(); advanceUntilIdle()
        assertEquals("标题不能为空", vm.uiState.value.renameError); assertEquals("原标题", store.sessionDao.findById(session.id)!!.title)
        vm.updateRenameDraft("x".repeat(SessionRepository.MAX_TITLE_LENGTH + 1)); vm.confirmRename(); advanceUntilIdle()
        assertEquals("标题最长 " + SessionRepository.MAX_TITLE_LENGTH + " 个字符", vm.uiState.value.renameError)
        assertEquals("原标题", store.sessionDao.findById(session.id)!!.title)
        vm.updateRenameDraft("  项目讨论  "); vm.confirmRename(); advanceUntilIdle()
        assertNull(vm.uiState.value.pendingRenameSession); assertNull(vm.uiState.value.renameError); assertEquals("项目讨论", store.sessionDao.findById(session.id)!!.title)
    }
    @Test fun rename_closesDialogWhenSessionDisappeared() = runTest(dispatcher) {
        val vm = ChatViewModel(repository); advanceUntilIdle(); stored(vm, "会被删掉")
        val session = activeSession(vm.uiState.value); repository.deleteSession(session.id); advanceUntilIdle()
        vm.requestRename(session); vm.confirmRename(); advanceUntilIdle()
        assertNull(vm.uiState.value.pendingRenameSession); assertNull(vm.uiState.value.renameError); assertEquals(0, store.sessionCount())
    }
    @Test fun stateIsRestoredByFreshViewModelOverSameDatabase_whenStartupNewDisabled() = runTest(dispatcher) {
        val preferences = MemoryChatPreferences(ChatLaunchPreferences(startNewChat = false))
        val first = ChatViewModel(repository, preferences); advanceUntilIdle()
        first.newSession("持久化验证"); first.onInputChange("写入数据库的一句话"); first.send(); advanceUntilIdle()
        val second = ChatViewModel(repository, preferences); advanceUntilIdle()
        assertEquals(1, second.uiState.value.sessions.size); assertEquals("持久化验证", second.uiState.value.sessions.single().title)
        assertEquals(listOf("写入数据库的一句话"), second.uiState.value.messages.map { it.content })
        assertFalse(second.uiState.value.messages.any { it.isDemo })
    }
    @Test fun dismissNotice_clearsMessageWithoutTouchingDatabase() = runTest(dispatcher) {
        val vm = ChatViewModel(repository); advanceUntilIdle(); vm.onInputChange("hi"); vm.send(); advanceUntilIdle()
        assertNotNull(vm.uiState.value.notice); vm.dismissNotice(); assertNull(vm.uiState.value.notice); assertEquals(1, store.sessionCount())
    }
}