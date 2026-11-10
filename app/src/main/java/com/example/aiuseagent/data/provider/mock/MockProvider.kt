package com.example.aiuseagent.data.provider.mock

import com.example.aiuseagent.domain.provider.ChatRequest
import com.example.aiuseagent.domain.provider.ChatRole
import com.example.aiuseagent.domain.provider.FinishReason
import com.example.aiuseagent.domain.provider.ModelProvider
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderCredential
import com.example.aiuseagent.domain.provider.ProviderError
import com.example.aiuseagent.domain.provider.ProviderErrorKind
import com.example.aiuseagent.domain.provider.ProviderEvent
import com.example.aiuseagent.domain.provider.ProviderPreflight
import com.example.aiuseagent.domain.provider.ProviderType
import com.example.aiuseagent.domain.provider.TokenUsage
import com.example.aiuseagent.domain.provider.catchAsProviderFailure
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Mock 的行为脚本：测试用它完整控制事件序列，包括「中途失败」这种真实适配层最难处理的情况。
 *
 * - [failAfterChunks] 只在 [failure] 非空时有意义：先发 N 个分片再失败（null 视为 0，即立即失败）；
 * - [emptyResponse] 用来模拟「服务端 200 但内容为空」，必须走 [ProviderErrorKind.EMPTY_RESPONSE]；
 * - [requiresKey] 为 true 时，Mock 也会要求 Key（用于覆盖「缺 Key」这条分支）；
 * - [echoLastUserMessage] 为 true 时回显最后一条用户消息，用来证明请求真的传到了 Provider。
 */
data class MockScript(
    val chunks: List<String> = DEFAULT_CHUNKS,
    val failure: ProviderError? = null,
    val failAfterChunks: Int? = null,
    val finishReason: FinishReason = FinishReason.STOP,
    val usage: TokenUsage? = null,
    val perChunkDelayMillis: Long = 0L,
    val emptyResponse: Boolean = false,
    val requiresKey: Boolean = false,
    val echoLastUserMessage: Boolean = false,
) {
    companion object {
        val DEFAULT_CHUNKS: List<String> = listOf("你好，", "我是本地 Mock 模型，", "不会发起任何真实网络请求。")

        fun normal(chunks: List<String> = DEFAULT_CHUNKS): MockScript = MockScript(chunks = chunks)

        fun failing(error: ProviderError, afterChunks: Int = 0): MockScript =
            MockScript(failure = error, failAfterChunks = afterChunks)
    }
}

/**
 * 本地 Mock 供应商：**完全不联网**。
 *
 * 它存在的意义是把「上层如何消费流式事件」这条链路先固定下来：
 * 取消、失败终态、空响应、事件契约都能在 JVM 单测里被覆盖，
 * 而不需要任何 API Key、不需要设备、不需要网络。
 */
class MockProvider(
    private val script: MockScript = MockScript(),
    override val type: ProviderType = ProviderType.MOCK,
) : ModelProvider {

    override fun stream(
        request: ChatRequest,
        config: ProviderConfig,
        credential: ProviderCredential,
    ): Flow<ProviderEvent> {
        val secrets = listOfNotNull(credential.reveal()).toTypedArray()
        val events = flow {
            emit(ProviderEvent.Started(type, config.model))

            val preflight = ProviderPreflight.check(
                type = type,
                request = request,
                config = config,
                credential = credential,
                requiresKey = script.requiresKey,
            )
            if (preflight != null) {
                emit(ProviderEvent.Failed(preflight.redact(*secrets)))
                return@flow
            }

            if (script.perChunkDelayMillis > 0) delay(script.perChunkDelayMillis)

            if (script.emptyResponse) {
                emit(ProviderEvent.Failed(ProviderError.of(ProviderErrorKind.EMPTY_RESPONSE, type)))
                return@flow
            }

            val chunks = resolveChunks(request)
            val failure = script.failure
            val chunksBeforeFailure = if (failure == null) chunks.size else (script.failAfterChunks ?: 0).coerceAtLeast(0)

            var emitted = 0
            for (chunk in chunks) {
                if (emitted >= chunksBeforeFailure) break
                if (chunk.isEmpty()) continue
                emit(ProviderEvent.TextDelta(chunk))
                emitted++
                if (script.perChunkDelayMillis > 0 && emitted < chunksBeforeFailure) delay(script.perChunkDelayMillis)
            }

            if (failure != null) {
                emit(ProviderEvent.Failed(failure.redact(*secrets)))
                return@flow
            }

            emit(ProviderEvent.Completed(finishReason = script.finishReason, usage = script.usage))
        }
        return events.catchAsProviderFailure(type, *secrets)
    }

    private fun resolveChunks(request: ChatRequest): List<String> {
        if (!script.echoLastUserMessage) return script.chunks
        val last = request.messages.lastOrNull { it.role == ChatRole.USER }?.content.orEmpty()
        return if (last.isEmpty()) script.chunks else listOf(last)
    }
}
