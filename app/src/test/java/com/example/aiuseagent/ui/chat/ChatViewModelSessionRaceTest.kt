package com.example.aiuseagent.ui.chat

import com.example.aiuseagent.data.FakeDaoStore
import com.example.aiuseagent.data.SessionRepository
import com.example.aiuseagent.data.MemoryChatPreferences
import com.example.aiuseagent.data.ChatLaunchPreferences
import com.example.aiuseagent.data.local.MessageDao
import com.example.aiuseagent.data.local.MessageEntity
import com.example.aiuseagent.data.local.SessionDao
import com.example.aiuseagent.data.local.SessionEntity
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 在 DAO 挂起与结果乱序时核对实际存储归属和可见内容，而不是订阅次数。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelSessionRaceTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = FakeDaoStore()
    private var sessionFlow: Flow<List<SessionEntity>>? = null
    private var beforeSessionInsert: suspend (SessionEntity) -> Unit = {}
    private var beforeMessageInsert: suspend (MessageEntity) -> Unit = {}
    private var messageFlow: ((Long) -> Flow<List<MessageEntity>>)? = null

    private val sessionDao = object : SessionDao by store.sessionDao {
        override fun observeSessions(): Flow<List<SessionEntity>> =
            sessionFlow ?: store.sessionDao.observeSessions()

        override suspend fun insert(session: SessionEntity): Long {
            beforeSessionInsert(session)
            return store.sessionDao.insert(session)
        }
    }
    private val messageDao = object : MessageDao by store.messageDao {
        override suspend fun insert(message: MessageEntity): Long {
            beforeMessageInsert(message)
            return store.messageDao.insert(message)
        }

        override fun observeMessages(sessionId: Long): Flow<List<MessageEntity>> =
            messageFlow?.invoke(sessionId) ?: store.messageDao.observeMessages(sessionId)
    }
    private val repository = SessionRepository(sessionDao, messageDao) { 1_000L }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private suspend fun session(title: String, marker: String): Long =
        repository.createSession(title).also {
            repository.appendMessage(it, SessionRepository.ROLE_USER, marker)
        }

    private fun contents(sessionId: Long): List<String> =
        store.messages.value.filter { it.sessionId == sessionId }.map { it.content }

    @Test
    fun newSession_replacesOldMessagesWithoutRequiringDrawerSelection() = runTest(dispatcher) {
        val firstId = session("A", "only-A")
        val viewModel = ChatViewModel(repository, MemoryChatPreferences(ChatLaunchPreferences(startNewChat = false)))
        advanceUntilIdle()
        assertEquals(firstId, viewModel.uiState.value.activeSessionId)

        viewModel.newSession("B")
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.activeSessionId)
        assertTrue(viewModel.uiState.value.messages.isEmpty())
        assertEquals(1, store.sessionCount())
        viewModel.onInputChange("only-B")
        viewModel.send()
        advanceUntilIdle()
        val state = viewModel.uiState.value
        val secondId = store.sessions.value.single { it.title == "B" }.id
        assertEquals(secondId, state.activeSessionId)
        assertEquals(listOf("only-B"), state.messages.map { it.content })
        assertFalse(state.messages.single().isDemo)
        assertEquals(listOf("only-A"), contents(firstId))
    }

    @Test
    fun sendDuringSuspendedCreation_staysInNewSessionAfterSelectingAnotherSession() =
        runTest(dispatcher) {
            val firstId = session("A", "only-A")
            val createGate = CompletableDeferred<Unit>()
            beforeSessionInsert = { if (it.title == "B") createGate.await() }
            val viewModel = ChatViewModel(repository, MemoryChatPreferences(ChatLaunchPreferences(startNewChat = false)))
            advanceUntilIdle()

            viewModel.newSession("B")
            viewModel.onInputChange("sent-to-pending-B")
            viewModel.send()
            runCurrent()
            viewModel.selectSession(firstId)
            viewModel.onInputChange("new-draft-in-current-view")
            createGate.complete(Unit)
            advanceUntilIdle()

            val secondId = store.sessions.value.single { it.title == "B" }.id
            assertEquals(listOf("only-A"), contents(firstId))
            assertTrue(contents(secondId).contains("sent-to-pending-B"))
            assertEquals(firstId, viewModel.uiState.value.activeSessionId)
            assertEquals(listOf("only-A"), viewModel.uiState.value.messages.map { it.content })
            assertEquals("new-draft-in-current-view", viewModel.uiState.value.input)
        }

    @Test
    fun creationsCompletingInReverseOrder_keepLatestSelectionAndEachSendTarget() =
        runTest(dispatcher) {
            val firstId = session("A", "only-A")
            val createB = CompletableDeferred<Unit>()
            val createC = CompletableDeferred<Unit>()
            beforeSessionInsert = {
                when (it.title) {
                    "B" -> createB.await()
                    "C" -> createC.await()
                    else -> Unit
                }
            }
            val viewModel = ChatViewModel(repository, MemoryChatPreferences(ChatLaunchPreferences(startNewChat = false)))
            advanceUntilIdle()

            viewModel.newSession("B")
            viewModel.onInputChange("only-B")
            viewModel.send()
            viewModel.newSession("C")
            viewModel.onInputChange("only-C")
            viewModel.send()
            runCurrent()
            createC.complete(Unit)
            advanceUntilIdle()
            val thirdId = store.sessions.value.single { it.title == "C" }.id
            assertEquals(thirdId, viewModel.uiState.value.activeSessionId)

            createB.complete(Unit)
            advanceUntilIdle()
            val secondId = store.sessions.value.single { it.title == "B" }.id
            assertEquals(thirdId, viewModel.uiState.value.activeSessionId)
            assertEquals(listOf("only-A"), contents(firstId))
            assertTrue(contents(secondId).contains("only-B"))
            assertFalse(contents(secondId).contains("only-C"))
            assertTrue(contents(thirdId).contains("only-C"))
            assertFalse(contents(thirdId).contains("only-B"))
            assertTrue(viewModel.uiState.value.messages.any { it.content == "only-C" })
            assertFalse(viewModel.uiState.value.messages.any { it.content == "only-B" })

            // 新 ViewModel 从相同 DAO 重读，各会话仍保持实际数据库归属。
            val restarted = ChatViewModel(repository, MemoryChatPreferences(ChatLaunchPreferences(startNewChat = false)))
            advanceUntilIdle()
            for ((id, marker) in listOf(firstId to "only-A", secondId to "only-B", thirdId to "only-C")) {
                restarted.selectSession(id)
                advanceUntilIdle()
                val markers = restarted.uiState.value.messages.filterNot { it.isDemo }.map { it.content }
                assertEquals(listOf(marker), markers)
            }
        }

    @Test
    fun sendCapturesSelectedSessionBeforeCoroutineStarts() = runTest(dispatcher) {
        val firstId = session("A", "only-A")
        val secondId = session("B", "only-B")
        val viewModel = ChatViewModel(repository, MemoryChatPreferences(ChatLaunchPreferences(startNewChat = false)))
        advanceUntilIdle()
        viewModel.selectSession(firstId)
        advanceUntilIdle()

        viewModel.onInputChange("A-clicked-send")
        viewModel.send()
        viewModel.selectSession(secondId)
        advanceUntilIdle()

        assertTrue(contents(firstId).contains("A-clicked-send"))
        assertFalse(contents(secondId).contains("A-clicked-send"))
        assertEquals(secondId, viewModel.uiState.value.activeSessionId)
        assertEquals(listOf("only-B"), viewModel.uiState.value.messages.map { it.content })
    }

    @Test
    fun delayedSend_doesNotReplaceNewSelectionOrClearLaterInput() = runTest(dispatcher) {
        val firstId = session("A", "only-A")
        val secondId = session("B", "only-B")
        val appendGate = CompletableDeferred<Unit>()
        beforeMessageInsert = { if (it.content == "slow-A-send") appendGate.await() }
        val viewModel = ChatViewModel(repository, MemoryChatPreferences(ChatLaunchPreferences(startNewChat = false)))
        advanceUntilIdle()
        viewModel.selectSession(firstId)
        advanceUntilIdle()

        viewModel.onInputChange("slow-A-send")
        viewModel.send()
        runCurrent()
        viewModel.selectSession(secondId)
        viewModel.onInputChange("later-draft")
        appendGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(contents(firstId).contains("slow-A-send"))
        assertFalse(contents(secondId).contains("slow-A-send"))
        assertEquals(secondId, viewModel.uiState.value.activeSessionId)
        assertEquals("later-draft", viewModel.uiState.value.input)
        assertEquals(listOf("only-B"), viewModel.uiState.value.messages.map { it.content })
    }

    @Test
    fun delayedSend_preservesLaterDraftInSameSession() = runTest(dispatcher) {
        val firstId = session("A", "only-A")
        val appendGate = CompletableDeferred<Unit>()
        beforeMessageInsert = { if (it.content == "slow-send") appendGate.await() }
        val viewModel = ChatViewModel(repository, MemoryChatPreferences(ChatLaunchPreferences(startNewChat = false)))
        advanceUntilIdle()
        viewModel.onInputChange("slow-send")
        viewModel.send()
        runCurrent()
        viewModel.onInputChange("later-draft")
        appendGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(contents(firstId).contains("slow-send"))
        assertEquals("later-draft", viewModel.uiState.value.input)
        assertEquals(ChatViewModel.MODEL_NOT_CONNECTED_NOTICE, viewModel.uiState.value.notice)
    }

    @OptIn(InternalCoroutinesApi::class)
    @Test
    fun cancelledOldMessages_cannotOverwriteLatestMessagesAfterSwitchingBack() = runTest(dispatcher) {
        val firstId = session("A", "old-A")
        val secondId = session("B", "only-B")
        val lateResultGate = CompletableDeferred<Unit>()
        var firstACollection = true
        messageFlow = { id ->
            if (id == firstId && firstACollection) {
                firstACollection = false
                // 模拟取消边界上已经在途的旧回调；它在 A → B → A 后才到达。
                object : Flow<List<MessageEntity>> {
                    override suspend fun collect(collector: FlowCollector<List<MessageEntity>>) {
                        collector.emit(store.messages.value.filter { it.sessionId == id })
                        try {
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) {
                                lateResultGate.await()
                                collector.emit(
                                    listOf(
                                        MessageEntity(
                                            id = 999L,
                                            sessionId = id,
                                            role = SessionRepository.ROLE_USER,
                                            content = "late-stale-A",
                                            createdAt = 0L,
                                        ),
                                    ),
                                )
                            }
                        }
                    }
                }
            } else {
                store.messageDao.observeMessages(id)
            }
        }
        val viewModel = ChatViewModel(repository, MemoryChatPreferences(ChatLaunchPreferences(startNewChat = false)))
        advanceUntilIdle()
        viewModel.selectSession(secondId)
        runCurrent()
        repository.appendMessage(firstId, SessionRepository.ROLE_USER, "fresh-A")
        viewModel.selectSession(firstId)
        runCurrent()
        assertTrue(viewModel.uiState.value.messages.any { it.content == "fresh-A" })

        lateResultGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(firstId, viewModel.uiState.value.activeSessionId)
        assertEquals(listOf("old-A", "fresh-A"), viewModel.uiState.value.messages.map { it.content })
        assertFalse(viewModel.uiState.value.messages.any { it.content == "late-stale-A" })
    }

    @Test
    fun newSession_mainImmediateListPublication_doesNotRestorePreviousSelection() = runTest(dispatcher) {
        val firstId = session("A", "only-A")
        val delayedRows = MutableStateFlow(store.sessions.value)
        sessionFlow = delayedRows
        Dispatchers.setMain(ImmediateMainTestDispatcher(dispatcher))
        val viewModel = ChatViewModel(repository, MemoryChatPreferences(ChatLaunchPreferences(startNewChat = false)))
        advanceUntilIdle()

        viewModel.newSession("B")
        viewModel.onInputChange("immediate-B-send")
        viewModel.send()
        runCurrent()
        val secondId = store.sessions.value.single { it.title == "B" }.id
        // 首次发送触发 insert，创建协程等待 DAO 发布；仍显示空白目标，不恢复 A。
        assertNull(viewModel.uiState.value.activeSessionId)

        delayedRows.value = store.sessions.value
        runCurrent()

        assertEquals(secondId, viewModel.uiState.value.activeSessionId)
        assertEquals(listOf("immediate-B-send"), viewModel.uiState.value.messages.map { it.content })
        advanceUntilIdle()
        assertEquals(listOf("only-A"), contents(firstId))
        assertTrue(contents(secondId).contains("immediate-B-send"))
        assertTrue(viewModel.uiState.value.messages.any { it.content == "immediate-B-send" })
    }

    /** 模拟主线程消息已开始执行时，Main.immediate 对内部恢复直接执行的行为。 */
    private class ImmediateMainTestDispatcher(
        private val queued: CoroutineDispatcher,
    ) : MainCoroutineDispatcher() {
        private var onMain = false
        override val immediate: MainCoroutineDispatcher get() = this

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !onMain

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.dispatch(context, Runnable {
                val previouslyOnMain = onMain
                onMain = true
                try {
                    block.run()
                } finally {
                    onMain = previouslyOnMain
                }
            })
        }
    }

    @Test
    fun sendWithoutSession_createsOneLocalSessionAndDisplaysStoredMessage() = runTest(dispatcher) {
        val viewModel = ChatViewModel(repository, MemoryChatPreferences(ChatLaunchPreferences(startNewChat = false)))
        advanceUntilIdle()
        viewModel.onInputChange("first-local-message")
        viewModel.send()
        advanceUntilIdle()

        val id = viewModel.uiState.value.activeSessionId!!
        assertEquals(1, store.sessionCount())
        assertEquals(listOf("first-local-message"), contents(id))
        assertEquals(listOf("first-local-message"), viewModel.uiState.value.messages.map { it.content })
        assertFalse(viewModel.uiState.value.messages.single().isDemo)
        assertEquals("", viewModel.uiState.value.input)
    }
}
