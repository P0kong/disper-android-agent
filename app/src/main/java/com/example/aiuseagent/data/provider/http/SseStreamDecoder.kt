package com.example.aiuseagent.data.provider.http

import com.example.aiuseagent.data.provider.sse.SseEvent
import com.example.aiuseagent.domain.provider.FinishReason
import com.example.aiuseagent.domain.provider.ProviderError
import com.example.aiuseagent.domain.provider.TokenUsage
import com.example.aiuseagent.domain.provider.ProviderToolCall

/** 协议解码器对单条 SSE 事件给出的动作。 */
sealed interface SseAction {

    /** 一段可以展示给用户的正文增量。 */
    data class Delta(val text: String) : SseAction

    /** Received a tool fragment: it prevents retries but is never executed or exposed. */
    data object ToolFragment : SseAction

    data class ToolCalls(val calls: List<ProviderToolCall>) : SseAction

    /** 服务端给出了结束原因。 */
    data class Finish(val reason: FinishReason) : SseAction

    /** 服务端给出了 token 用量（可能只包含 input 或 output）。 */
    data class Usage(val usage: TokenUsage) : SseAction

    /** 协议层的正常终止标记（OpenAI 的 [DONE] / Anthropic 的 message_stop）。 */
    data object Terminator : SseAction

    /** 服务端在流内报告了错误：按终态失败处理。 */
    data class Error(val error: ProviderError) : SseAction

    /** 服务端返回了本阶段不支持的内容：必须显式失败，**不得**当作普通答案继续输出。 */
    data class Unsupported(val detail: String) : SseAction

    /** 只写诊断日志的说明（例如被忽略的未知事件）。 */
    data class Note(val detail: String) : SseAction

    /** 明确忽略（心跳、空增量、未知字段）。 */
    data object Ignored : SseAction
}

/**
 * 把 SSE 事件翻译成 [SseAction]。
 *
 * 每个协议有各自的终止规则与事件名，因此它们各自实现本接口，不共用同一个解码器。
 */
interface SseStreamDecoder {
    fun onEvent(event: SseEvent): List<SseAction>
}
