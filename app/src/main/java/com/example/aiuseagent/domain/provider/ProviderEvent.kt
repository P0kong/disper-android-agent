package com.example.aiuseagent.domain.provider

/**
 * 流式事件。合法序列（P2-A 契约）：
 * [Started] → 若干 [TextDelta] / 完整 [ToolCalls] → **恰好一个** 终态（[Completed] 或 [Failed]）。
 *
 * 取消不产生终态事件：取消以 [kotlinx.coroutines.CancellationException] 表达。
 */
sealed interface ProviderEvent {

    data class Started(val providerType: ProviderType, val model: String) : ProviderEvent

    data class TextDelta(val text: String) : ProviderEvent

    /** Fully aggregated calls; fragments are never exposed to executors. */
    data class ToolCalls(val calls: List<ProviderToolCall>) : ProviderEvent {
        override fun toString(): String = "ToolCalls(count=${calls.size})"
    }

    data class Completed(
        val finishReason: FinishReason = FinishReason.STOP,
        val usage: TokenUsage? = null,
    ) : ProviderEvent

    data class Failed(val error: ProviderError) : ProviderEvent
}

val ProviderEvent.isTerminal: Boolean
    get() = this is ProviderEvent.Completed || this is ProviderEvent.Failed

/** 取增量文本；非文本事件返回 null。 */
fun ProviderEvent.textOrNull(): String? = (this as? ProviderEvent.TextDelta)?.text

enum class FinishReason(val wireName: String?) {
    STOP("stop"),
    LENGTH("length"),
    CONTENT_FILTER("content_filter"),
    TOOL_CALLS("tool_calls"),
    UNKNOWN(null),
    ;

    companion object {
        fun fromWireName(raw: String?): FinishReason =
            entries.firstOrNull { it.wireName == raw } ?: UNKNOWN
    }
}

data class TokenUsage(val inputTokens: Int? = null, val outputTokens: Int? = null)
