package com.example.aiuseagent.data.generation

import com.example.aiuseagent.data.FakeDaoStore
import com.example.aiuseagent.data.SessionRepository
import com.example.aiuseagent.data.local.MessageEntity
import com.example.aiuseagent.data.provider.HttpProviders
import com.example.aiuseagent.data.provider.http.OkHttpTransport
import com.example.aiuseagent.data.provider.http.ProviderDiagnostics
import com.example.aiuseagent.data.workspace.WorkspaceToolResult
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderType
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real HTTP/SSE transport and provider assembly, confined to a loopback server and one synthetic file. */
class GenerationHttpRoundTripTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun fragmentedToolCallReadsOneSyntheticFileAndSendsItsResultInSecondRequest() = runBlocking {
        val syntheticText = "LOCAL_FILE_SYNTHETIC_MARKER\nOnly this temporary test file may be read."
        val syntheticFile = temporary.newFile("synthetic.txt").apply { writeText(syntheticText, Charsets.UTF_8) }
        val server = MockWebServer()
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        server.start()
        try {
            server.enqueue(sse(
                fragment(id = "call_", name = "read_text_", arguments = "{\"pa"),
                fragment(id = "local", name = "file", arguments = "th\":\"synthetic.txt\"}"),
                finish("tool_calls"), usage(7, 4), "[DONE]",
            ))
            server.enqueue(sse(text("HTTP_TOOL_ROUND_FINAL_MARKER"), finish("stop"), usage(5, 6), "[DONE]"))
            val store = FakeDaoStore()
            val sessions = SessionRepository(store.sessionDao, store.messageDao, store.generationPersistence) { 100L }
            val other = sessions.createSession("unrelated session")
            val target = sessions.createSession("selected session","BUILD","synthetic-workspace")
            suspend fun seed(session: Long, content: String, provider: ProviderType, demo: Boolean = false) {
                store.messageDao.insert(MessageEntity(sessionId = session, role = "assistant", content = content,
                    createdAt = 1L, providerType = provider.name, isDemo = demo))
            }
            seed(other, "OTHER_SESSION_DO_NOT_SEND", ProviderType.DEEPSEEK)
            seed(target, "MOCK_HISTORY_DO_NOT_SEND", ProviderType.MOCK)
            seed(target, "DEMO_HISTORY_DO_NOT_SEND", ProviderType.DEEPSEEK, demo = true)
            seed(target, "SELECTED_REAL_HISTORY", ProviderType.DEEPSEEK)
            val otherBefore = store.messages.value.filter { it.sessionId == other }
            val diagnostics = ProviderDiagnostics.Recording()
            val providers = HttpProviders.repository(transport = OkHttpTransport(), diagnostics = diagnostics).apply {
                updateConfig(loopbackConfig(server))
                putCredential(ProviderType.DEEPSEEK, FAKE_KEY)
                selectActive(ProviderType.DEEPSEEK)
            }
            val profile=providers.prepare()
            store.sessionDao.updateContext(target,0,0,profile.profileId,profile.profileRevision)
            store.messages.value=store.messages.value.map { if(it.sessionId==target)
                it.copy(mode="BUILD",profileId=profile.profileId,profileRevision=profile.profileRevision) else it }
            val executed = AtomicInteger()
            val coordinator = GenerationCoordinator(sessions, providers, processScope,
                toolExecutor = { workspace, name, arguments, budget ->
                    assertEquals("synthetic-workspace", workspace)
                    assertEquals("read_text_file", name)
                    assertEquals("synthetic.txt", Json.parseToJsonElement(arguments).jsonObject["path"]!!.jsonPrimitive.content)
                    assertEquals(0, executed.getAndIncrement())
                    // Fixed file identity, independent of untrusted path resolution.
                    val bytes = syntheticFile.readBytes()
                    assertTrue(budget.consume(bytes.size))
                    WorkspaceToolResult(true, bytes.toString(Charsets.UTF_8), bytes.size)
                }, workspaceAvailable = { it == "synthetic-workspace" },
                limits = GenerationLimits.acceptance().copy(flushMillis = 10_000))
            withTimeout(5_000) { coordinator.state.first { it.ready } }
            assertTrue(coordinator.submit(GenerationSubmission(target, "READ_LOCAL_SYNTHETIC", "synthetic-workspace", build = true)))
            withTimeout(10_000) { coordinator.state.first { !it.busy } }

            assertEquals(1, executed.get())
            assertEquals(2, server.requestCount)
            assertEquals(2, coordinator.state.value.realRequests)
            assertEquals(12, coordinator.state.value.inputTokens)
            assertEquals(10, coordinator.state.value.outputTokens)
            assertEquals("COMPLETED", store.generations.value.single().status)
            assertEquals(target, store.generations.value.single().sessionId)
            assertEquals("HTTP_TOOL_ROUND_FINAL_MARKER", store.messages.value.last().content)
            assertEquals(otherBefore, store.messages.value.filter { it.sessionId == other })
            assertFalse(store.messages.value.any { it.content.contains("LOCAL_FILE_SYNTHETIC_MARKER") })
            assertEquals(1, store.tools.value.size)
            assertEquals("call_local", store.tools.value.single().callId)
            assertEquals("read_text_file", store.tools.value.single().name)

            val first = server.takeRequest()
            val second = server.takeRequest()
            for (wire in listOf(first, second)) {
                assertEquals("/v1/chat/completions", wire.target)
                assertEquals("Bearer $FAKE_KEY", wire.headers["Authorization"])
            }
            val firstBody = first.body!!.utf8()
            val secondBody = second.body!!.utf8()
            for (body in listOf(firstBody, secondBody)) {
                assertFalse(body.contains("OTHER_SESSION_DO_NOT_SEND"))
                assertFalse(body.contains("MOCK_HISTORY_DO_NOT_SEND"))
                assertFalse(body.contains("DEMO_HISTORY_DO_NOT_SEND"))
                assertFalse(body.contains(FAKE_KEY))
                assertTrue(body.contains("SELECTED_REAL_HISTORY"))
            }
            val roundTwo = Json.parseToJsonElement(secondBody).jsonObject
            val messages = roundTwo["messages"]!!.jsonArray
            val assistant = messages[messages.lastIndex - 1].jsonObject
            assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
            val call = assistant["tool_calls"]!!.jsonArray.single().jsonObject
            assertEquals("call_local", call["id"]!!.jsonPrimitive.content)
            assertEquals("read_text_file", call["function"]!!.jsonObject["name"]!!.jsonPrimitive.content)
            val toolMessage = messages.last().jsonObject
            assertEquals("tool", toolMessage["role"]!!.jsonPrimitive.content)
            assertEquals("call_local", toolMessage["tool_call_id"]!!.jsonPrimitive.content)
            assertEquals(syntheticText, toolMessage["content"]!!.jsonPrimitive.content)
            val attempts = diagnostics.snapshot().filter { it.event == "request_attempt_started" }
            assertEquals(2, attempts.size)
            assertTrue(attempts.all { it.attempt == 1 })
        } finally {
            processScope.cancel()
            server.close()
        }
    }

    @Test
    fun malformedCompletedToolArgumentsFailWithoutReadingFileOrSendingSecondRequest() = runBlocking {
        val server = MockWebServer()
        val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        server.start()
        try {
            server.enqueue(sse(fragment(id = "call_bad", name = "read_text_file", arguments = "{\"path\":"),
                finish("tool_calls"), "[DONE]"))
            val store = FakeDaoStore()
            val sessions = SessionRepository(store.sessionDao, store.messageDao, store.generationPersistence) { 100L }
            val providers = HttpProviders.repository(transport = OkHttpTransport()).apply {
                updateConfig(loopbackConfig(server)); putCredential(ProviderType.DEEPSEEK, FAKE_KEY)
                selectActive(ProviderType.DEEPSEEK)
            }
            val executed = AtomicInteger()
            val coordinator = GenerationCoordinator(sessions, providers, processScope,
                toolExecutor = { _, _, _, _ -> executed.incrementAndGet(); error("Malformed call must not execute") },
                workspaceAvailable = { true }, limits = GenerationLimits.acceptance())
            withTimeout(5_000) { coordinator.state.first { it.ready } }
            assertTrue(coordinator.submit(GenerationSubmission(null, "synthetic malformed case", build = true)))
            withTimeout(10_000) { coordinator.state.first { !it.busy } }
            assertEquals(0, executed.get())
            assertEquals(1, server.requestCount)
            assertEquals("FAILED", store.generations.value.single().status)
            assertEquals("PROTOCOL", store.generations.value.single().errorKind)
            assertTrue(store.tools.value.isEmpty())
            assertTrue(store.messages.value.none { it.role == "assistant" })
        } finally {
            processScope.cancel()
            server.close()
        }
    }

    private fun loopbackConfig(server: MockWebServer) = ProviderConfig(ProviderType.DEEPSEEK,
        server.url("/v1").toString(), "deepseek-flash", allowInsecureHttp = true,
        connectTimeoutMillis = 3_000, readTimeoutMillis = 3_000, totalTimeoutMillis = 5_000)

    private fun sse(vararg payloads: String) = MockResponse(200,
        Headers.headersOf("Content-Type", "text/event-stream"), payloads.joinToString("") { "data: $it\n\n" })

    private fun fragment(id: String, name: String, arguments: String) = buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject { putJsonObject("delta") {
            putJsonArray("tool_calls") { add(buildJsonObject {
                put("index", 0); put("id", id); put("type", "function")
                putJsonObject("function") { put("name", name); put("arguments", arguments) }
            }) }
        } }) }
    }.toString()

    private fun finish(reason: String) = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"$reason\"}]}"
    private fun usage(input: Int, output: Int) = "{\"choices\":[],\"usage\":{\"prompt_tokens\":$input,\"completion_tokens\":$output}}"
    private fun text(content: String) = buildJsonObject {
        putJsonArray("choices") { add(buildJsonObject { putJsonObject("delta") { put("content", content) } }) }
    }.toString()

    companion object { private const val FAKE_KEY = "fake-http-loopback-only-credential" }
}
