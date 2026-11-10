package com.example.aiuseagent.data.provider.openai

import com.example.aiuseagent.data.provider.http.OkHttpTransport
import com.example.aiuseagent.data.provider.http.ProviderDiagnostics
import com.example.aiuseagent.data.provider.http.RetryPolicy
import com.example.aiuseagent.domain.provider.ChatRequest
import com.example.aiuseagent.domain.provider.FinishReason
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderCredential
import com.example.aiuseagent.domain.provider.ProviderErrorKind
import com.example.aiuseagent.domain.provider.ProviderEvent
import com.example.aiuseagent.domain.provider.ProviderEventContract
import com.example.aiuseagent.domain.provider.ProviderType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 把若干 JSON 片段拼成一条 OpenAI 风格的 SSE 流：每条 data: 后面跟一个空行。 */
private fun streamOf(vararg dataLines: String): String =
    dataLines.joinToString("") { "data: " + it + "\n\n" }

/** 单测里一律使用虚构凭据：真实 API Key 绝不进入仓库。 */
private const val FAKE_API_KEY = "test-key-not-secret"

/**
 * OpenAI 兼容适配器对着真实 HTTP 服务（MockWebServer）的端到端行为。
 * 覆盖请求组装、SSE 片段语义、错误类别映射与重试次数上限。
 */
class OpenAiCompatibleProviderTest {

    private lateinit var server: MockWebServer

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun sse(body: String) =
        MockResponse(200, Headers.headersOf("Content-Type", "text/event-stream"), body)

    private fun provider(diagnostics: ProviderDiagnostics = ProviderDiagnostics.NoOp) = OpenAiCompatibleProvider(
        type = ProviderType.DEEPSEEK,
        transport = OkHttpTransport(),
        retryPolicy = RetryPolicy(maxAttempts = 3, initialBackoffMillis = 1, maxBackoffMillis = 2),
        backoff = { },
        diagnostics = diagnostics,
    )

    private fun config() = ProviderConfig.forType(ProviderType.DEEPSEEK).copy(
        baseUrl = server.url("/v1").toString(),
        model = "deepseek-chat",
        allowInsecureHttp = true,
        connectTimeoutMillis = 5_000,
        readTimeoutMillis = 5_000,
        totalTimeoutMillis = 15_000,
    )

    private fun request() = ChatRequest.ofUserText("你好")

    private fun credential() = ProviderCredential.of(FAKE_API_KEY)

    @Test
    fun sendsOpenAiCompatibleRequestAndStreamsTheAnswer() = runBlocking {
        server.enqueue(
            sse(
                streamOf(
                    "{\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}",
                    "{\"choices\":[{\"delta\":{\"content\":\"，世界\"},\"finish_reason\":\"stop\"}]," +
                        "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":5}}",
                    "[DONE]",
                )
            )
        )

        val events = provider()
            .stream(
                request().copy(system = "你是简洁的助手", temperature = 0.2, maxOutputTokens = 64),
                config(),
                credential(),
            )
            .toList()

        assertEquals(
            "事件必须满足 ProviderEventContract",
            emptyList<String>(),
            ProviderEventContract.validate(events),
        )
        assertEquals("你好，世界", ProviderEventContract.textOf(events))
        val completed = events.last() as ProviderEvent.Completed
        assertEquals(FinishReason.STOP, completed.finishReason)
        assertEquals(3, completed.usage?.inputTokens)
        assertEquals(5, completed.usage?.outputTokens)
        assertEquals("一次成功流只发一个请求", 1, server.requestCount)

        val recorded = server.takeRequest()
        assertEquals("baseUrl 已含 /v1，相对路径不得重复 /v1", "/v1/chat/completions", recorded.target)
        assertEquals("Bearer " + FAKE_API_KEY, recorded.headers["Authorization"])
        assertEquals("text/event-stream", recorded.headers["Accept"])
        assertTrue(
            "Content-Type 必须是 application/json（OkHttp 会补 charset）",
            recorded.headers["Content-Type"]?.startsWith("application/json") == true,
        )

        val payload = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals("deepseek-chat", payload["model"]!!.jsonPrimitive.content)
        assertTrue(payload["stream"]!!.jsonPrimitive.boolean)
        assertEquals(64, payload["max_tokens"]!!.jsonPrimitive.int)
        assertEquals(0.2, payload["temperature"]!!.jsonPrimitive.content.toDouble(), 0.0001)
        val messages = payload["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("system", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("你是简洁的助手", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        assertEquals("user", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("你好", messages[1].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun withoutSystemPromptOnlyTheUserMessageIsSent() = runBlocking {
        server.enqueue(sse(streamOf("[DONE]")))

        provider().stream(request(), config(), credential()).toList()

        val payload = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        val messages = payload["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
    }

    @Test
    fun emptyChoicesAndUnknownFieldsAreIgnoredInsteadOfBecomingAnswers() = runBlocking {
        server.enqueue(
            sse(
                streamOf(
                    "{\"choices\":[]}",
                    "{\"id\":\"abc\",\"object\":\"chat.completion.chunk\",\"unknown_field\":{\"nested\":1}," +
                        "\"choices\":[{\"index\":0,\"logprobs\":null,\"delta\":{\"content\":\"好\"}}]}",
                    "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":\"内部推理\"}}]}",
                    "[DONE]",
                )
            )
        )

        val events = provider().stream(request(), config(), credential()).toList()

        assertEquals("推理字段不得当答案输出", "好", ProviderEventContract.textOf(events))
        assertTrue("仍然要正常完成", events.last() is ProviderEvent.Completed)
        assertEquals(emptyList<String>(), ProviderEventContract.validate(events))
    }

    @Test
    fun toolCallsAreNeverTurnedIntoAnAnswer() = runBlocking {
        server.enqueue(
            sse(
                streamOf(
                    "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\"," +
                        "\"function\":{\"name\":\"get_weather\",\"arguments\":\"{}\"}}]}}]}",
                )
            )
        )

        val events = provider().stream(request(), config(), credential()).toList()

        val failure = events.last() as ProviderEvent.Failed
        assertEquals(ProviderErrorKind.PROTOCOL, failure.error.kind)
        assertEquals("不支持的能力不得当普通答案输出", "", ProviderEventContract.textOf(events))
        assertEquals("协议错误不得重试", 1, server.requestCount)
    }

    @Test
    fun streamedErrorObjectIsMappedToRateLimitAndRetriedWithinTheLimit() = runBlocking {
        repeat(3) {
            server.enqueue(
                sse(streamOf("{\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}"))
            )
        }

        val events = provider().stream(request(), config(), credential()).toList()

        val failure = events.last() as ProviderEvent.Failed
        assertEquals(ProviderErrorKind.RATE_LIMITED, failure.error.kind)
        assertEquals("1 次原始请求 + 2 次重试", 3, server.requestCount)
    }

    @Test
    fun httpErrorStatusIsNeverParsedAsSse() = runBlocking {
        repeat(3) {
            server.enqueue(
                MockResponse(
                    503,
                    Headers.headersOf("Content-Type", "text/event-stream"),
                    streamOf("{\"choices\":[{\"delta\":{\"content\":\"不该出现\"}}]}", "[DONE]"),
                )
            )
        }

        val events = provider().stream(request(), config(), credential()).toList()

        val failure = events.last() as ProviderEvent.Failed
        assertEquals(ProviderErrorKind.SERVER_ERROR, failure.error.kind)
        assertEquals(503, failure.error.httpStatus)
        assertEquals("非 2xx 的响应体不得当 SSE 解析", "", ProviderEventContract.textOf(events))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun unauthorizedIsNotRetriedAndTheServerEchoIsNotKept() = runBlocking {
        server.enqueue(
            MockResponse(
                401,
                Headers.headersOf("Content-Type", "application/json", "x-request-id", "req-401"),
                "{\"error\":{\"type\":\"invalid_api_key\",\"message\":\"Incorrect API key provided: " +
                    FAKE_API_KEY + "\"}}",
            )
        )

        val events = provider().stream(request(), config(), credential()).toList()

        val failure = events.last() as ProviderEvent.Failed
        assertEquals(ProviderErrorKind.UNAUTHORIZED, failure.error.kind)
        assertEquals("req-401", failure.error.requestId)
        assertEquals("401 不得重试", 1, server.requestCount)
        val detail = failure.error.detail.orEmpty()
        assertTrue("必须保留机器可读标记，实际：" + detail, detail.contains("invalid_api_key"))
        assertTrue("服务端回显的密钥不得进入细节", !detail.contains(FAKE_API_KEY))
    }

    @Test
    fun missingCredentialSendsNothing() = runBlocking {
        val events = provider().stream(request(), config(), ProviderCredential.Absent).toList()

        val failure = events.last() as ProviderEvent.Failed
        assertEquals(ProviderErrorKind.MISSING_API_KEY, failure.error.kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun emptyTwoHundredIsReportedAsEmptyResponse() = runBlocking {
        server.enqueue(sse(""))

        val events = provider().stream(request(), config(), credential()).toList()

        val failure = events.last() as ProviderEvent.Failed
        assertEquals(ProviderErrorKind.EMPTY_RESPONSE, failure.error.kind)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun crlfLineEndingsAndHeartbeatCommentsAreAccepted() = runBlocking {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\r\n" +
                    "\r\n" +
                    ": keep-alive\r\n" +
                    "\r\n" +
                    "data: [DONE]\r\n" +
                    "\r\n"
            )
        )

        val events = provider().stream(request(), config(), credential()).toList()

        assertEquals("a", ProviderEventContract.textOf(events))
        assertTrue(events.last() is ProviderEvent.Completed)
    }

    @Test
    fun doneOnlyEmptyDeltaAndWhitespaceDeltaAreEmptyResponses() = runBlocking {
        for (body in listOf(
            streamOf("[DONE]"),
            streamOf("{\"choices\":[{\"delta\":{\"content\":\"\"}}]}", "[DONE]"),
            streamOf("{\"choices\":[{\"delta\":{\"content\":\" \\n\\t\"}}]}", "[DONE]"),
        )) {
            val before = server.requestCount
            server.enqueue(sse(body))
            val events = provider().stream(request(), config(), credential()).toList()
            assertEquals(ProviderErrorKind.EMPTY_RESPONSE, (events.last() as ProviderEvent.Failed).error.kind)
            assertEquals(1, events.count { it is ProviderEvent.Failed || it is ProviderEvent.Completed })
            assertTrue(ProviderEventContract.validate(events).isEmpty())
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test
    fun invalidJsonHasAFixedSafeProtocolDetail() = runBlocking {
        assertSafeProtocolFailure(streamOf("invalid-json private-response-body " + FAKE_API_KEY),
            expectedDetail = "流式 JSON 数据格式无效")
    }

    @Test
    fun truncatedJsonAtEndOfStreamHasAFixedSafeProtocolDetail() = runBlocking {
        assertSafeProtocolFailure("data: {\"private-response-body\":\"" + FAKE_API_KEY + "\",\"choices\":[",
            expectedDetail = "流式 JSON 数据格式无效")
    }

    @Test
    fun wrongFieldTypesFailInsteadOfBeingIgnoredOrTurnedIntoText() = runBlocking {
        val badPayloads = listOf(
            "[\"$FAKE_API_KEY\"]",
            "{\"error\":\"$FAKE_API_KEY\"}",
            "{\"error\":{\"type\":[],\"message\":\"$FAKE_API_KEY\"}}",
            "{\"choices\":\"$FAKE_API_KEY\"}",
            "{\"choices\":[{\"delta\":\"$FAKE_API_KEY\"}]}",
            "{\"choices\":[{\"delta\":{\"content\":42}}],\"private-response-body\":\"$FAKE_API_KEY\"}",
            "{\"choices\":[{\"delta\":{\"content\":\"uncommitted\"},\"finish_reason\":{\"value\":\"$FAKE_API_KEY\"}}]}",
            "{\"choices\":[],\"usage\":{\"prompt_tokens\":\"$FAKE_API_KEY\"}}",
            "{\"choices\":[],\"usage\":{\"prompt_tokens\":\"3\"}}",
            "{\"choices\":[],\"usage\":{\"completion_tokens\":true}}",
        )
        for (payload in badPayloads) assertSafeProtocolFailure(streamOf(payload, "[DONE]"))
    }

    @Test
    fun functionCallsMultimodalContentAndToolFinishReasonsRemainUnsupported() = runBlocking {
        for (payload in listOf(
            "{\"choices\":[{\"delta\":{\"function_call\":{\"name\":\"local_tool\"}}}]}",
            "{\"choices\":[{\"delta\":{\"content\":[{\"type\":\"image\",\"url\":\"private-response-body\"}]}}]}",
            "{\"choices\":[{\"delta\":{},\"finish_reason\":\"tool_calls\"}]}",
        )) assertSafeProtocolFailure(streamOf(payload, "[DONE]"))
    }

    @Test
    fun malformedJsonAfterTextPreservesPartialTextAndHasOnlyAFailedTerminal() = runBlocking {
        server.enqueue(sse(streamOf("{\"choices\":[{\"delta\":{\"content\":\"部分答案\"}}]}",
            "{\"private-response-body\":\"$FAKE_API_KEY\",")))
        val events = provider().stream(request(), config(), credential()).toList()
        val error = (events.last() as ProviderEvent.Failed).error
        assertEquals("部分答案", ProviderEventContract.textOf(events))
        assertEquals(ProviderErrorKind.PROTOCOL, error.kind)
        assertEquals("流式 JSON 数据格式无效", error.detail)
        assertEquals(1, events.count { it is ProviderEvent.Failed || it is ProviderEvent.Completed })
        assertEquals(1, server.requestCount)
        assertTrue(!error.toString().contains(FAKE_API_KEY))
        assertTrue(!error.toString().contains("private-response-body"))
    }

    private suspend fun assertSafeProtocolFailure(body: String, expectedDetail: String? = null) {
        val before = server.requestCount
        val diagnostics = ProviderDiagnostics.Recording()
        server.enqueue(sse(body))
        val events = provider(diagnostics).stream(request(), config(), credential()).toList()
        val error = (events.last() as ProviderEvent.Failed).error
        assertEquals(ProviderErrorKind.PROTOCOL, error.kind)
        if (expectedDetail != null) assertEquals(expectedDetail, error.detail)
        assertEquals("不支持/无效字段不得产生答案", "", ProviderEventContract.textOf(events))
        assertEquals(1, events.count { it is ProviderEvent.Failed || it is ProviderEvent.Completed })
        assertTrue(ProviderEventContract.validate(events).isEmpty())
        val output = error.toString() + diagnostics.snapshot().joinToString()
        assertTrue("秘密不得进入错误/诊断", !output.contains(FAKE_API_KEY))
        assertTrue("解析正文不得进入错误/诊断", !output.contains("private-response-body"))
        assertEquals("协议错误不得重试", before + 1, server.requestCount)
    }
}
