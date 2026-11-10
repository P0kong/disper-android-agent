package com.example.aiuseagent.data.provider.anthropic

import com.example.aiuseagent.data.provider.http.DiagnosticText
import com.example.aiuseagent.data.provider.http.ErrorBodyProbe
import com.example.aiuseagent.data.provider.http.HttpStreamingProvider
import com.example.aiuseagent.data.provider.http.HttpTransport
import com.example.aiuseagent.data.provider.http.ProviderDiagnostics
import com.example.aiuseagent.data.provider.http.ProviderHeader
import com.example.aiuseagent.data.provider.http.ProviderHttpRequest
import com.example.aiuseagent.data.provider.http.RetryPolicy
import com.example.aiuseagent.data.provider.http.SseAction
import com.example.aiuseagent.data.provider.http.SseStreamDecoder
import com.example.aiuseagent.data.provider.http.jsonInt
import com.example.aiuseagent.data.provider.sse.SseEvent
import com.example.aiuseagent.domain.provider.ChatRequest
import com.example.aiuseagent.domain.provider.FinishReason
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderCredential
import com.example.aiuseagent.domain.provider.ProviderError
import com.example.aiuseagent.domain.provider.ProviderErrorKind
import com.example.aiuseagent.domain.provider.ProviderProtocol
import com.example.aiuseagent.domain.provider.ProviderType
import com.example.aiuseagent.domain.provider.TokenUsage
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Anthropic Messages API 的流式适配器。
 *
 * 与 OpenAI 兼容协议的三处关键差异（都有自动测试覆盖）：
 * 1. 端点相对路径是 messages，凭据走 x-api-key 而不是 Authorization: Bearer；
 * 2. 必须发送 anthropic-version（见 [ANTHROPIC_VERSION]）；
 * 3. system 提示词是**顶层 system 字段**，不是 messages 里的一条消息。
 */
class AnthropicMessagesProvider(
    transport: HttpTransport,
    retryPolicy: RetryPolicy = RetryPolicy(),
    diagnostics: ProviderDiagnostics = ProviderDiagnostics.NoOp,
    backoff: suspend (Long) -> Unit = { millis -> delay(millis) },
) : HttpStreamingProvider(ProviderType.ANTHROPIC, transport, retryPolicy, diagnostics, backoff) {

    init {
        require(type.protocol == ProviderProtocol.ANTHROPIC_MESSAGES) {
            "AnthropicMessagesProvider 只接受 ANTHROPIC_MESSAGES 协议，收到：" + type.protocol
        }
    }

    override val relativePath: String = ENDPOINT

    override fun buildRequest(
        request: ChatRequest,
        config: ProviderConfig,
        credential: ProviderCredential,
    ): ProviderHttpRequest {
        val messages = buildJsonArray {
            for (message in request.messages) {
                add(buildJsonObject {
                    put("role", message.role.wireName)
                    if(message.parts.isEmpty()) put("content", message.content)
                    else put("content",buildJsonArray {
                        if(message.content.isNotBlank()) add(buildJsonObject {put("type","text");put("text",message.content)})
                        message.parts.forEach { part -> add(buildJsonObject {
                            when(part) {
                                is com.example.aiuseagent.domain.provider.ProviderContentPart.Text -> {put("type","text");put("text",part.source?.let {"来源：$it\n"}.orEmpty()+part.text)}
                                is com.example.aiuseagent.domain.provider.ProviderContentPart.Image -> {
                                    val bytes=requireNotNull(request.attachmentPayloads?.resolve(part)) {"ATTACHMENT_UNAVAILABLE"}
                                    put("type","image");put("source",buildJsonObject {put("type","base64");put("media_type",part.mimeType);put("data",java.util.Base64.getEncoder().encodeToString(bytes))})
                                }
                                is com.example.aiuseagent.domain.provider.ProviderContentPart.Document -> error("DOCUMENT_PROTOCOL_UNSUPPORTED")
                            }
                        }) }
                    })
                })
            }
        }
        val payload = buildJsonObject {
            put("model", config.model)
            // Anthropic 要求 max_tokens 必填：请求没给就用本阶段的默认值
            put("max_tokens", request.maxOutputTokens ?: DEFAULT_MAX_TOKENS)
            put("stream", true)
            request.system?.takeIf { it.isNotBlank() }?.let { system -> put("system", system) }
            put("messages", messages)
            request.temperature?.let { put("temperature", JsonPrimitive(it)) }
        }
        val apiKey = credential.reveal().orEmpty()
        return ProviderHttpRequest(
            url = config.resolvePath(relativePath),
            headers = listOf(
                ProviderHeader(API_KEY_HEADER, apiKey),
                ProviderHeader(VERSION_HEADER, config.connectionProfile?.apiVersion ?: ANTHROPIC_VERSION),
                ProviderHeader(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE),
                ProviderHeader(ACCEPT_HEADER, EVENT_STREAM_CONTENT_TYPE),
            ),
            body = payload.toString(),
            allowInsecureHttp = config.allowInsecureHttp,
        )
    }

    override fun newDecoder(): SseStreamDecoder = AnthropicSseDecoder(type)

    companion object {
        /** 相对路径：Base URL 已经包含 /v1。 */
        const val ENDPOINT = "messages"

        /**
         * Anthropic Messages API 版本。
         *
         * 更新方式：Anthropic 用日期字符串标识 API 版本，升级时只改这一个常量，
         * 并同步更新 docs/PROVIDER-DESIGN.md 中记录的版本号；
         * 头名与请求形状由官方文档固定（x-api-key + anthropic-version）。
         */
        const val ANTHROPIC_VERSION = "2023-06-01"

        /** 请求没给 max_tokens 时的默认值（Anthropic 必填字段）。 */
        const val DEFAULT_MAX_TOKENS = 1024

        const val API_KEY_HEADER = "x-api-key"
        const val VERSION_HEADER = "anthropic-version"
        private const val CONTENT_TYPE_HEADER = "Content-Type"
        private const val ACCEPT_HEADER = "Accept"
        private const val JSON_CONTENT_TYPE = "application/json"
        private const val EVENT_STREAM_CONTENT_TYPE = "text/event-stream"
    }
}

/**
 * Anthropic 命名事件解码器。
 *
 * 已知事件按语义处理；未知事件只记诊断（向前兼容），
 * 但「不支持的内容」（tool_use、input_json_delta、未知增量类型）必须显式失败。
 */
internal class AnthropicSseDecoder(private val type: ProviderType) : SseStreamDecoder {

    override fun onEvent(event: SseEvent): List<SseAction> {
        val payload = event.data.trim()
        if (payload.isEmpty()) return listOf(SseAction.Ignored)
        val root = Json.parseToJsonElement(payload) as? JsonObject
            ?: return unsupported("事件数据不是合法的 JSON 对象")
        if (!isOptionalString(root["type"])) return unsupported("事件 type 字段类型无效")
        val name = event.event ?: (root["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        return when (name) {
            EVENT_PING -> listOf(SseAction.Ignored)
            EVENT_MESSAGE_START -> messageStart(root)
            EVENT_CONTENT_BLOCK_START -> contentBlockStart(root)
            EVENT_CONTENT_BLOCK_DELTA -> contentBlockDelta(root)
            EVENT_CONTENT_BLOCK_STOP -> listOf(SseAction.Ignored)
            EVENT_MESSAGE_DELTA -> messageDelta(root)
            EVENT_MESSAGE_STOP -> listOf(SseAction.Terminator)
            EVENT_ERROR -> {
                val error = root["error"] as? JsonObject ?: return unsupported("error 字段结构无效")
                if (!isOptionalString(error["type"]) || !isOptionalString(error["message"])) {
                    return unsupported("error 字段结构无效")
                }
                listOf(SseAction.Error(streamError(root, payload)))
            }
            null -> unsupported("事件缺少类型字段")
            // 服务端标签可能回显长凭据；固定文案避免先截断后无法按完整凭据脱敏。
            else -> listOf(SseAction.Note("未知事件类型，已忽略"))
        }
    }

    private fun messageStart(root: JsonObject): List<SseAction> {
        val message = root["message"] as? JsonObject ?: return unsupported("message_start 缺少 message 字段")
        if (!isValidUsage(message["usage"])) return unsupported("usage 字段结构无效")
        val usage = message["usage"] as? JsonObject
        val input = jsonInt(usage?.get("input_tokens"))
        val output = jsonInt(usage?.get("output_tokens"))
        if (input == null && output == null) return listOf(SseAction.Ignored)
        return listOf(SseAction.Usage(TokenUsage(inputTokens = input, outputTokens = output)))
    }

    private fun contentBlockStart(root: JsonObject): List<SseAction> {
        val block = root["content_block"] as? JsonObject
            ?: return unsupported("content_block_start 缺少 content_block 字段")
        return when ((block["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "text" -> if (isOptionalString(block["text"])) listOf(SseAction.Ignored)
                else unsupported("text 内容块字段类型无效")
            "tool_use" -> unsupported("服务端开始输出工具调用（tool_use）：本阶段不支持，已停止本次流")
            "image", "document", "audio", "video" -> unsupported("服务端返回了多模态内容块：本阶段不支持")
            "thinking", "redacted_thinking" -> listOf(SseAction.Note("服务端返回了扩展思考内容块：本阶段不展示"))
            null -> unsupported("content_block 缺少 type 字段")
            else -> listOf(SseAction.Note("未知内容块类型，已忽略"))
        }
    }

    private fun contentBlockDelta(root: JsonObject): List<SseAction> {
        val delta = root["delta"] as? JsonObject ?: return unsupported("content_block_delta 缺少 delta 字段")
        return when ((delta["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content) {
            "text_delta" -> {
                val text = (delta["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (text == null) unsupported("text_delta 缺少 text 字段")
                else if (text.isEmpty()) listOf(SseAction.Ignored)
                else listOf(SseAction.Delta(text))
            }
            "input_json_delta" -> unsupported("服务端在输出工具调用参数（input_json_delta）：本阶段不支持，已停止本次流")
            "thinking_delta" -> listOf(SseAction.Note("服务端返回了扩展思考增量：本阶段不展示"))
            "signature_delta" -> listOf(SseAction.Ignored)
            null -> unsupported("内容增量缺少 type 字段")
            else -> unsupported("不支持的内容增量类型")
        }
    }

    private fun messageDelta(root: JsonObject): List<SseAction> {
        val delta = root["delta"] as? JsonObject ?: return unsupported("message_delta 缺少 delta 字段")
        if (!isOptionalString(delta["stop_reason"])) return unsupported("stop_reason 字段类型无效")
        if (!isValidUsage(root["usage"])) return unsupported("usage 字段结构无效")
        if ((delta["stop_reason"] as? JsonPrimitive)?.content == "tool_use") {
            return unsupported("服务端返回了工具调用终态：本阶段不支持")
        }
        val actions = mutableListOf<SseAction>()
        (delta["stop_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.let { reason ->
            actions += SseAction.Finish(mapStopReason(reason.content))
        }
        val output = jsonInt((root["usage"] as? JsonObject)?.get("output_tokens"))
        if (output != null) actions += SseAction.Usage(TokenUsage(outputTokens = output))
        if (actions.isEmpty()) actions += SseAction.Ignored
        return actions
    }

    private fun isOptionalString(element: JsonElement?): Boolean =
        element == null || element is JsonNull || (element is JsonPrimitive && element.isString)

    private fun isValidUsage(element: JsonElement?): Boolean {
        if (element == null || element is JsonNull) return true
        if (element !is JsonObject) return false
        return listOf("input_tokens", "output_tokens").all { field ->
            val value = element[field]
            value == null || value is JsonNull || jsonInt(value)?.let { it >= 0 } == true
        }
    }

    private fun mapStopReason(raw: String): FinishReason = when (raw) {
        "end_turn", "stop_sequence" -> FinishReason.STOP
        "max_tokens" -> FinishReason.LENGTH
        "refusal" -> FinishReason.CONTENT_FILTER
        else -> FinishReason.UNKNOWN
    }

    private fun streamError(root: JsonObject, payload: String): ProviderError {
        val wireType = ((root["error"] as? JsonObject)?.get("type") as? JsonPrimitive)
            ?.takeIf { it.isString }?.content
        val kind = when (wireType) {
            "authentication_error", "permission_error" -> ProviderErrorKind.UNAUTHORIZED
            "rate_limit_error" -> ProviderErrorKind.RATE_LIMITED
            "overloaded_error", "api_error" -> ProviderErrorKind.SERVER_ERROR
            "invalid_request_error", "not_found_error" -> ProviderErrorKind.PROTOCOL
            else -> ProviderErrorKind.PROTOCOL
        }
        val token = ErrorBodyProbe.tokenOf(payload, emptyList())
        val detail = if (token == null) "服务端在流中返回了错误事件" else "服务端错误标记：" + token
        return ProviderError.of(kind, type, detail = DiagnosticText.safe(detail, emptyList()))
    }

    private fun unsupported(detail: String): List<SseAction> = listOf(SseAction.Unsupported(detail))

    private companion object {
        const val EVENT_PING = "ping"
        const val EVENT_MESSAGE_START = "message_start"
        const val EVENT_CONTENT_BLOCK_START = "content_block_start"
        const val EVENT_CONTENT_BLOCK_DELTA = "content_block_delta"
        const val EVENT_CONTENT_BLOCK_STOP = "content_block_stop"
        const val EVENT_MESSAGE_DELTA = "message_delta"
        const val EVENT_MESSAGE_STOP = "message_stop"
        const val EVENT_ERROR = "error"
    }
}
