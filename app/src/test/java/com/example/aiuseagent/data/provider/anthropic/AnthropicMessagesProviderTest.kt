package com.example.aiuseagent.data.provider.anthropic

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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 单测里一律使用虚构凭据：真实 API Key 绝不进入仓库。 */
private const val FAKE_API_KEY = "test-key-not-secret"

/** 把 (事件名, 数据) 拼成 Anthropic 风格的命名事件流。 */
private fun eventStreamOf(vararg pairs: Pair<String, String>): String =
    pairs.joinToString("") { (name, data) -> "event: " + name + "\ndata: " + data + "\n\n" }

/**
 * Anthropic Messages 适配器对着真实 HTTP 服务（MockWebServer）的端到端行为。
 * 重点覆盖与 OpenAI 兼容协议的三处差异，以及命名事件的终止规则。
 */
class AnthropicMessagesProviderTest {

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

    private fun provider(diagnostics: ProviderDiagnostics = ProviderDiagnostics.NoOp) = AnthropicMessagesProvider(
        transport = OkHttpTransport(),
        retryPolicy = RetryPolicy(maxAttempts = 3, initialBackoffMillis = 1, maxBackoffMillis = 2),
        backoff = { },
        diagnostics = diagnostics,
    )

    private fun config() = ProviderConfig.forType(ProviderType.ANTHROPIC).copy(
        baseUrl = server.url("/v1").toString(),
        model = "claude-3-5-sonnet-latest",
        allowInsecureHttp = true,
        connectTimeoutMillis = 5_000,
        readTimeoutMillis = 5_000,
        totalTimeoutMillis = 15_000,
    )

    private fun request() = ChatRequest.ofUserText("你好")

    private fun credential() = ProviderCredential.of(FAKE_API_KEY)

    @Test
    fun sendsAnthropicRequestAndStreamsNamedEvents() = runBlocking {
        server.enqueue(
            sse(
                eventStreamOf(
                    "message_start" to
                        "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"usage\":{\"input_tokens\":7}}}",
                    "content_block_start" to
                        "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}",
                    "content_block_delta" to
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"你好\"}}",
                    "ping" to "{\"type\":\"ping\"}",
                    "content_block_delta" to
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"世界\"}}",
                    "content_block_stop" to "{\"type\":\"content_block_stop\",\"index\":0}",
                    "message_delta" to
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},\"usage\":{\"output_tokens\":9}}",
                    "message_stop" to "{\"type\":\"message_stop\"}",
                )
            )
        )

        val events = provider()
            .stream(
                request().copy(system = "你是简洁的助手", maxOutputTokens = 512),
                config(),
                credential(),
            )
            .toList()

        assertEquals(
            "事件必须满足 ProviderEventContract",
            emptyList<String>(),
            ProviderEventContract.validate(events),
        )
        assertEquals("你好世界", ProviderEventContract.textOf(events))
        val completed = events.last() as ProviderEvent.Completed
        assertEquals(FinishReason.STOP, completed.finishReason)
        assertEquals(7, completed.usage?.inputTokens)
        assertEquals(9, completed.usage?.outputTokens)
        assertEquals("一次成功流只发一个请求", 1, server.requestCount)

        val recorded = server.takeRequest()
        assertEquals("baseUrl 已含 /v1，相对路径不得重复 /v1", "/v1/messages", recorded.target)
        assertEquals("Anthropic 的凭据走 x-api-key", FAKE_API_KEY, recorded.headers["x-api-key"])
        assertEquals("2023-06-01", recorded.headers["anthropic-version"])
        assertTrue(
            "Content-Type 必须是 application/json（OkHttp 会补 charset）",
            recorded.headers["Content-Type"]?.startsWith("application/json") == true,
        )
        assertEquals("text/event-stream", recorded.headers["Accept"])
        assertNull("Anthropic 不得使用 Authorization: Bearer", recorded.headers["Authorization"])

        val payload = Json.parseToJsonElement(recorded.body!!.utf8()).jsonObject
        assertEquals("claude-3-5-sonnet-latest", payload["model"]!!.jsonPrimitive.content)
        assertEquals(512, payload["max_tokens"]!!.jsonPrimitive.int)
        assertTrue(payload["stream"]!!.jsonPrimitive.boolean)
        assertEquals("system 必须是顶层字段", "你是简洁的助手", payload["system"]!!.jsonPrimitive.content)
        val messages = payload["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("system 提示词不得混进 messages", "user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("你好", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun defaultMaxTokensIsSentAndSystemIsOmittedWhenAbsent() = runBlocking {
        server.enqueue(sse(eventStreamOf("message_stop" to "{\"type\":\"message_stop\"}")))

        provider().stream(request(), config(), credential()).toList()

        val payload = Json.parseToJsonElement(server.takeRequest().body!!.utf8()).jsonObject
        assertEquals("max_tokens 有默认值", 1024, payload["max_tokens"]!!.jsonPrimitive.int)
        assertNull("没有 system 时不得发送 system 字段", payload["system"])
    }

    @Test
    fun maxTokensStopReasonBecomesLength() = runBlocking {
        server.enqueue(
            sse(
                eventStreamOf(
                    "content_block_delta" to
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"被截断\"}}",
                    "message_delta" to
                        "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"max_tokens\"},\"usage\":{\"output_tokens\":3}}",
                    "message_stop" to "{\"type\":\"message_stop\"}",
                )
            )
        )

        val events = provider().stream(request(), config(), credential()).toList()

        val completed = events.last() as ProviderEvent.Completed
        assertEquals(FinishReason.LENGTH, completed.finishReason)
        assertEquals("被截断", ProviderEventContract.textOf(events))
    }

    @Test
    fun streamedErrorEventIsMappedAndRetriedWithinTheLimit() = runBlocking {
        repeat(3) {
            server.enqueue(
                sse(
                    eventStreamOf(
                        "error" to
                            "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}",
                    )
                )
            )
        }

        val events = provider().stream(request(), config(), credential()).toList()

        val failure = events.last() as ProviderEvent.Failed
        assertEquals(ProviderErrorKind.SERVER_ERROR, failure.error.kind)
        assertEquals("1 次原始请求 + 2 次重试", 3, server.requestCount)
    }

    @Test
    fun unknownEventsAndFieldsAreIgnored() = runBlocking {
        server.enqueue(
            sse(
                eventStreamOf(
                    "some_future_event" to "{\"type\":\"some_future_event\",\"surprise\":{\"a\":1}}",
                    "content_block_delta" to
                        "{\"type\":\"content_block_delta\",\"index\":0,\"unknown\":\"x\",\"delta\":{\"type\":\"text_delta\",\"text\":\"好\"}}",
                    "message_stop" to "{\"type\":\"message_stop\",\"extra\":true}",
                )
            )
        )

        val events = provider().stream(request(), config(), credential()).toList()

        assertEquals("好", ProviderEventContract.textOf(events))
        assertTrue(events.last() is ProviderEvent.Completed)
        assertEquals(emptyList<String>(), ProviderEventContract.validate(events))
    }

    @Test
    fun thinkingDeltasAreNotTurnedIntoAnswers() = runBlocking {
        server.enqueue(
            sse(
                eventStreamOf(
                    "content_block_delta" to
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"内部推理\"}}",
                    "content_block_delta" to
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"答案\"}}",
                    "message_stop" to "{\"type\":\"message_stop\"}",
                )
            )
        )

        val events = provider().stream(request(), config(), credential()).toList()

        assertEquals("推理增量不得当答案输出", "答案", ProviderEventContract.textOf(events))
    }

    @Test
    fun streamWithoutMessageStopIsAProtocolError() = runBlocking {
        server.enqueue(
            sse(
                eventStreamOf(
                    "content_block_delta" to
                        "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"半截\"}}",
                )
            )
        )

        val events = provider().stream(request(), config(), credential()).toList()

        val failure = events.last() as ProviderEvent.Failed
        assertEquals(ProviderErrorKind.PROTOCOL, failure.error.kind)
        assertEquals("半截", ProviderEventContract.textOf(events))
        assertEquals("流不完整不得重试", 1, server.requestCount)
    }

    @Test
    fun unauthorizedIsNotRetried() = runBlocking {
        server.enqueue(
            MockResponse(
                401,
                Headers.headersOf("Content-Type", "application/json"),
                "{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}",
            )
        )

        val events = provider().stream(request(), config(), credential()).toList()

        val failure = events.last() as ProviderEvent.Failed
        assertEquals(ProviderErrorKind.UNAUTHORIZED, failure.error.kind)
        assertEquals(1, server.requestCount)
        assertTrue(failure.error.detail.orEmpty().contains("authentication_error"))
    }

    @Test
    fun messageStopOnlyEmptyDeltaAndWhitespaceDeltaAreEmptyResponses() = runBlocking {
        for (text in listOf<String?>(null, "", " \\n\\t")) {
            val before = server.requestCount
            val delta = if (text == null) "" else eventStreamOf("content_block_delta" to
                "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"$text\"}}")
            server.enqueue(sse(delta + eventStreamOf("message_stop" to "{\"type\":\"message_stop\"}")))
            val events = provider().stream(request(), config(), credential()).toList()
            assertEquals(ProviderErrorKind.EMPTY_RESPONSE, (events.last() as ProviderEvent.Failed).error.kind)
            assertEquals(1, events.count { it is ProviderEvent.Failed || it is ProviderEvent.Completed })
            assertTrue(ProviderEventContract.validate(events).isEmpty())
            assertEquals(before + 1, server.requestCount)
        }
    }

    @Test
    fun invalidJsonHasAFixedSafeProtocolDetail() = runBlocking {
        assertSafeProtocolFailure(eventStreamOf("content_block_delta" to
            ("invalid-json private-response-body " + FAKE_API_KEY)), expectedDetail = "流式 JSON 数据格式无效")
    }

    @Test
    fun truncatedJsonAtEndOfStreamHasAFixedSafeProtocolDetail() = runBlocking {
        assertSafeProtocolFailure("event: content_block_delta\ndata: {\"private-response-body\":\"" + FAKE_API_KEY +
            "\",\"delta\":{", expectedDetail = "流式 JSON 数据格式无效")
    }

    @Test
    fun wrongFieldTypesFailInsteadOfBeingIgnoredOrTurnedIntoText() = runBlocking {
        for ((name, payload) in listOf(
            "message_stop" to "[\"$FAKE_API_KEY\"]",
            "message_stop" to "{\"type\":42,\"private-response-body\":\"$FAKE_API_KEY\"}",
            "content_block_delta" to "{\"delta\":\"$FAKE_API_KEY\"}",
            "content_block_delta" to "{\"delta\":{\"type\":42,\"text\":\"$FAKE_API_KEY\"}}",
            "content_block_delta" to "{\"delta\":{\"type\":\"text_delta\",\"text\":[\"$FAKE_API_KEY\"]}}",
            "content_block_start" to "{\"content_block\":{\"type\":\"text\",\"text\":[\"$FAKE_API_KEY\"]}}",
            "message_start" to "{\"message\":{\"usage\":{\"input_tokens\":\"$FAKE_API_KEY\"}}}",
            "message_start" to "{\"message\":{\"usage\":{\"input_tokens\":\"3\"}}}",
            "message_delta" to "{\"delta\":{},\"usage\":{\"output_tokens\":false}}",
            "message_delta" to "{\"delta\":{\"stop_reason\":[\"$FAKE_API_KEY\"]}}",
            "message_delta" to "{\"delta\":{},\"usage\":\"$FAKE_API_KEY\"}",
            "error" to "{\"error\":[\"$FAKE_API_KEY\"]}",
        )) assertSafeProtocolFailure(eventStreamOf(name to payload))
    }

    @Test
    fun toolsAndMultimodalBlocksRemainUnsupported() = runBlocking {
        for ((name, payload) in listOf(
            "content_block_start" to "{\"content_block\":{\"type\":\"tool_use\",\"name\":\"local_tool\"}}",
            "content_block_delta" to "{\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":\"{}\"}}",
            "content_block_start" to "{\"content_block\":{\"type\":\"image\",\"url\":\"private-response-body\"}}",
        )) assertSafeProtocolFailure(eventStreamOf(name to payload))
    }

    @Test
    fun toolUseStopReasonIsNotACompletedTextAnswer() = runBlocking {
        server.enqueue(sse(eventStreamOf(
            "content_block_delta" to "{\"delta\":{\"type\":\"text_delta\",\"text\":\"部分答案\"}}",
            "message_delta" to "{\"delta\":{\"stop_reason\":\"tool_use\"}}",
            "message_stop" to "{\"type\":\"message_stop\"}",
        )))
        val events = provider().stream(request(), config(), credential()).toList()
        assertEquals("部分答案", ProviderEventContract.textOf(events))
        assertEquals(ProviderErrorKind.PROTOCOL, (events.last() as ProviderEvent.Failed).error.kind)
        assertEquals(1, events.count { it is ProviderEvent.Failed || it is ProviderEvent.Completed })
        assertEquals(1, server.requestCount)
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
