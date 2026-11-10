package com.example.aiuseagent.data.provider.openai

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
import com.example.aiuseagent.domain.provider.ProviderContentPart
import com.example.aiuseagent.domain.provider.FinishReason
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderCredential
import com.example.aiuseagent.domain.provider.ProviderError
import com.example.aiuseagent.domain.provider.ProviderErrorKind
import com.example.aiuseagent.domain.provider.ProviderProtocol
import com.example.aiuseagent.domain.provider.ProviderType
import com.example.aiuseagent.domain.provider.ProviderToolCall
import com.example.aiuseagent.domain.provider.TokenUsage
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * OpenAI 兼容的 /chat/completions 流式适配器。
 *
 * 一个实现覆盖 OpenAI 兼容协议的所有供应商：DeepSeek 与「通用 OpenAI 兼容服务」
 * 都注册本类实例，只是 [ProviderType] 不同，**不重复实现**协议逻辑。
 *
 * tool_calls 只接受请求声明的函数并在完整终态后发布。
 * function_call、多模态 content 数组仍拒绝；reasoning_content 只记诊断，不当作答案。
 */
class OpenAiCompatibleProvider(
    type: ProviderType,
    transport: HttpTransport,
    retryPolicy: RetryPolicy = RetryPolicy(),
    diagnostics: ProviderDiagnostics = ProviderDiagnostics.NoOp,
    backoff: suspend (Long) -> Unit = { millis -> delay(millis) },
) : HttpStreamingProvider(type, transport, retryPolicy, diagnostics, backoff) {

    init {
        require(type.protocol == ProviderProtocol.OPENAI_COMPATIBLE) {
            "OpenAiCompatibleProvider 只接受 OPENAI_COMPATIBLE 协议，收到：" + type.protocol
        }
    }

    override val relativePath: String = ENDPOINT

    override fun buildRequest(
        request: ChatRequest,
        config: ProviderConfig,
        credential: ProviderCredential,
    ): ProviderHttpRequest {
        val messages = buildJsonArray {
            request.system?.takeIf { it.isNotBlank() }?.let { system -> add(textMessage(SYSTEM_ROLE, system)) }
            for (message in request.messages) {
                add(buildJsonObject {
                    put("role", message.role.wireName)
                    if(message.parts.isEmpty()) put("content", if (message.content.isEmpty() && message.toolCalls.isNotEmpty()) JsonNull else JsonPrimitive(message.content))
                    else put("content",buildJsonArray {
                        if(message.content.isNotBlank()) add(buildJsonObject {put("type","text");put("text",message.content)})
                        message.parts.forEach {part -> add(buildJsonObject {
                            when(part) {
                                is ProviderContentPart.Text -> {put("type","text");put("text",part.source?.let {"来源：$it\n"}.orEmpty()+part.text)}
                                is ProviderContentPart.Image -> {
                                    val bytes=requireNotNull(request.attachmentPayloads?.resolve(part)) {"ATTACHMENT_UNAVAILABLE"}
                                    put("type","image_url");put("image_url",buildJsonObject {put("url","data:${part.mimeType};base64,"+java.util.Base64.getEncoder().encodeToString(bytes))})
                                }
                                is ProviderContentPart.Document -> error("DOCUMENT_PROTOCOL_UNSUPPORTED")
                            }
                        }) }
                    })
                    message.toolCallId?.let { put("tool_call_id", it) }
                    if (message.toolCalls.isNotEmpty()) put("tool_calls", buildJsonArray {
                        message.toolCalls.forEach { call -> add(buildJsonObject {
                            put("id", call.id)
                            put("type", "function")
                            put("function", buildJsonObject { put("name", call.name); put("arguments", call.arguments) })
                        }) }
                    })
                })
            }
        }
        val payload = buildJsonObject {
            put("model", config.model)
            put("stream", true)
            put("messages", messages)
            put("stream_options", buildJsonObject { put("include_usage", true) })
            if (request.thinkingDisabled && type == ProviderType.DEEPSEEK) {
                put("thinking", buildJsonObject { put("type", "disabled") })
            }
            if (request.tools.isNotEmpty()) put("tools", buildJsonArray {
                request.tools.forEach { tool -> add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.parameters)
                    })
                }) }
            })
            request.temperature?.let { put("temperature", JsonPrimitive(it)) }
            request.maxOutputTokens?.let { put("max_tokens", JsonPrimitive(it)) }
        }
        // preflight 已经保证有凭据；密钥只出现在协议规定的 Header 里
        val apiKey = credential.reveal().orEmpty()
        return ProviderHttpRequest(
            url = config.resolvePath(relativePath),
            headers = listOf(
                ProviderHeader(AUTHORIZATION_HEADER, BEARER_PREFIX + apiKey),
                ProviderHeader(CONTENT_TYPE_HEADER, JSON_CONTENT_TYPE),
                ProviderHeader(ACCEPT_HEADER, EVENT_STREAM_CONTENT_TYPE),
            ),
            body = payload.toString(),
            allowInsecureHttp = config.allowInsecureHttp,
        )
    }

    override fun newDecoder(): SseStreamDecoder = OpenAiSseDecoder(type)
    override fun newDecoder(request: ChatRequest): SseStreamDecoder =
        OpenAiSseDecoder(type, request.tools.map { it.name }.toSet())

    private fun textMessage(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    companion object {
        /** 相对路径：Base URL 已经包含 /v1，这里不得再写一遍。 */
        const val ENDPOINT = "chat/completions"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        private const val CONTENT_TYPE_HEADER = "Content-Type"
        private const val ACCEPT_HEADER = "Accept"
        private const val JSON_CONTENT_TYPE = "application/json"
        private const val EVENT_STREAM_CONTENT_TYPE = "text/event-stream"
        private const val SYSTEM_ROLE = "system"
    }
}

/** OpenAI 兼容协议的流式解码器。 */
internal class OpenAiSseDecoder(
    private val type: ProviderType,
    private val allowedToolNames: Set<String> = emptySet(),
) : SseStreamDecoder {
    private data class PartialCall(var id: String = "", var name: String = "", var arguments: String = "")
    private val calls = sortedMapOf<Int, PartialCall>()
    private var finished = false

    override fun onEvent(event: SseEvent): List<SseAction> {
        val payload = event.data.trim()
        if (payload.isEmpty()) return listOf(SseAction.Ignored)
        if (payload == DONE_MARKER) {
            if (calls.isNotEmpty() && !finished) return unsupported("工具调用没有完整终态")
            return listOf(SseAction.Terminator)
        }
        val root = Json.parseToJsonElement(payload) as? JsonObject
            ?: return unsupported("流式数据不是合法的 JSON 对象")
        val errorObject = root["error"]
        if (errorObject != null && errorObject !is JsonNull) {
            if (errorObject !is JsonObject || !isOptionalString(errorObject["type"]) ||
                !isOptionalString(errorObject["message"])
            ) return unsupported("error 字段结构无效")
            return listOf(SseAction.Error(streamError(errorObject, payload)))
        }

        val actions = mutableListOf<SseAction>()
        if (!isValidUsage(root["usage"])) return unsupported("usage 字段结构无效")
        usageOf(root["usage"])?.let { actions += SseAction.Usage(it) }

        val choices = root["choices"]
        if (choices == null) {
            if (actions.isEmpty()) actions += SseAction.Note("分块里没有 choices 字段，已忽略")
            return actions
        }
        if (choices !is JsonArray) return unsupported("choices 不是数组")
        if (choices.isEmpty()) {
            if (actions.isEmpty()) actions += SseAction.Note("choices 是空数组，已忽略")
            return actions
        }
        val choice = choices[0] as? JsonObject ?: return unsupported("choices[0] 不是 JSON 对象")

        for (field in listOf("delta", "message")) {
            val value = choice[field]
            if (value != null && value !is JsonNull && value !is JsonObject) {
                return unsupported("消息增量字段结构无效")
            }
        }
        if (!isOptionalString(choice["finish_reason"])) return unsupported("finish_reason 字段类型无效")
        val finishReason = (choice["finish_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (finishReason != null && FinishReason.fromWireName(finishReason) == FinishReason.UNKNOWN) {
            return unsupported("服务端返回了未知或不支持的终态")
        }
        val container = (choice["delta"] as? JsonObject) ?: (choice["message"] as? JsonObject)
        if (container != null) {
            if (container.containsKey("function_call")) return unsupported("不支持旧版 function_call 字段")
            if (finished && (container["tool_calls"] != null ||
                    (container["content"] as? JsonPrimitive)?.content.orEmpty().isNotEmpty())) {
                return unsupported("终态之后仍有消息增量")
            }
            container["tool_calls"]?.let { toolCalls ->
                val problem = accumulateTools(toolCalls)
                if (problem != null) return unsupported(problem)
                actions += SseAction.ToolFragment
            }
            when (val content = container["content"]) {
                null -> Unit
                is JsonNull -> Unit
                is JsonPrimitive -> {
                    if (!content.isString) return unsupported("content 不是字符串")
                    if (content.content.isNotEmpty()) actions += SseAction.Delta(content.content)
                }
                is JsonArray -> return unsupported("content 是数组（多模态内容）：本阶段不支持，已停止本次流")
                else -> return unsupported("content 结构不受支持")
            }
            if (container.containsKey("reasoning_content")) {
                if (!isOptionalString(container["reasoning_content"])) {
                    return unsupported("reasoning_content 字段类型无效")
                }
                actions += SseAction.Note("服务端返回了 reasoning_content 字段：本阶段不展示推理内容")
            }
        }
        (choice["finish_reason"] as? JsonPrimitive)?.takeIf { it.isString }?.let { reason ->
            if (finished) return unsupported("服务端重复返回终态")
            if (reason.content == "tool_calls") {
                if (calls.isEmpty()) return unsupported("工具调用终态没有调用")
                val completed = calls.values.map { ProviderToolCall(it.id, it.name, it.arguments) }
                if (calls.keys.toList() != (0 until calls.size).toList() || completed.any {
                        it.id.isBlank() || it.name !in allowedToolNames ||
                            runCatching { Json.parseToJsonElement(it.arguments) !is JsonObject }.getOrDefault(true)
                    } || completed.map { it.id }.distinct().size != completed.size) {
                    return unsupported("工具调用不完整、未授权或参数不是 JSON 对象")
                }
                actions += SseAction.ToolCalls(completed)
            } else if (calls.isNotEmpty()) return unsupported("工具调用与终态不一致")
            finished = true
            actions += SseAction.Finish(FinishReason.fromWireName(reason.content))
        }
        return actions
    }

    private fun accumulateTools(element: JsonElement): String? {
        if (allowedToolNames.isEmpty()) return "请求没有声明工具，拒绝服务端工具调用"
        val array = element as? JsonArray ?: return "tool_calls 不是数组"
        if (array.isEmpty()) return "tool_calls 是空数组"
        for (value in array) {
            val obj = value as? JsonObject ?: return "工具调用不是 JSON 对象"
            val index = jsonInt(obj["index"])?.takeIf { it in 0 until MAX_TOOL_CALLS }
                ?: return "工具调用 index 无效"
            val wireType = obj["type"]
            if (!isOptionalString(wireType) || (wireType is JsonPrimitive && wireType.isString && wireType.content != "function")) {
                return "工具调用 type 不受支持"
            }
            val function = obj["function"]
            if (function != null && function !is JsonNull && function !is JsonObject) return "工具 function 结构无效"
            val id = obj["id"]
            val name = (function as? JsonObject)?.get("name")
            val arguments = (function as? JsonObject)?.get("arguments")
            if (!isOptionalString(id) || !isOptionalString(name) || !isOptionalString(arguments)) return "工具调用增量不是字符串"
            val partial = calls.getOrPut(index) { PartialCall() }
            partial.id += (id as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            partial.name += (name as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            partial.arguments += (arguments as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
            if (partial.id.length > 256 || partial.name.length > 64 || partial.arguments.length > MAX_ARGUMENT_LENGTH) {
                return "工具调用超过长度上限"
            }
        }
        return null
    }

    private fun isOptionalString(element: JsonElement?): Boolean =
        element == null || element is JsonNull || (element is JsonPrimitive && element.isString)

    private fun isValidUsage(element: JsonElement?): Boolean {
        if (element == null || element is JsonNull) return true
        if (element !is JsonObject) return false
        return listOf("prompt_tokens", "completion_tokens").all { field ->
            val value = element[field]
            value == null || value is JsonNull || jsonInt(value)?.let { it >= 0 } == true
        }
    }

    private fun usageOf(element: JsonElement?): TokenUsage? {
        val obj = element as? JsonObject ?: return null
        val input = jsonInt(obj["prompt_tokens"])
        val output = jsonInt(obj["completion_tokens"])
        if (input == null && output == null) return null
        return TokenUsage(inputTokens = input, outputTokens = output)
    }

    private fun streamError(errorObject: JsonObject, payload: String): ProviderError {
        val wireType = (errorObject["type"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val kind = when (wireType) {
            "invalid_api_key", "authentication_error", "permission_error" -> ProviderErrorKind.UNAUTHORIZED
            "insufficient_quota", "rate_limit_error" -> ProviderErrorKind.RATE_LIMITED
            "server_error", "overloaded_error" -> ProviderErrorKind.SERVER_ERROR
            else -> ProviderErrorKind.PROTOCOL
        }
        return ProviderError.of(kind, type, detail = errorDetailOf(payload))
    }

    private fun errorDetailOf(payload: String): String? {
        val token = ErrorBodyProbe.tokenOf(payload, emptyList()) ?: return null
        return DiagnosticText.safe("服务端错误标记：" + token, emptyList())
    }

    private fun unsupported(detail: String): List<SseAction> = listOf(SseAction.Unsupported(detail))

    private companion object {
        const val DONE_MARKER = "[DONE]"
        const val MAX_TOOL_CALLS = 16
        const val MAX_ARGUMENT_LENGTH = 131_072
    }
}
