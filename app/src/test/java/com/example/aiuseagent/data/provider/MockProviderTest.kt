package com.example.aiuseagent.data.provider

import com.example.aiuseagent.data.provider.mock.MockProvider
import com.example.aiuseagent.data.provider.mock.MockScript
import com.example.aiuseagent.domain.provider.ChatRequest
import com.example.aiuseagent.domain.provider.FinishReason
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderCredential
import com.example.aiuseagent.domain.provider.ProviderError
import com.example.aiuseagent.domain.provider.ProviderErrorKind
import com.example.aiuseagent.domain.provider.ProviderEvent
import com.example.aiuseagent.domain.provider.ProviderEventContract
import com.example.aiuseagent.domain.provider.ProviderType
import com.example.aiuseagent.domain.provider.TokenUsage
import com.example.aiuseagent.domain.provider.isTerminal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mock Provider 的行为契约。它不联网、不需要 Key、不需要设备，
 * 因此「事件序列、失败终态、取消」这些最容易出错的地方必须在 JVM 上被钉死。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MockProviderTest {

    private val request = ChatRequest.ofUserText("你好")
    private val config = ProviderConfig.forType(ProviderType.MOCK)
    private val credential = ProviderCredential.Absent

    @Test
    fun stream_emitsStartedDeltasThenCompletedAndSatisfiesContract() = runTest {
        val provider = MockProvider(MockScript.normal(listOf("A", "B", "C")))
        val events = provider.stream(request, config, credential).toList()

        assertEquals("必须恰好 5 个事件（Started + 3 个 TextDelta + Completed）", 5, events.size)
        assertTrue(events.first() is ProviderEvent.Started)
        assertEquals(ProviderType.MOCK, (events.first() as ProviderEvent.Started).providerType)
        assertEquals("mock-echo", (events.first() as ProviderEvent.Started).model)
        assertEquals(3, events.count { it is ProviderEvent.TextDelta })
        assertEquals("ABC", ProviderEventContract.textOf(events))
        assertTrue(events.last() is ProviderEvent.Completed)
        assertTrue(
            "事件序列必须满足契约，实际问题：" + ProviderEventContract.validate(events),
            ProviderEventContract.validate(events).isEmpty(),
        )
    }

    @Test
    fun stream_carriesFinishReasonAndUsage() = runTest {
        val script = MockScript.normal(listOf("x")).copy(
            finishReason = FinishReason.LENGTH,
            usage = TokenUsage(inputTokens = 11, outputTokens = 3),
        )
        val events = MockProvider(script).stream(request, config, credential).toList()
        val completed = events.last() as ProviderEvent.Completed
        assertEquals(FinishReason.LENGTH, completed.finishReason)
        assertEquals(11, completed.usage!!.inputTokens)
        assertEquals(3, completed.usage!!.outputTokens)
    }

    @Test
    fun stream_failsAfterRequestedChunkCountWithoutCompletion() = runTest {
        val error = ProviderError.of(ProviderErrorKind.NETWORK, ProviderType.MOCK, detail = "connection reset")
        val provider = MockProvider(MockScript.failing(error, afterChunks = 2))
        val events = provider.stream(request, config, credential).toList()

        assertEquals(2, events.count { it is ProviderEvent.TextDelta })
        assertTrue("失败流不得出现 Completed", events.none { it is ProviderEvent.Completed })
        val failed = events.last() as ProviderEvent.Failed
        assertEquals(ProviderErrorKind.NETWORK, failed.error.kind)
        assertTrue(ProviderEventContract.validate(events).isEmpty())
    }

    @Test
    fun stream_failsImmediatelyWhenFailAfterChunksIsNull() = runTest {
        val error = ProviderError.of(ProviderErrorKind.RATE_LIMITED, ProviderType.MOCK, httpStatus = 429)
        val provider = MockProvider(MockScript(failure = error))
        val events = provider.stream(request, config, credential).toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is ProviderEvent.Started)
        val failed = events[1] as ProviderEvent.Failed
        assertEquals(ProviderErrorKind.RATE_LIMITED, failed.error.kind)
        assertTrue(failed.error.retryable)
    }

    @Test
    fun stream_redactsScriptedErrorFieldsBeforeEmittingFailure() = runTest {
        val fakeKey = "test-mock-credential-not-real"
        val original = ProviderError(
            kind = ProviderErrorKind.UNAUTHORIZED,
            providerType = ProviderType.MOCK,
            detail = "script echoed " + fakeKey,
            requestId = "req-" + fakeKey,
        )
        val events = MockProvider(MockScript.failing(original))
            .stream(request, config, ProviderCredential.of(fakeKey)).toList()
        val failed = (events.last() as ProviderEvent.Failed).error

        assertEquals(ProviderErrorKind.UNAUTHORIZED, failed.kind)
        assertEquals(null, failed.requestId)
        assertFalse(failed.detail!!.contains(fakeKey))
        assertFalse(failed.toString().contains(fakeKey))
        assertFalse(failed.userMessage.contains(fakeKey))
        assertTrue("脚本输入不能被就地改写", original.detail!!.contains(fakeKey))
        assertTrue(ProviderEventContract.validate(events).isEmpty())
    }

    @Test
    fun stream_redactsScriptedRequestIdWhenDetailIsNull() = runTest {
        val fakeKey = "test-mock-id-credential-not-real"
        val original = ProviderError(ProviderErrorKind.NETWORK, requestId = fakeKey)
        val events = MockProvider(MockScript.failing(original))
            .stream(request, config, ProviderCredential.of(fakeKey)).toList()
        val failed = (events.last() as ProviderEvent.Failed).error

        assertEquals(null, failed.detail)
        assertEquals(null, failed.requestId)
        assertFalse(failed.toString().contains(fakeKey))
        assertTrue(ProviderEventContract.validate(events).isEmpty())
    }

    @Test
    fun stream_redactsPreflightErrorUsingActualCredential() = runTest {
        // 不依赖常见 Key 前缀：即便凭据碰巧出现在校验文案中，仍要先脱敏再发射。
        val fakeKey = "不能为空"
        val events = MockProvider().stream(request, config.copy(model = ""), ProviderCredential.of(fakeKey)).toList()
        val failed = (events.last() as ProviderEvent.Failed).error

        assertEquals(ProviderErrorKind.INVALID_CONFIG, failed.kind)
        assertFalse(failed.detail!!.contains(fakeKey))
        assertFalse(failed.toString().contains(fakeKey))
        assertTrue(events.none { it is ProviderEvent.TextDelta })
    }

    @Test
    fun stream_reportsEmptyResponseAsFailure() = runTest {
        val provider = MockProvider(MockScript.normal(listOf("A")).copy(emptyResponse = true))
        val events = provider.stream(request, config, credential).toList()

        assertTrue("空响应不得被当成成功", events.none { it is ProviderEvent.Completed })
        assertEquals(ProviderErrorKind.EMPTY_RESPONSE, (events.last() as ProviderEvent.Failed).error.kind)
        assertTrue(ProviderEventContract.validate(events).isEmpty())
    }

    @Test
    fun stream_reportsMissingKeyWhenScriptRequiresKey() = runTest {
        val provider = MockProvider(MockScript.normal(listOf("A")).copy(requiresKey = true))
        val events = provider.stream(request, config, credential).toList()

        assertEquals(ProviderErrorKind.MISSING_API_KEY, (events.last() as ProviderEvent.Failed).error.kind)
        assertTrue("缺 Key 时不得吐出任何文本", events.none { it is ProviderEvent.TextDelta })
    }

    @Test
    fun stream_reportsInvalidConfigBeforeEmittingText() = runTest {
        val broken = config.copy(model = "  ")
        val events = MockProvider(MockScript.normal()).stream(request, broken, credential).toList()

        val failed = events.last() as ProviderEvent.Failed
        assertEquals(ProviderErrorKind.INVALID_CONFIG, failed.error.kind)
        assertTrue("配置非法时不得吐出文本", events.none { it is ProviderEvent.TextDelta })
    }

    @Test
    fun stream_rejectsInvalidRequestBeforeEmittingText() = runTest {
        val empty = ChatRequest(messages = emptyList())
        val events = MockProvider(MockScript.normal()).stream(empty, config, credential).toList()
        assertEquals(ProviderErrorKind.INVALID_CONFIG, (events.last() as ProviderEvent.Failed).error.kind)
    }

    @Test
    fun stream_echoesLastUserMessageWhenScriptedThatWay() = runTest {
        val provider = MockProvider(MockScript(echoLastUserMessage = true))
        val echoRequest = ChatRequest(
            messages = listOf(
                com.example.aiuseagent.domain.provider.ProviderMessage(
                    com.example.aiuseagent.domain.provider.ChatRole.USER,
                    "第一条",
                ),
                com.example.aiuseagent.domain.provider.ProviderMessage(
                    com.example.aiuseagent.domain.provider.ChatRole.ASSISTANT,
                    "回复",
                ),
                com.example.aiuseagent.domain.provider.ProviderMessage(
                    com.example.aiuseagent.domain.provider.ChatRole.USER,
                    "最后一条",
                ),
            ),
        )
        val events = provider.stream(echoRequest, config, credential).toList()
        assertEquals("最后一条", ProviderEventContract.textOf(events))
    }

    @Test
    fun stream_skipsEmptyChunksSoNoEmptyDeltaIsEmitted() = runTest {
        val provider = MockProvider(MockScript.normal(listOf("A", "", "B")))
        val events = provider.stream(request, config, credential).toList()

        assertEquals("AB", ProviderEventContract.textOf(events))
        assertEquals(2, events.count { it is ProviderEvent.TextDelta })
        assertTrue(ProviderEventContract.validate(events).isEmpty())
    }

    @Test
    fun stream_isColdSoEachCollectionRunsAgain() = runTest {
        val provider = MockProvider(MockScript.normal(listOf("A", "B")))
        val first = provider.stream(request, config, credential).toList()
        val second = provider.stream(request, config, credential).toList()

        assertEquals(first.size, second.size)
        assertEquals(ProviderEventContract.textOf(first), ProviderEventContract.textOf(second))
        assertTrue(ProviderEventContract.validate(second).isEmpty())
    }

    @Test
    fun stream_cancellationStopsEmissionWithoutTerminalEvent() = runTest {
        val script = MockScript.normal(listOf("A", "B", "C")).copy(perChunkDelayMillis = 1_000)
        val provider = MockProvider(script)
        val received = mutableListOf<ProviderEvent>()

        val job = launch { provider.stream(request, config, credential).collect { received += it } }
        runCurrent()

        assertEquals("此时只应收到 Started，之后停在延迟里", 1, received.size)
        assertTrue(received.first() is ProviderEvent.Started)

        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals("取消后不得再产生任何事件", 1, received.size)
        assertTrue("取消不产生终态事件", received.none { it.isTerminal })
    }

    @Test
    fun mock_isNeverARealNetworkProvider() {
        assertFalse(ProviderType.MOCK.isRealNetworkProvider)
        assertEquals(setOf(ProviderType.MOCK), ProviderType.implementedInP2A)
        assertEquals(
            setOf(
                ProviderType.DEEPSEEK,
                ProviderType.OPENAI_COMPATIBLE,
                ProviderType.ANTHROPIC,
                ProviderType.MOCK,
            ),
            ProviderType.implementedInP2B,
        )
    }
}
