package com.example.aiuseagent.data.provider.http

import com.example.aiuseagent.data.provider.sse.SseEvent
import com.example.aiuseagent.data.provider.sse.SseFormatException
import com.example.aiuseagent.data.provider.sse.SseParser
import com.example.aiuseagent.domain.provider.ChatRequest
import com.example.aiuseagent.domain.provider.FinishReason
import com.example.aiuseagent.domain.provider.ModelProvider
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderCredential
import com.example.aiuseagent.domain.provider.ProviderError
import com.example.aiuseagent.domain.provider.ProviderErrorKind
import com.example.aiuseagent.domain.provider.ProviderErrorMapper
import com.example.aiuseagent.domain.provider.ProviderEvent
import com.example.aiuseagent.domain.provider.ProviderPreflight
import com.example.aiuseagent.domain.provider.ProviderType
import com.example.aiuseagent.domain.provider.TokenUsage
import com.example.aiuseagent.domain.provider.ProviderToolCall
import com.example.aiuseagent.domain.provider.catchAsProviderFailure
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.serialization.SerializationException

/**
 * 真实网络供应商的公共实现：组装请求 → 流式读取 → SSE 解码 → 有限重试 → 事件契约。
 *
 * 事件契约（与 MockProvider 保持一致）：
 * 1. 进入 flow 就先发 [ProviderEvent.Started]，因此任何失败都是「Started 之后的 Failed」终态；
 * 2. 已发出首个 [ProviderEvent.TextDelta] 之后**绝不重放请求**（否则用户会看到重复内容）；
 * 3. 取消不是失败：取消时抛 CancellationException，不产生终态事件。
 */
abstract class HttpStreamingProvider(
    final override val type: ProviderType,
    private val transport: HttpTransport,
    private val retryPolicy: RetryPolicy = RetryPolicy(),
    private val diagnostics: ProviderDiagnostics = ProviderDiagnostics.NoOp,
    private val backoff: suspend (Long) -> Unit = { millis -> delay(millis) },
) : ModelProvider {

    /** 相对 Base URL 的端点路径（Base URL 里不允许已经包含它）。 */
    protected abstract val relativePath: String

    protected abstract fun buildRequest(
        request: ChatRequest,
        config: ProviderConfig,
        credential: ProviderCredential,
    ): ProviderHttpRequest

    protected abstract fun newDecoder(): SseStreamDecoder
    protected open fun newDecoder(request: ChatRequest): SseStreamDecoder = newDecoder()

    override fun stream(
        request: ChatRequest,
        config: ProviderConfig,
        credential: ProviderCredential,
    ): Flow<ProviderEvent> {
        val secrets = listOfNotNull(credential.reveal())
        return flow {
            emit(ProviderEvent.Started(type, config.model))

            val preflight = ProviderPreflight.check(type, request, config, credential)
            if (preflight != null) {
                record("preflight_failed", errorKind = preflight.kind.name, attempt = 0, secrets = secrets)
                emit(ProviderEvent.Failed(preflight.redact(*secrets.toTypedArray())))
                return@flow
            }

            val spec = try {
                buildRequest(request, config, credential)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                record("request_build_failed", attempt = 0, secrets = secrets)
                emit(ProviderEvent.Failed(invalidConfig(e.message, secrets)))
                return@flow
            }

            if (!HttpUrlPolicy.isAllowed(spec.url, spec.allowInsecureHttp)) {
                record("url_rejected", attempt = 0, secrets = secrets)
                emit(ProviderEvent.Failed(invalidConfig(urlRejectionDetail(spec.url), secrets)))
                return@flow
            }

            var attempt = 1
            var anyDeltaEmitted = false
            val effectiveRetryPolicy = request.maxAttempts?.let {
                retryPolicy.copy(maxAttempts = minOf(it, retryPolicy.maxAttempts))
            } ?: retryPolicy
            while (true) {
                currentCoroutineContext().ensureActive()
                if (request.attachmentPayloads?.current() == false) {
                    emit(ProviderEvent.Failed(invalidConfig("附件已失效，请重新选择。", secrets)))
                    return@flow
                }
                val startedAt = System.currentTimeMillis()
                val outcome = runAttempt(spec, config, request, attempt, secrets,
                    onProgress = { anyDeltaEmitted = true },
                    onDelta = { text -> emit(ProviderEvent.TextDelta(text)) })
                val durationMillis = System.currentTimeMillis() - startedAt
                when (outcome) {
                    is AttemptOutcome.Completed -> {
                        record("stream_completed", attempt = attempt, durationMillis = durationMillis, secrets = secrets)
                        if (outcome.toolCalls.isNotEmpty()) emit(ProviderEvent.ToolCalls(outcome.toolCalls))
                        emit(ProviderEvent.Completed(outcome.finishReason, outcome.usage))
                        return@flow
                    }
                    is AttemptOutcome.Failure -> {
                        record(
                            event = "stream_failed",
                            errorKind = outcome.error.kind.name,
                            httpStatus = outcome.error.httpStatus,
                            durationMillis = durationMillis,
                            attempt = attempt,
                            requestId = outcome.error.requestId,
                            secrets = secrets,
                        )
                        if (effectiveRetryPolicy.shouldRetry(outcome.error.kind, attempt, anyDeltaEmitted)) {
                            val waitMillis = effectiveRetryPolicy.delayMillisFor(attempt, outcome.retryAfterMillis)
                            record(
                                event = "retry_scheduled",
                                errorKind = outcome.error.kind.name,
                                durationMillis = waitMillis,
                                attempt = attempt,
                                secrets = secrets,
                            )
                            backoff(waitMillis)
                            attempt++
                        } else {
                            emit(ProviderEvent.Failed(outcome.error))
                            return@flow
                        }
                    }
                }
            }
        }.catchAsProviderFailure(type, *secrets.toTypedArray())
    }

    private sealed interface AttemptOutcome {
        data class Completed(val finishReason: FinishReason, val usage: TokenUsage?,
            val toolCalls: List<ProviderToolCall>) : AttemptOutcome

        data class Failure(val error: ProviderError, val retryAfterMillis: Long? = null) : AttemptOutcome
    }

    private class DecodeState {
        var finishReason: FinishReason = FinishReason.STOP
        var usage: TokenUsage? = null
        var terminated: Boolean = false
        var eventCount: Int = 0
        var hasNonBlankText: Boolean = false
        var toolCalls: List<ProviderToolCall> = emptyList()
    }

    private suspend fun runAttempt(
        spec: ProviderHttpRequest,
        config: ProviderConfig,
        request: ChatRequest,
        attempt: Int,
        secrets: List<String>,
        onProgress: () -> Unit,
        onDelta: suspend (String) -> Unit,
    ): AttemptOutcome {
        val timeouts = HttpTimeouts(
            connectMillis = config.connectTimeoutMillis,
            readMillis = config.readTimeoutMillis,
            totalMillis = config.totalTimeoutMillis,
        )
        val response = try {
            record("request_attempt_started", attempt = attempt, secrets = secrets)
            transport.open(spec, timeouts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalArgumentException) {
            return AttemptOutcome.Failure(invalidConfig(e.message, secrets))
        } catch (e: IOException) {
            throwIfCancelled()
            return AttemptOutcome.Failure(ProviderErrorMapper.fromThrowable(e, type).redact(*secrets.toTypedArray()))
        }
        return response.use { open ->
            if (open.status !in 200..299) {
                // 非 2xx 一律**不做 SSE 解析**：先按状态码分类，再只补充机器可读的短标记
                val error = ProviderError.fromHttpStatus(open.status, type, open.requestId)
                    .copy(detail = errorDetail(open, secrets))
                    .redact(*secrets.toTypedArray())
                AttemptOutcome.Failure(error, open.retryAfterMillis)
            } else {
                readStream(open, request, attempt, secrets, onProgress, onDelta)
            }
        }
    }

    private suspend fun readStream(
        response: StreamingHttpResponse,
        request: ChatRequest,
        attempt: Int,
        secrets: List<String>,
        onProgress: () -> Unit,
        onDelta: suspend (String) -> Unit,
    ): AttemptOutcome {
        val parser = SseParser()
        val decoder = newDecoder(request)
        val state = DecodeState()
        try {
            while (!state.terminated) {
                currentCoroutineContext().ensureActive()
                val chunk = try {
                    response.readChunk()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    throwIfCancelled()
                    return AttemptOutcome.Failure(
                        ProviderErrorMapper.fromThrowable(e, type).redact(*secrets.toTypedArray())
                    )
                }
                if (chunk == null) break
                if (chunk.isEmpty()) continue
                consumeEvents(parser.feed(chunk), decoder, state, attempt, secrets, onProgress, onDelta)?.let { return it }
            }
            if (!state.terminated) {
                consumeEvents(parser.finish(), decoder, state, attempt, secrets, onProgress, onDelta)?.let { return it }
            }
        } catch (e: SseFormatException) {
            return AttemptOutcome.Failure(protocolError(e.message, secrets))
        }
        if (!state.terminated) {
            // 没读到协议终止标记就结束了：一个事件都没有 → 空响应；否则 → 协议不完整
            return if (state.eventCount == 0) {
                AttemptOutcome.Failure(ProviderError.of(ProviderErrorKind.EMPTY_RESPONSE, type))
            } else {
                AttemptOutcome.Failure(protocolError("流在协议终止标记之前结束（可能被中途截断）", secrets))
            }
        }
        if (!state.hasNonBlankText && state.toolCalls.isEmpty()) {
            return AttemptOutcome.Failure(ProviderError.of(ProviderErrorKind.EMPTY_RESPONSE, type))
        }
        return AttemptOutcome.Completed(state.finishReason, state.usage, state.toolCalls)
    }

    private suspend fun consumeEvents(
        events: List<SseEvent>,
        decoder: SseStreamDecoder,
        state: DecodeState,
        attempt: Int,
        secrets: List<String>,
        onProgress: () -> Unit,
        onDelta: suspend (String) -> Unit,
    ): AttemptOutcome? {
        for (event in events) {
            state.eventCount++
            val actions = try {
                decoder.onEvent(event)
            } catch (e: SerializationException) {
                // 仅捕获解码异常：message 可能带正文，不能回显，也不能捕获下游收集器异常。
                return AttemptOutcome.Failure(protocolError("流式 JSON 数据格式无效", secrets))
            }
            for (action in actions) {
                when (action) {
                    is SseAction.Delta -> if (action.text.isNotEmpty()) {
                        onProgress()
                        if (action.text.isNotBlank()) state.hasNonBlankText = true
                        onDelta(action.text)
                    }
                    SseAction.ToolFragment -> onProgress()
                    is SseAction.ToolCalls -> state.toolCalls = action.calls
                    is SseAction.Finish -> state.finishReason = action.reason
                    is SseAction.Usage -> state.usage = mergeUsage(state.usage, action.usage)
                    SseAction.Terminator -> {
                        state.terminated = true
                        return null
                    }
                    is SseAction.Error -> return AttemptOutcome.Failure(action.error.redact(*secrets.toTypedArray()))
                    is SseAction.Unsupported -> return AttemptOutcome.Failure(protocolError(action.detail, secrets))
                    is SseAction.Note -> record(
                        event = "stream_note",
                        attempt = attempt,
                        note = action.detail,
                        secrets = secrets,
                    )
                    SseAction.Ignored -> Unit
                }
            }
        }
        return null
    }

    private fun mergeUsage(current: TokenUsage?, update: TokenUsage): TokenUsage = TokenUsage(
        inputTokens = update.inputTokens ?: current?.inputTokens,
        outputTokens = update.outputTokens ?: current?.outputTokens,
    )

    private suspend fun errorDetail(response: StreamingHttpResponse, secrets: List<String>): String? {
        val body = try {
            response.readErrorBodyText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            null
        }
        val token = ErrorBodyProbe.tokenOf(body, secrets) ?: return null
        return DiagnosticText.safe("服务端错误标记：" + token, secrets)
    }

    private fun urlRejectionDetail(url: String): String =
        if (HttpUrlPolicy.schemeOf(url) == "http") {
            "拒绝向明文 HTTP 地址发送请求（需要显式允许明文流量）"
        } else {
            "Base URL 不是可用的 https 地址"
        }

    private fun protocolError(detail: String?, secrets: List<String>): ProviderError =
        ProviderError.of(ProviderErrorKind.PROTOCOL, type, detail = DiagnosticText.safe(detail, secrets))

    private fun invalidConfig(detail: String?, secrets: List<String>): ProviderError =
        ProviderError.of(ProviderErrorKind.INVALID_CONFIG, type, detail = DiagnosticText.safe(detail, secrets))

    private suspend fun throwIfCancelled() {
        if (!currentCoroutineContext().isActive) throw CancellationException("请求已取消")
    }

    private fun record(
        event: String,
        errorKind: String? = null,
        httpStatus: Int? = null,
        durationMillis: Long? = null,
        attempt: Int? = null,
        requestId: String? = null,
        note: String? = null,
        secrets: List<String> = emptyList(),
    ) {
        diagnostics.record(
            ProviderLogRecord(
                event = event,
                providerType = type,
                errorKind = errorKind,
                httpStatus = httpStatus,
                durationMillis = durationMillis,
                attempt = attempt,
                requestId = requestId,
                note = DiagnosticText.safe(note, secrets),
            ).redact(secrets)
        )
    }
}
