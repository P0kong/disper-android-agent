package com.example.aiuseagent.domain.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** 消息角色。system 提示由 [ChatRequest.system] 单独承载，不混在消息列表里。 */
enum class ChatRole(val wireName: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}

data class ProviderToolCall(val id: String, val name: String, val arguments: String) {
    override fun toString(): String = "ProviderToolCall(<omitted>)"
}

data class ToolDefinition(val name: String, val description: String, val parameters: JsonObject) {
    override fun toString(): String = "ToolDefinition(<omitted>)"
}

data class ProviderMessage(
    val role: ChatRole,
    val content: String,
    val toolCalls: List<ProviderToolCall> = emptyList(),
    val toolCallId: String? = null,
    val parts: List<ProviderContentPart> = emptyList(),
) {
    override fun toString(): String = "ProviderMessage(role=$role, content=<omitted>)"
}

enum class RequestIssue(val message: String) {
    NO_MESSAGES("至少需要一条消息。"),
    BLANK_CONTENT("消息内容不能为空。"),
    SYSTEM_ROLE_IN_MESSAGES("system 提示必须放在 ChatRequest.system 字段，不能混在消息列表中。"),
    TEMPERATURE_OUT_OF_RANGE("temperature 必须在 0.0 到 2.0 之间。"),
    MAX_TOKENS_OUT_OF_RANGE("maxOutputTokens 必须在 1 到 100000 之间。"),
    INVALID_TOOL_MESSAGE("工具消息的角色、调用 ID 或参数无效。"),
    INVALID_CONTENT_PART("附件内容、角色或本地副本无效，请重新选择。"),
    INVALID_TOOL_DEFINITION("工具定义名称必须唯一且参数必须是 JSON 对象 schema。"),
    MAX_ATTEMPTS_OUT_OF_RANGE("maxAttempts 必须在 1 到 3 之间。"),
}

/**
 * 一次纯文本或显式声明工具的请求。图像与其他多模态输入仍不支持。
 */
data class ChatRequest(
    val messages: List<ProviderMessage>,
    val system: String? = null,
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
    val tools: List<ToolDefinition> = emptyList(),
    val thinkingDisabled: Boolean = false,
    /** null preserves the provider retry policy; acceptance requests can explicitly use 1. */
    val maxAttempts: Int? = null,
    val attachmentPayloads: ProviderAttachmentPayloads? = null,
) {

    /** 可打印请求不得包含提示词或用户粘贴到消息中的秘密。 */
    override fun toString(): String = "ChatRequest(messages=<omitted>, system=<omitted>)"

    fun validationIssues(): List<RequestIssue> {
        val issues = mutableListOf<RequestIssue>()
        if (messages.isEmpty()) issues += RequestIssue.NO_MESSAGES
        if (messages.any { it.content.isBlank() && it.parts.isEmpty() && !(it.role == ChatRole.ASSISTANT && it.toolCalls.isNotEmpty()) }) {
            issues += RequestIssue.BLANK_CONTENT
        }
        if (messages.any { it.role == ChatRole.SYSTEM }) issues += RequestIssue.SYSTEM_ROLE_IN_MESSAGES
        if(messages.any { message -> message.parts.isNotEmpty() && (message.role!=ChatRole.USER || message.parts.any { part ->
            when(part) {
                is ProviderContentPart.Text -> part.text.isBlank() || part.text.length>400_000 || (part.source?.length ?: 0)>1024
                is ProviderContentPart.Image -> attachmentPayloads?.resolve(part)==null
                is ProviderContentPart.Document -> true // No native document protocol has been implemented.
            }
        }) } || attachmentPayloads?.current()==false) issues+=RequestIssue.INVALID_CONTENT_PART
        temperature?.let { if (!it.isFinite() || it < 0.0 || it > 2.0) issues += RequestIssue.TEMPERATURE_OUT_OF_RANGE }
        maxOutputTokens?.let { if (it < 1 || it > 100_000) issues += RequestIssue.MAX_TOKENS_OUT_OF_RANGE }
        maxAttempts?.let { if (it !in 1..3) issues += RequestIssue.MAX_ATTEMPTS_OUT_OF_RANGE }
        if (tools.map { it.name }.distinct().size != tools.size || tools.any {
                !TOOL_NAME.matches(it.name) || it.parameters["type"]?.toString() != "\"object\""
            }) issues += RequestIssue.INVALID_TOOL_DEFINITION
        if (messages.any { message ->
                (message.role == ChatRole.TOOL) != !message.toolCallId.isNullOrBlank() ||
                    (message.toolCalls.isNotEmpty() && message.role != ChatRole.ASSISTANT) ||
                    message.toolCalls.any { call ->
                        call.id.isBlank() || !TOOL_NAME.matches(call.name) ||
                            runCatching { Json.parseToJsonElement(call.arguments) !is JsonObject }.getOrDefault(true)
                    } || message.toolCalls.map { it.id }.distinct().size != message.toolCalls.size
            }) issues += RequestIssue.INVALID_TOOL_MESSAGE
        return issues
    }

    companion object {
        private val TOOL_NAME = Regex("[A-Za-z0-9_-]{1,64}")
        fun ofUserText(text: String): ChatRequest =
            ChatRequest(messages = listOf(ProviderMessage(ChatRole.USER, text)))
    }
}
