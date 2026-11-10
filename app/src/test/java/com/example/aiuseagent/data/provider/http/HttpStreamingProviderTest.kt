package com.example.aiuseagent.data.provider.http

import com.example.aiuseagent.data.provider.sse.SseEvent
import com.example.aiuseagent.data.provider.sse.SseParser
import com.example.aiuseagent.domain.provider.ChatRequest
import com.example.aiuseagent.domain.provider.FinishReason
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderCredential
import com.example.aiuseagent.domain.provider.ProviderError
import com.example.aiuseagent.domain.provider.ProviderErrorKind
import com.example.aiuseagent.domain.provider.ProviderEvent
import com.example.aiuseagent.domain.provider.ProviderEventContract
import com.example.aiuseagent.domain.provider.ProviderType
import com.example.aiuseagent.domain.provider.isTerminal
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.SerializationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * P2-B §6/§7.3：传输无关的调度逻辑（错误映射、限次重试、取消、响应关闭）。
 *
 * 这里用假传输层，完全不碰网络，所以能精确断言「一共发了多少次请求」。
 * 真实 HTTP 行为在 OkHttpTransportTest 里用 MockWebServer 验证。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HttpStreamingProviderTest {

    private val apiKey = "test-key-not-secret"
    private val request = ChatRequest.ofUserText("你好")
    private val credential = ProviderCredential.of(apiKey)

    private fun config(
        baseUrl: String = "https://api.example.invalid/v1",
        model: String = "test-model",
        allowInsecureHttp: Boolean = false,
    ): ProviderConfig = ProviderConfig.forType(ProviderType.OPENAI_COMPATIBLE).copy(
        baseUrl = baseUrl,
        model = model,
        allowInsecureHttp = allowInsecureHttp,
        connectTimeoutMillis = 1_000,
        readTimeoutMillis = 2_000,
        totalTimeoutMillis = 3_000,
    )

    private val doneStream = "data: a\n\ndata: b\n\ndata: [DONE]\n\n"

    private fun failureOf(events: List<ProviderEvent>): ProviderError {
        val failed = events.last()
        assertTrue("最后一个事件必须是 Failed，实际：" + events.map { it::class.simpleName }, failed is ProviderEvent.Failed)
        return (failed as ProviderEvent.Failed).error
    }

    @Test
    fun stream_sendsOneRequestAndSatisfiesTheEventContract() = runTest {
        val response = FakeResponse(chunks = listOf(doneStream), requestId = "req-1")
        val transport = FakeTransport { response }

        val events = TestProvider(transport).stream(request, config(), credential).toList()

        assertEquals(1, transport.requests.size)
        assertEquals(1, response.closeCount)
        assertEquals("https://api.example.invalid/v1/chat/completions", transport.requests[0].url)
        assertEquals(
            "Bearer " + apiKey,
            transport.requests[0].headers.first { it.name == "Authorization" }.value,
        )
        assertEquals("{\"model\":\"test-model\"}", transport.requests[0].body)
        assertEquals(HttpTimeouts(1_000, 2_000, 3_000), transport.timeouts[0])
        assertTrue(ProviderEventContract.validate(events).isEmpty())
        assertEquals("ab", ProviderEventContract.textOf(events))
        assertEquals(FinishReason.STOP, (events.last() as ProviderEvent.Completed).finishReason)
    }

    @Test
    fun stream_isColdAndEachCollectionSendsItsOwnRequest() = runTest {
        val transport = FakeTransport { FakeResponse(chunks = listOf(doneStream)) }
        val flow = TestProvider(transport).stream(request, config(), credential)

        assertEquals("冷流：没有收集前不得发请求", 0, transport.requests.size)

        flow.toList()
        flow.toList()

        assertEquals("重复收集各自发一次请求", 2, transport.requests.size)
    }

    @Test
    fun unauthorizedIsNotRetried() = runTest {
        val echoed = "sk-echoed-secret"
        val transport = FakeTransport {
            FakeResponse(
                status = 401,
                errorBody = "{\"error\":{\"type\":\"invalid_api_key\",\"message\":\"key " + echoed + " is wrong\"}}",
                requestId = "req-401",
            )
        }

        val events = TestProvider(transport).stream(request, config(), credential).toList()
        val error = failureOf(events)

        assertEquals(ProviderErrorKind.UNAUTHORIZED, error.kind)
        assertEquals(401, error.httpStatus)
        assertEquals("req-401", error.requestId)
        assertTrue("必须带上机器可读标记", error.detail!!.contains("invalid_api_key"))
        assertFalse("错误正文原文不得进入错误信息", error.detail!!.contains(echoed))
        assertEquals("401 不得重试", 1, transport.requests.size)
        assertTrue("契约仍然成立", ProviderEventContract.validate(events).isEmpty())
    }

    @Test
    fun rateLimitedIsRetriedTwiceAndThenFails() = runTest {
        val delays = mutableListOf<Long>()
        val responses = mutableListOf<FakeResponse>()
        val transport = FakeTransport {
            FakeResponse(
                status = 429,
                retryAfterMillis = 1_500L,
                errorBody = "{\"error\":{\"type\":\"rate_limit_exceeded\"}}",
            ).also { responses += it }
        }

        val events = TestProvider(transport, backoff = { delays += it })
            .stream(request, config(), credential)
            .toList()

        assertEquals("首次 + 2 次额外尝试", 3, transport.requests.size)
        assertTrue("每次都必须关闭响应", responses.all { it.closeCount == 1 })
        assertEquals("Retry-After 必须被采纳", listOf(1_500L, 1_500L), delays)
        assertEquals(ProviderErrorKind.RATE_LIMITED, failureOf(events).kind)
        assertTrue(ProviderEventContract.validate(events).isEmpty())
    }

    @Test
    fun serverErrorIsRetriedAndTheThirdAttemptSucceeds() = runTest {
        val transport = FakeTransport { attempt ->
            if (attempt < 3) {
                FakeResponse(status = 503)
            } else {
                FakeResponse(chunks = listOf("data: ok\n\ndata: [DONE]\n\n"))
            }
        }

        val events = TestProvider(transport, backoff = { }).stream(request, config(), credential).toList()

        assertEquals(3, transport.requests.size)
        assertTrue(events.last() is ProviderEvent.Completed)
        assertTrue("中间失败不得产生终态事件", events.none { it is ProviderEvent.Failed })
        assertEquals("ok", ProviderEventContract.textOf(events))
    }

    @Test
    fun networkAndTimeoutFailuresAreMappedAndRetried() = runTest {
        val networkTransport = FakeTransport { throw IOException("connection reset") }
        val networkEvents = TestProvider(networkTransport, backoff = { })
            .stream(request, config(), credential)
            .toList()

        assertEquals(ProviderErrorKind.NETWORK, failureOf(networkEvents).kind)
        assertEquals(3, networkTransport.requests.size)

        val timeoutTransport = FakeTransport { throw SocketTimeoutException("read timed out") }
        val timeoutEvents = TestProvider(timeoutTransport, backoff = { })
            .stream(request, config(), credential)
            .toList()

        assertEquals(ProviderErrorKind.TIMEOUT, failureOf(timeoutEvents).kind)
        assertEquals(3, timeoutTransport.requests.size)
    }

    @Test
    fun aRetryableFailureAfterTheFirstDeltaIsNotReplayed() = runTest {
        val decoder = object : SseStreamDecoder {
            override fun onEvent(event: SseEvent): List<SseAction> = when (event.data) {
                "boom" -> listOf(
                    SseAction.Error(ProviderError(ProviderErrorKind.SERVER_ERROR, detail = "服务端错误")),
                )
                else -> listOf(SseAction.Delta(event.data))
            }
        }
        val transport = FakeTransport { FakeResponse(chunks = listOf("data: hello\n\ndata: boom\n\n")) }

        val events = TestProvider(transport, decoderFactory = { decoder })
            .stream(request, config(), credential)
            .toList()

        assertEquals("已经发出过文本增量后不得重放请求", 1, transport.requests.size)
        assertEquals("hello", ProviderEventContract.textOf(events))
        assertEquals(ProviderErrorKind.SERVER_ERROR, failureOf(events).kind)
    }

    @Test
    fun anEmptyTwoHundredIsReportedAsEmptyResponse() = runTest {
        val transport = FakeTransport { FakeResponse(status = 200, chunks = emptyList()) }

        val events = TestProvider(transport).stream(request, config(), credential).toList()

        assertEquals(ProviderErrorKind.EMPTY_RESPONSE, failureOf(events).kind)
        assertEquals(1, transport.requests.size)
    }

    @Test
    fun aStreamThatEndsBeforeTheTerminatorIsAProtocolError() = runTest {
        val transport = FakeTransport { FakeResponse(chunks = listOf("data: half\n\n")) }

        val events = TestProvider(transport).stream(request, config(), credential).toList()
        val error = failureOf(events)

        assertEquals(ProviderErrorKind.PROTOCOL, error.kind)
        assertTrue("必须说明流被截断", error.detail!!.contains("终止标记"))
        assertEquals("协议错误不重试", 1, transport.requests.size)
    }

    @Test
    fun missingCredentialSendsNoRequest() = runTest {
        val transport = FakeTransport { FakeResponse(chunks = listOf(doneStream)) }

        val events = TestProvider(transport)
            .stream(request, config(), ProviderCredential.Absent)
            .toList()

        assertEquals(ProviderErrorKind.MISSING_API_KEY, failureOf(events).kind)
        assertEquals("没有 Key 时不得发出请求", 0, transport.requests.size)
    }

    @Test
    fun invalidConfigSendsNoRequest() = runTest {
        val transport = FakeTransport { FakeResponse(chunks = listOf(doneStream)) }

        val events = TestProvider(transport).stream(request, config(model = ""), credential).toList()

        assertEquals(ProviderErrorKind.INVALID_CONFIG, failureOf(events).kind)
        assertEquals(0, transport.requests.size)
    }

    @Test
    fun plainHttpIsRejectedUnlessExplicitlyAllowed() = runTest {
        val blocked = FakeTransport { FakeResponse(chunks = listOf(doneStream)) }
        val blockedEvents = TestProvider(blocked)
            .stream(request, config(baseUrl = "http://127.0.0.1:8080/v1"), credential)
            .toList()

        assertEquals(ProviderErrorKind.INVALID_CONFIG, failureOf(blockedEvents).kind)
        assertTrue("必须说明拒绝明文 HTTP", failureOf(blockedEvents).detail!!.contains("明文"))
        assertEquals("被拒绝的请求不得发出", 0, blocked.requests.size)

        val allowed = FakeTransport { FakeResponse(chunks = listOf(doneStream)) }
        val allowedEvents = TestProvider(allowed)
            .stream(
                request,
                config(baseUrl = "http://127.0.0.1:8080/v1", allowInsecureHttp = true),
                credential,
            )
            .toList()

        assertEquals(1, allowed.requests.size)
        assertEquals("http://127.0.0.1:8080/v1/chat/completions", allowed.requests[0].url)
        assertTrue(allowedEvents.last() is ProviderEvent.Completed)
    }

    @Test
    fun diagnosticsRecordRetriesButNeverTheSecret() = runTest {
        val diagnostics = ProviderDiagnostics.Recording()
        val transport = FakeTransport {
            FakeResponse(status = 500, errorBody = "{\"error\":{\"type\":\"" + apiKey + "\"}}")
        }

        TestProvider(transport, diagnostics = diagnostics, backoff = { })
            .stream(request, config(), credential)
            .toList()

        // 记录类型本身就是白名单：没有 headers / 请求体 / 响应体字段可以承载秘密
        val records = diagnostics.snapshot()
        assertTrue("必须有诊断记录", records.isNotEmpty())
        assertEquals("3 次尝试各记录一条失败", 3, records.count { it.event == "stream_failed" })
        assertEquals("2 次重试各记录一条调度", 2, records.count { it.event == "retry_scheduled" })
        assertTrue(
            "失败记录必须带错误类别与状态码",
            records.filter { it.event == "stream_failed" }.all {
                it.errorKind == ProviderErrorKind.SERVER_ERROR.name && it.httpStatus == 500
            },
        )
        val everything = records.joinToString("|") { it.toString() }
        assertTrue("诊断里不得出现 API Key", !everything.contains(apiKey))
        assertTrue("诊断里不得出现请求头名称", !everything.contains("Authorization"))

        // 解码器给出的 Note 也必须先脱敏再落进诊断
        val leaking = ProviderDiagnostics.Recording()
        val noteTransport = FakeTransport { FakeResponse(chunks = listOf("data: x\n\n")) }
        TestProvider(
            noteTransport,
            diagnostics = leaking,
            backoff = { },
            decoderFactory = {
                object : SseStreamDecoder {
                    override fun onEvent(event: SseEvent): List<SseAction> = if (event.data == "[DONE]") {
                        listOf(SseAction.Terminator)
                    } else {
                        listOf(SseAction.Note("供应商回显 " + apiKey + " 结束"))
                    }
                }
            },
        ).stream(request, config(), credential).toList()

        val notes = leaking.notes()
        assertEquals("Note 必须落成一条诊断记录", 1, notes.size)
        assertTrue("NOTE-KEEPS-KEY|" + notes[0], !notes[0].contains(apiKey))
        assertTrue("NOTE-KEEPS-MASK|" + notes[0], notes[0].contains("?"))
        assertTrue("NOTE-TOO-LONG|" + notes[0], notes[0].length <= ProviderLogRecord.MAX_NOTE_LENGTH)
    }

    @Test
    fun cancellingTheCollectorClosesTheResponseAndEmitsNoTerminalEvent() = runTest {
        val response = FakeResponse(blockForever = true)
        val transport = FakeTransport { response }
        val received = mutableListOf<ProviderEvent>()

        val job = launch {
            TestProvider(transport).stream(request, config(), credential).collect { received += it }
        }
        runCurrent()

        assertEquals("此时只应收到 Started", 1, received.size)
        assertTrue(received.first() is ProviderEvent.Started)

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals("取消后不得再产生事件", 1, received.size)
        assertTrue("取消不产生终态事件", received.none { it.isTerminal })
        assertEquals("取消后不得重试", 1, transport.requests.size)
        assertEquals("取消必须关闭底层响应", 1, response.closeCount)
    }

    @Test
    fun aTerminatorWithoutNonBlankTextIsAnEmptyResponseAndHasOneTerminal() = runTest {
        for (stream in listOf("data: [DONE]\n\n", "data:  \n\ndata: \t\n\ndata: [DONE]\n\n")) {
            val response = FakeResponse(chunks = listOf(stream))
            val transport = FakeTransport { response }
            val events = TestProvider(transport).stream(request, config(), credential).toList()
            assertEquals(ProviderErrorKind.EMPTY_RESPONSE, failureOf(events).kind)
            assertEquals(1, events.count { it.isTerminal })
            assertTrue(ProviderEventContract.validate(events).isEmpty())
            assertEquals("空响应不可重试", 1, transport.requests.size)
            assertEquals(1, response.closeCount)
        }
    }

    @Test
    fun malformedJsonDecoderFailureHasFixedDetailAndDoesNotLeakTheBody() = runTest {
        val response = FakeResponse(chunks = listOf("data: broken\n\n"))
        val transport = FakeTransport { response }
        val diagnostics = ProviderDiagnostics.Recording()
        val decoder = object : SseStreamDecoder {
            override fun onEvent(event: SseEvent): List<SseAction> =
                throw SerializationException("private-response-body " + apiKey)
        }
        val events = TestProvider(transport, diagnostics = diagnostics, decoderFactory = { decoder })
            .stream(request, config(), credential).toList()
        val error = failureOf(events)
        assertEquals(ProviderErrorKind.PROTOCOL, error.kind)
        assertEquals("流式 JSON 数据格式无效", error.detail)
        val output = error.toString() + diagnostics.snapshot().joinToString()
        assertFalse(output.contains(apiKey))
        assertFalse(output.contains("private-response-body"))
        assertEquals(1, events.count { it.isTerminal })
        assertEquals(1, transport.requests.size)
        assertEquals(1, response.closeCount)
    }

    @Test
    fun decoderCancellationPropagatesWithoutAFailureOrRetryAndClosesTheResponse() = runTest {
        val cancellation = CancellationException("解析期间取消")
        val response = FakeResponse(chunks = listOf("data: x\n\n"))
        val transport = FakeTransport { response }
        val received = mutableListOf<ProviderEvent>()
        val decoder = object : SseStreamDecoder {
            override fun onEvent(event: SseEvent): List<SseAction> = throw cancellation
        }
        try {
            TestProvider(transport, decoderFactory = { decoder })
                .stream(request, config(), credential).collect { received += it }
            fail("CancellationException 必须继续传播")
        } catch (expected: CancellationException) {
            assertSame(cancellation, expected)
        }
        assertEquals(1, received.size)
        assertTrue(received.single() is ProviderEvent.Started)
        assertEquals(1, transport.requests.size)
        assertEquals(1, response.closeCount)
    }

    @Test
    fun oversizedUnterminatedSseLineFailsAsProtocolWithoutRetryAndClosesTheResponse() = runTest {
        val response = FakeResponse(chunks = listOf("data: " + "x".repeat(SseParser.DEFAULT_MAX_LINE_BYTES)))
        val transport = FakeTransport { response }
        val events = TestProvider(transport).stream(request, config(), credential).toList()
        val error = failureOf(events)
        assertEquals(ProviderErrorKind.PROTOCOL, error.kind)
        assertTrue(error.detail!!.contains("字节长度上限"))
        assertEquals(1, events.count { it.isTerminal })
        assertEquals("资源超限不得重试", 1, transport.requests.size)
        assertEquals(1, response.closeCount)
    }

    @Test
    fun collectorSerializationExceptionIsNotMisclassifiedAsAProtocolFailure() = runTest {
        val collectorFailure = SerializationException("下游收集器失败")
        val response = FakeResponse(chunks = listOf(doneStream))
        val transport = FakeTransport { response }
        val received = mutableListOf<ProviderEvent>()
        try {
            TestProvider(transport).stream(request, config(), credential).collect { event ->
                received += event
                if (event is ProviderEvent.TextDelta) throw collectorFailure
            }
            fail("下游异常必须保持 Flow 异常透明性")
        } catch (expected: SerializationException) {
            assertSame(collectorFailure, expected)
        }
        assertTrue(received.none { it.isTerminal })
        assertEquals(1, transport.requests.size)
        assertEquals(1, response.closeCount)
    }
}

/** 可编造的传输层：完全不联网，只按预设返回响应，并记录每次请求。 */
private class FakeTransport(private val responder: (Int) -> StreamingHttpResponse) : HttpTransport {

    val requests = mutableListOf<ProviderHttpRequest>()
    val timeouts = mutableListOf<HttpTimeouts>()

    override suspend fun open(request: ProviderHttpRequest, timeouts: HttpTimeouts): StreamingHttpResponse {
        requests += request
        this.timeouts += timeouts
        return responder(requests.size)
    }
}

/** 可编造的流式响应：按块吐出字节，可选「永远不结束」用来测取消。 */
private class FakeResponse(
    override val status: Int = 200,
    private val chunks: List<String> = emptyList(),
    override val requestId: String? = null,
    override val retryAfterMillis: Long? = null,
    private val errorBody: String? = null,
    private val blockForever: Boolean = false,
) : StreamingHttpResponse {

    var closeCount = 0
        private set

    private var index = 0

    override suspend fun readChunk(): ByteArray? {
        if (blockForever) awaitCancellation()
        return if (index < chunks.size) chunks[index++].toByteArray(Charsets.UTF_8) else null
    }

    override suspend fun readErrorBodyText(): String? = errorBody

    override fun close() {
        closeCount++
    }
}

/** 测试用的透传解码器：data 直接当文本增量，[DONE] 当终止标记。 */
private val passThroughDecoder: SseStreamDecoder = object : SseStreamDecoder {
    override fun onEvent(event: SseEvent): List<SseAction> = when {
        event.data == "[DONE]" -> listOf(SseAction.Terminator)
        event.data.isEmpty() -> listOf(SseAction.Ignored)
        else -> listOf(SseAction.Delta(event.data))
    }
}

/** 只用于测试基类调度逻辑的最小适配器。 */
private class TestProvider(
    transport: HttpTransport,
    retryPolicy: RetryPolicy = RetryPolicy(),
    diagnostics: ProviderDiagnostics = ProviderDiagnostics.NoOp,
    backoff: suspend (Long) -> Unit = { },
    private val decoderFactory: () -> SseStreamDecoder = { passThroughDecoder },
) : HttpStreamingProvider(ProviderType.OPENAI_COMPATIBLE, transport, retryPolicy, diagnostics, backoff) {

    override val relativePath: String = "chat/completions"

    override fun buildRequest(
        request: ChatRequest,
        config: ProviderConfig,
        credential: ProviderCredential,
    ): ProviderHttpRequest = ProviderHttpRequest(
        url = config.resolvePath(relativePath),
        headers = listOf(
            ProviderHeader("Authorization", "Bearer " + credential.reveal().orEmpty()),
            ProviderHeader("Content-Type", "application/json"),
        ),
        body = "{\"model\":\"" + config.model + "\"}",
        allowInsecureHttp = config.allowInsecureHttp,
    )

    override fun newDecoder(): SseStreamDecoder = decoderFactory()
}
