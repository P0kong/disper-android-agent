package com.example.aiuseagent.data.generation

import com.example.aiuseagent.data.FakeDaoStore
import com.example.aiuseagent.data.GenerationPersistence
import com.example.aiuseagent.data.SessionRepository
import com.example.aiuseagent.data.local.GenerationDao
import com.example.aiuseagent.data.provider.ProviderRepository
import com.example.aiuseagent.data.workspace.ToolBudget
import com.example.aiuseagent.data.workspace.WorkspaceToolResult
import com.example.aiuseagent.domain.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/** Exercises the real coordinator/repository together; fakes replace only provider, tools and Room. */
class GenerationCoordinatorTest {
    @Test fun oversizedCallIdsRejectEntireBatchBeforeFileEffects() = runBlocking {
        for (length in listOf(161,256)) {
            val parent=java.io.File("build/test-task-files").absoluteFile.apply { mkdirs() }.toPath()
            val temporary=java.nio.file.Files.createTempDirectory(parent,"invalid-call-")
            val root=java.nio.file.Files.createDirectory(temporary.resolve("root"))
            val files=com.example.aiuseagent.data.workspace.WorkspaceMutationService(
                com.example.aiuseagent.data.execution.TaskFileStore(root,java.nio.file.Files.createDirectory(temporary.resolve("recovery"))))
            val args="""{"path":"result.md","expected_hash":"MISSING","text":"synthetic"}"""
            val h=Harness(availableTools=GenerationCoordinator.FILE_TOOLS + com.example.aiuseagent.data.workspace.WorkspaceMutationService.TOOLS) { _,_ ->
                toolCalls(ProviderToolCall("valid-first","write_text_file",args),ProviderToolCall("x".repeat(length),"write_text_file",args))
            }
            h.toolHandler={name,arguments,budget->files.execute(files.prepare(name,arguments),budget)}
            try {
                assertTrue(h.coordinator.submit(GenerationSubmission(null,"synthetic",build=true)));h.idle()
                assertFalse(java.nio.file.Files.exists(root.resolve("result.md")))
                assertTrue(h.toolExecutions.isEmpty());assertTrue(h.store.tools.value.isEmpty())
                assertEquals("PROTOCOL",h.store.generations.value.single().errorKind)
            } finally { h.close();temporary.toFile().deleteRecursively() }
        }
    }
    @Test
    fun explicitlyRegisteredMutationWritesFileAndReturnsReceiptToProvider() = runBlocking {
        val parent=java.io.File("build/test-task-files").absoluteFile.apply { mkdirs() }.toPath()
        val temporary=java.nio.file.Files.createTempDirectory(parent,"coordinator-")
        val root=java.nio.file.Files.createDirectory(temporary.resolve("root"))
        val recovery=java.nio.file.Files.createDirectory(temporary.resolve("recovery"))
        val files=com.example.aiuseagent.data.workspace.WorkspaceMutationService(
            com.example.aiuseagent.data.execution.TaskFileStore(root,recovery))
        val h=Harness(availableTools=GenerationCoordinator.FILE_TOOLS + com.example.aiuseagent.data.workspace.WorkspaceMutationService.TOOLS) { round,request ->
            if(round==1) toolCalls(ProviderToolCall("write-1","write_text_file","""{"path":"result.md","expected_hash":"MISSING","text":"synthetic result"}"""))
            else {
                assertTrue(request.messages.last().content.contains("recovery_token"))
                answer("工具已返回写入回执")
            }
        }
        h.toolHandler={name,args,budget->files.execute(files.prepare(name,args),budget)}
        try {
            assertTrue(h.coordinator.submit(GenerationSubmission(null,"create synthetic file",build=true)))
            h.idle()
            assertEquals("COMPLETED",h.store.generations.value.single().status)
            assertEquals("synthetic result",java.nio.file.Files.readString(root.resolve("result.md")))
            assertEquals(listOf("write_text_file"),h.toolExecutions)
            assertEquals(2,h.provider.requests.size)
        } finally { h.close();temporary.toFile().deleteRecursively() }
    }

    @Test
    fun streamsThenPersistsOneUserAndOneCompletedAssistantWithUsage() = runBlocking {
        val h = Harness { _, _ -> flow {
            emit(ProviderEvent.TextDelta("first ")); emit(ProviderEvent.TextDelta("answer"))
            emit(ProviderEvent.Completed(usage = TokenUsage(3, 5)))
        } }
        try {
            assertTrue(h.coordinator.submit(GenerationSubmission(null, "question")))
            h.idle()
            assertEquals(listOf("user", "assistant"), h.store.messages.value.map { it.role })
            assertEquals("first answer", h.store.messages.value.last().content)
            assertEquals("COMPLETED", h.store.generations.value.single().status)
            assertEquals(1, h.provider.requests.size)
            assertEquals(1, h.coordinator.state.value.realRequests)
            assertEquals(3, h.coordinator.state.value.inputTokens)
            assertEquals(5, h.coordinator.state.value.outputTokens)
            assertTrue(h.provider.requests.single().thinkingDisabled)
            assertEquals(1, h.provider.requests.single().maxAttempts)
        } finally { h.close() }
    }

    @Test
    fun doubleSubmitIsRejectedUntilStoppedAndLateOldTextCannotEnterNextSession() = runBlocking {
        val h = Harness { round, _ -> flow {
            emit(ProviderEvent.TextDelta(if (round == 1) "old partial" else "new answer"))
            if (round == 1) try { awaitCancellation() } finally {
                // A misbehaving source tries to send a late chunk after cancellation.
                withContext(NonCancellable) { emit(ProviderEvent.TextDelta("late stale text")) }
            } else emit(ProviderEvent.Completed())
        } }
        try {
            assertTrue(h.coordinator.submit(GenerationSubmission(null, "first")))
            val oldSession = h.coordinator.state.value.sessionId!!
            assertFalse(h.coordinator.submit(GenerationSubmission(null, "duplicate")))
            assertTrue(h.coordinator.stopAndJoin("切换会话"))
            assertEquals("CANCELLED", h.store.generations.value.single().status)
            assertEquals("old partial", h.store.messages.value.last().content)
            assertTrue(h.coordinator.submit(GenerationSubmission(null, "second")))
            h.idle()
            assertNotEquals(oldSession, h.coordinator.state.value.sessionId)
            assertEquals("new answer", h.coordinator.state.value.text)
            assertFalse(h.store.messages.value.any { it.content.contains("late stale") || it.content == "duplicate" })
            assertEquals(2, h.provider.requests.size)
        } finally { h.close() }
    }

    @Test
    fun immediateBackgroundStopBeforeProviderRunsDoesNotLeaveBusyLease() = runBlocking {
        val h = Harness { _, _ -> flow { awaitCancellation() } }
        try {
            assertTrue(h.coordinator.submit(GenerationSubmission(null, "stop now")))
            h.coordinator.stop("应用进入后台")
            assertTrue(h.coordinator.stopAndJoin("应用进入后台"))
            assertFalse(h.coordinator.state.value.busy)
            assertEquals("CANCELLED", h.store.generations.value.single().status)
            assertTrue(h.store.messages.value.none { it.role == "assistant" })
        } finally { h.close() }
    }

    @Test
    fun partialFailureIsSavedAndRetryReusesUserMessageWithoutDuplicate() = runBlocking {
        val h = Harness { count, _ -> flow {
            emit(ProviderEvent.TextDelta(if (count == 1) "partial" else "retry success"))
            if (count == 1) emit(ProviderEvent.Failed(ProviderError.of(ProviderErrorKind.SERVER_ERROR)))
            else emit(ProviderEvent.Completed())
        } }
        try {
            h.coordinator.submit(GenerationSubmission(null, "question")); h.idle()
            val state = h.coordinator.state.value
            assertTrue(state.canRetry)
            assertEquals("FAILED", h.store.generations.value.single().status)
            assertEquals("partial", h.store.messages.value.last().content)
            assertTrue(h.coordinator.submit(GenerationSubmission(state.sessionId, "question", retryUserMessageId = state.userMessageId)))
            h.idle()
            assertEquals(1, h.store.messages.value.count { it.role == "user" })
            assertEquals(2, h.store.messages.value.count { it.role == "assistant" })
            assertEquals("retry success", h.store.messages.value.last().content)
            assertTrue(h.provider.requests.last().messages.none { it.content == "partial" })
        } finally { h.close() }
    }

    @Test
    fun emptyCompletionRecordsFailedGenerationWithoutCreatingEmptyAssistant() = runBlocking {
        val h = Harness { _, _ -> flow { emit(ProviderEvent.Completed()) } }
        try {
            h.coordinator.submit(GenerationSubmission(null, "question")); h.idle()
            assertEquals("FAILED", h.store.generations.value.single().status)
            assertEquals("EMPTY_RESPONSE", h.store.generations.value.single().errorKind)
            assertEquals(listOf("user"), h.store.messages.value.map { it.role })
            assertTrue(h.coordinator.state.value.canRetry)
        } finally { h.close() }
    }

    @Test
    fun saveFailureCanRetryOnlyPersistenceWithoutAnotherModelRequest() = runBlocking {
        val h = Harness { _, _ -> answer("retain this answer") }
        try {
            h.failWrites = true
            h.coordinator.submit(GenerationSubmission(null, "question")); h.idle()
            assertTrue(h.coordinator.state.value.savingFailed)
            assertFalse(h.coordinator.submit(GenerationSubmission(null, "blocked")))
            assertEquals(1, h.provider.requests.size)
            h.failWrites = false
            h.coordinator.retrySave(); h.saved()
            assertEquals("retain this answer", h.store.messages.value.last().content)
            assertEquals("COMPLETED", h.store.generations.value.single().status)
            assertEquals(1, h.provider.requests.size)
        } finally { h.close() }
    }

    @Test
    fun periodicSaveFailureCancelsHeldNetworkAndCanBeSavedAfterFailureClears() = runBlocking {
        val h = Harness(limits = limits().copy(flushMillis = 20)) { _, _ -> flow {
            emit(ProviderEvent.TextDelta("partial answer")); awaitCancellation()
        } }
        try {
            h.failWrites = true
            h.coordinator.submit(GenerationSubmission(null, "question"))
            withTimeout(3_000) { h.coordinator.state.first { it.savingFailed } }
            withTimeout(3_000) { h.provider.openCollectors.first { it == 0 } }
            h.idle()
            h.failWrites = false
            h.coordinator.retrySave(); h.saved()
            assertEquals("partial answer", h.store.messages.value.last().content)
            assertEquals("FAILED", h.store.generations.value.single().status)
            assertEquals(1, h.provider.requests.size)
        } finally { h.close() }
    }

    @Test
    fun streamingWritesAreThrottledAndUnchangedTextIsNotRepeated() = runBlocking {
        val firstFlush = CompletableDeferred<Unit>()
        val h = Harness(limits = limits().copy(flushMillis = 30)) { _, _ -> flow {
            repeat(100) { emit(ProviderEvent.TextDelta("a")) }
            awaitCancellation()
        } }
        try {
            h.writeHook = { if (it == "STREAMING") firstFlush.complete(Unit) }
            h.coordinator.submit(GenerationSubmission(null, "question"))
            withTimeout(3_000) { firstFlush.await() }
            assertEquals(1, h.writeStatuses.count { it == "STREAMING" })
            delay(80)
            assertEquals(1, h.writeStatuses.count { it == "STREAMING" })
            assertTrue(h.coordinator.stopAndJoin())
            assertEquals("a".repeat(100), h.store.messages.value.last().content)
            assertEquals("CANCELLED", h.store.messages.value.last().status)
        } finally { h.close() }
    }

    @Test
    fun stoppingDuringStreamingWriteKeepsCancelledStatusAndDoesNotBecomeSaveFailure() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val h = Harness(limits = limits().copy(flushMillis = 20)) { _, _ -> flow {
            emit(ProviderEvent.TextDelta("preserved partial")); awaitCancellation()
        } }
        try {
            h.writeHook = { status -> if (status == "STREAMING") { entered.complete(Unit); awaitCancellation() } }
            h.coordinator.submit(GenerationSubmission(null, "question"))
            withTimeout(3_000) { entered.await() }
            assertTrue(h.coordinator.stopAndJoin())
            assertFalse(h.coordinator.state.value.savingFailed)
            assertEquals("CANCELLED", h.store.generations.value.single().status)
            assertEquals("preserved partial", h.store.messages.value.last().content)
        } finally { h.close() }
    }

    @Test
    fun toolRoundTripUsesOnlyDeclaredReadOnlyToolsAndStoresMetadata() = runBlocking {
        val h = Harness { count, request -> if (count == 1) toolCalls(call("call_1")) else {
            assertEquals(ChatRole.TOOL, request.messages.last().role)
            assertEquals("call_1", request.messages.last().toolCallId)
            assertEquals("safe file body", request.messages.last().content)
            answer("file summary")
        } }
        try {
            assertTrue(h.coordinator.submit(GenerationSubmission(null, "read notes", build = true)))
            h.idle()
            assertEquals(2, h.provider.requests.size)
            assertEquals(1, h.toolExecutions.size)
            assertEquals("SUCCESS", h.store.tools.value.single().status)
            assertEquals("file summary", h.store.messages.value.last().content)
            assertEquals(listOf("user", "assistant"), h.store.messages.value.map { it.role })
            assertTrue(h.provider.requests.all { it.tools.map { tool -> tool.name }.toSet() == GenerationCoordinator.TOOL_NAMES })
            assertTrue(h.coordinator.state.value.activities.single().contains("完成"))
        } finally { h.close() }
    }

    @Test
    fun repeatedToolCallIdIsNeverExecutedTwice() = runBlocking {
        val h = Harness { _, _ -> toolCalls(call("replayed")) }
        try {
            h.coordinator.submit(GenerationSubmission(null, "read", build = true)); h.idle()
            assertEquals(2, h.provider.requests.size)
            assertEquals(1, h.toolExecutions.size)
            assertEquals("PROTOCOL", h.store.generations.value.single().errorKind)
        } finally { h.close() }
    }

    @Test
    fun thirdRoundCanCompleteAfterTwoToolRoundsButEmptyFinalTextCannotUseToolIntroduction() = runBlocking {
        val complete = Harness { count, _ -> if (count < 3) toolCalls(call("call_$count")) else answer("final summary") }
        try {
            complete.coordinator.submit(GenerationSubmission(null, "read", build = true)); complete.idle()
            assertEquals(3, complete.provider.requests.size)
            assertEquals(2, complete.toolExecutions.size)
            assertEquals("COMPLETED", complete.store.generations.value.single().status)
        } finally { complete.close() }
        val empty = Harness { count, _ -> flow {
            if (count == 1) { emit(ProviderEvent.TextDelta("tool introduction")); emitAll(toolCalls(call("id"))) }
            else emit(ProviderEvent.Completed())
        } }
        try {
            empty.coordinator.submit(GenerationSubmission(null, "read", build = true)); empty.idle()
            assertEquals("FAILED", empty.store.generations.value.single().status)
            assertEquals("EMPTY_RESPONSE", empty.store.generations.value.single().errorKind)
        } finally { empty.close() }
    }

    @Test
    fun workspaceRevisionChangeDuringToolExecutionRejectsResultAndStopsNextRound() = runBlocking {
        val h = Harness { _, _ -> toolCalls(call("id")) }
        try {
            h.toolHook = { h.workspaceRevision++ }
            h.coordinator.submit(GenerationSubmission(null, "read", build = true)); h.idle()
            assertEquals(1, h.provider.requests.size)
            assertEquals(1, h.toolExecutions.size)
            assertTrue(h.store.tools.value.isEmpty())
            assertEquals("FAILED", h.store.generations.value.single().status)
        } finally { h.close() }
    }

    @Test
    fun toolCallAndRoundLimitsFailBeforeExecutingExcessCalls() = runBlocking {
        val tooMany = Harness { _, _ -> toolCalls(*(1..5).map { call("id_$it") }.toTypedArray()) }
        try {
            tooMany.coordinator.submit(GenerationSubmission(null, "read", build = true)); tooMany.idle()
            assertTrue(tooMany.toolExecutions.isEmpty())
            assertEquals(1, tooMany.provider.requests.size)
        } finally { tooMany.close() }
        val rounds = Harness { count, _ -> toolCalls(call("id_$count")) }
        try {
            rounds.coordinator.submit(GenerationSubmission(null, "read", build = true)); rounds.idle()
            assertEquals(3, rounds.provider.requests.size)
            assertEquals(2, rounds.toolExecutions.size)
            assertEquals("FAILED", rounds.store.generations.value.single().status)
        } finally { rounds.close() }
    }

    @Test
    fun unknownToolAndNonBuildToolResponseAreRejected() = runBlocking {
        for (build in listOf(false, true)) {
            val h = Harness { _, _ -> toolCalls(call("id", if (build) "write_file" else "read_text_file")) }
            try {
                h.coordinator.submit(GenerationSubmission(null, "read", build = build)); h.idle()
                assertTrue(h.toolExecutions.isEmpty())
                assertEquals("PROTOCOL", h.store.generations.value.single().errorKind)
            } finally { h.close() }
        }
    }

    @Test
    fun knownSecretInputAndHistoricalSecretAreRejectedBeforeAnyMutation() = runBlocking {
        val h = Harness { _, _ -> answer("safe") }
        try {
            assertFalse(h.coordinator.submit(GenerationSubmission(null, "echo $SECRET")))
            assertTrue(h.store.messages.value.isEmpty())
            assertTrue(h.store.sessions.value.isEmpty())
            val id = h.sessions.createSession("historical")
            val profile=h.providers.prepare()
            h.store.sessionDao.updateContext(id,0,0,profile.profileId,profile.profileRevision)
            h.sessions.appendMessage(id, "user", "legacy $SECRET")
            h.store.messages.value = h.store.messages.value.map { it.copy(providerType = ProviderType.DEEPSEEK.name,
                mode="CHAT",profileId=profile.profileId,profileRevision=profile.profileRevision) }
            val old = h.store.messages.value
            assertTrue(h.coordinator.submit(GenerationSubmission(id, "new safe text")))
            h.idle()
            assertEquals(old, h.store.messages.value)
            assertTrue(h.store.generations.value.isEmpty())
            assertTrue(h.provider.requests.isEmpty())
        } finally { h.close() }
    }

    @Test
    fun splitCredentialEchoAndCancelledPrefixCannotEnterUiOrDatabase() = runBlocking {
        val h = Harness { count, _ -> flow {
            emit(ProviderEvent.TextDelta("safe fake-generation-"))
            if (count == 1) { emit(ProviderEvent.TextDelta("credential tail")); emit(ProviderEvent.Completed()) }
            else awaitCancellation()
        } }
        try {
            h.coordinator.submit(GenerationSubmission(null, "first")); h.idle()
            assertEquals("safe *** tail", h.store.messages.value.last().content)
            h.coordinator.submit(GenerationSubmission(null, "second"))
            assertTrue(h.coordinator.stopAndJoin())
            assertEquals("safe ", h.store.messages.value.last().content)
            assertFalse(h.coordinator.state.value.text.contains("fake-generation-"))
            assertTrue(h.store.messages.value.none { it.content.contains(SECRET) })
        } finally { h.close() }
    }

    @Test
    fun credentialReturnedByToolIsNotSavedOrSentToNextModelRound() = runBlocking {
        val h = Harness { _, _ -> toolCalls(call("id")) }
        try {
            h.toolResult = WorkspaceToolResult(true, "contents $SECRET", 10)
            h.coordinator.submit(GenerationSubmission(null, "read", build = true)); h.idle()
            assertEquals(1, h.provider.requests.size)
            assertTrue(h.store.tools.value.isEmpty())
            assertTrue(h.store.messages.value.none { it.content.contains(SECRET) })
            assertEquals("INVALID_CONFIG", h.store.generations.value.single().errorKind)
        } finally { h.close() }
    }

    @Test
    fun acceptanceBudgetPreventsThirteenthRealRequest() = runBlocking {
        val h = Harness { _, _ -> answer("safe answer") }
        try {
            repeat(12) { assertTrue(h.coordinator.submit(GenerationSubmission(null, "question $it"))); h.idle() }
            h.coordinator.submit(GenerationSubmission(null, "thirteenth")); h.idle()
            assertEquals(12, h.provider.requests.size)
            assertEquals(12, h.coordinator.state.value.realRequests)
            assertEquals("BUDGET_EXHAUSTED", h.store.generations.value.last().errorKind)
        } finally { h.close() }
    }

    @Test
    fun unauthorizedAndTwoNetworkFailuresStopAcceptanceWithoutAutoReplay() = runBlocking {
        for (kind in listOf(ProviderErrorKind.UNAUTHORIZED, ProviderErrorKind.NETWORK)) {
            val h = Harness { _, _ -> flow { emit(ProviderEvent.Failed(ProviderError.of(kind))) } }
            try {
                val allowed = if (kind == ProviderErrorKind.NETWORK) 2 else 1
                repeat(allowed) { assertTrue(h.coordinator.submit(GenerationSubmission(null, "question"))); h.idle() }
                assertFalse(h.coordinator.submit(GenerationSubmission(null, "blocked")))
                assertEquals(allowed, h.provider.requests.size)
                assertFalse(h.coordinator.state.value.canRetry)
            } finally { h.close() }
        }
    }

    private class Harness(
        limits: GenerationLimits = limits(),
        availableTools: List<ToolDefinition> = GenerationCoordinator.FILE_TOOLS,
        script: (Int, ChatRequest) -> Flow<ProviderEvent>,
    ) {
        val store = FakeDaoStore()
        var failWrites = false
        var writeHook: suspend (String) -> Unit = {}
        val writeStatuses = mutableListOf<String>()
        private val writes = object : GenerationDao by store.generationDao {
            override suspend fun updateActive(id: String, assistantId: Long?, status: String, updatedAt: Long, errorKind: String?): Int {
                writeStatuses += status
                writeHook(status)
                if (failWrites) throw IllegalStateException("synthetic save failure")
                return store.generationDao.updateActive(id, assistantId, status, updatedAt, errorKind)
            }
        }
        private val persistence = object : GenerationPersistence {
            override val generations: GenerationDao get() = writes
            override suspend fun transaction(block: suspend () -> Unit) = store.generationPersistence.transaction(block)
        }
        val sessions = SessionRepository(store.sessionDao, store.messageDao, persistence) { 100L }
        val provider = FakeProvider(script)
        val providers = ProviderRepository(ProviderRegistry(mapOf(ProviderType.DEEPSEEK to provider))).also {
            it.updateConfig(ProviderConfig(ProviderType.DEEPSEEK, "https://example.invalid", "deepseek-flash"))
            it.putCredential(ProviderType.DEEPSEEK, SECRET)
            it.selectActive(ProviderType.DEEPSEEK)
        }
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val toolExecutions = mutableListOf<String>()
        var toolResult = WorkspaceToolResult(true, "safe file body", 14)
        var toolHook: suspend () -> Unit = {}
        var toolHandler: (suspend (String,String,ToolBudget)->WorkspaceToolResult)?=null
        var workspaceRevision = 0L
        val coordinator = GenerationCoordinator(sessions, providers, scope,
            toolExecutor = { _, name, arguments, budget: ToolBudget -> toolExecutions += name; toolHook(); toolHandler?.invoke(name,arguments,budget) ?: toolResult },
            workspaceAvailable = { true }, limits = limits, workspaceVersion = { workspaceRevision },workspaceTools={availableTools})

        suspend fun idle() { withTimeout(3_000) { coordinator.state.first { !it.busy } } }
        suspend fun saved() { withTimeout(3_000) { coordinator.state.first { !it.busy && !it.savingFailed } } }
        fun close() { scope.cancel() }
    }

    private class FakeProvider(private val script: (Int, ChatRequest) -> Flow<ProviderEvent>) : ModelProvider {
        override val type = ProviderType.DEEPSEEK
        val requests = mutableListOf<ChatRequest>()
        val openCollectors = MutableStateFlow(0)
        override fun stream(request: ChatRequest, config: ProviderConfig, credential: ProviderCredential): Flow<ProviderEvent> = flow {
            requests += request
            openCollectors.value++
            try {
                emit(ProviderEvent.Started(type, config.model))
                emitAll(script(requests.size, request))
            } finally { openCollectors.value-- }
        }
    }

    companion object {
        private const val SECRET = "fake-generation-credential"
        private fun limits() = GenerationLimits.acceptance().copy(joinMillis = 500, saveMillis = 500)
        private fun answer(text: String): Flow<ProviderEvent> = flow {
            emit(ProviderEvent.TextDelta(text)); emit(ProviderEvent.Completed())
        }
        private fun call(id: String, name: String = "read_text_file") = ProviderToolCall(id, name, "{\"path\":\"notes.txt\"}")
        private fun toolCalls(vararg calls: ProviderToolCall): Flow<ProviderEvent> = flow {
            emit(ProviderEvent.ToolCalls(calls.toList())); emit(ProviderEvent.Completed(FinishReason.TOOL_CALLS))
        }
    }
}
