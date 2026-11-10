package com.example.aiuseagent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 事件序列契约：Started → 若干 TextDelta → 恰好一个终态。 */
class ProviderEventContractTest {

    private val started = ProviderEvent.Started(ProviderType.MOCK, "mock-echo")
    private val completed = ProviderEvent.Completed()

    @Test
    fun validate_acceptsCanonicalSequence() {
        val events = listOf(
            started,
            ProviderEvent.TextDelta("你"),
            ProviderEvent.TextDelta("好"),
            completed,
        )
        assertTrue("合法序列不应报错，实际：" + ProviderEventContract.validate(events), ProviderEventContract.validate(events).isEmpty())
    }

    @Test
    fun validate_rejectsEmptySequence() {
        assertTrue(ProviderEventContract.validate(emptyList()).isNotEmpty())
    }

    @Test
    fun validate_rejectsSequenceWithoutStartedFirst() {
        val problems = ProviderEventContract.validate(listOf(ProviderEvent.TextDelta("x"), completed))
        assertTrue(problems.any { it.contains("Started") })
    }

    @Test
    fun validate_rejectsDuplicateStarted() {
        val problems = ProviderEventContract.validate(listOf(started, started, completed))
        assertTrue(problems.any { it.contains("多次") })
    }

    @Test
    fun validate_rejectsMultipleTerminalEvents() {
        val events = listOf(started, completed, ProviderEvent.Failed(ProviderError.of(ProviderErrorKind.CANCELLED)))
        val problems = ProviderEventContract.validate(events)
        assertTrue(problems.any { it.contains("多于一个") })
        assertTrue(problems.any { it.contains("不是最后一个") })
    }

    @Test
    fun validate_rejectsTerminalNotLast() {
        val events = listOf(started, completed, ProviderEvent.TextDelta("late"))
        assertTrue(ProviderEventContract.validate(events).any { it.contains("不是最后一个") })
    }

    @Test
    fun validate_rejectsEmptyTextDelta() {
        val events = listOf(started, ProviderEvent.TextDelta(""), completed)
        assertTrue(ProviderEventContract.validate(events).any { it.contains("空") })
    }

    @Test
    fun textOf_concatenatesDeltasOnly() {
        val events = listOf(
            started,
            ProviderEvent.TextDelta("你好，"),
            ProviderEvent.TextDelta("世界"),
            completed,
        )
        assertEquals("你好，世界", ProviderEventContract.textOf(events))
        assertEquals("你好，世界", events.mapNotNull { it.textOrNull() }.joinToString(""))
    }

    @Test
    fun isTerminal_coversExactlyCompletedAndFailed() {
        assertTrue(completed.isTerminal)
        assertTrue(ProviderEvent.Failed(ProviderError.of(ProviderErrorKind.NETWORK)).isTerminal)
        assertFalse(started.isTerminal)
        assertFalse(ProviderEvent.TextDelta("x").isTerminal)
    }

    @Test
    fun finishReason_mapsWireNamesAndFallsBackToUnknown() {
        assertEquals(FinishReason.STOP, FinishReason.fromWireName("stop"))
        assertEquals(FinishReason.LENGTH, FinishReason.fromWireName("length"))
        assertEquals(FinishReason.CONTENT_FILTER, FinishReason.fromWireName("content_filter"))
        assertEquals(FinishReason.TOOL_CALLS, FinishReason.fromWireName("tool_calls"))
        assertEquals("未知的结束原因必须归到 UNKNOWN，而不是假装 STOP", FinishReason.UNKNOWN, FinishReason.fromWireName("future_unknown_finish"))
        assertEquals("旧版函数调用未实现，不能映射为已支持的工具终态", FinishReason.UNKNOWN, FinishReason.fromWireName("function_call"))
        assertEquals(FinishReason.UNKNOWN, FinishReason.fromWireName(null))
        assertNull(FinishReason.UNKNOWN.wireName)
    }
}
