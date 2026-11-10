package com.example.aiuseagent.data.provider.http

import com.example.aiuseagent.domain.provider.SecretRedaction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 从**错误正文**里只提取机器可读的短标记。
 *
 * 为什么不去读 message：错误正文由服务端控制，可能回显 API Key 或整段请求内容。
 * 这里只认结构化错误对象里的 type/code，长度受限、字符集受限、再脱敏，
 * 因此错误信息与日志里不可能出现正文原文。
 */
object ErrorBodyProbe {

    private const val MAX_BODY_LENGTH = 8_192
    private const val MAX_TOKEN_LENGTH = 64
    private const val ALLOWED_SYMBOLS = "._-"

    fun tokenOf(body: String?, secrets: Collection<String>): String? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty() || text.length > MAX_BODY_LENGTH) return null
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null
        val nested = root["error"] as? JsonObject
        val candidates = listOf(
            nested?.get("type"),
            nested?.get("code"),
            root["type"],
            root["code"],
        )
        for (candidate in candidates) {
            val raw = (candidate as? JsonPrimitive)?.takeIf { it.isString }?.content ?: continue
            // 先脱敏；不能先替换秘密中的符号，否则后续按实际凭据匹配会失效。
            val token = sanitize(SecretRedaction.scrub(raw, secrets)) ?: continue
            return token
        }
        return null
    }

    private fun sanitize(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty() || value.length > MAX_TOKEN_LENGTH) return null
        // 拒绝不合法的标记而不改写；未持有凭据的协议解码器必须能把原标记交给上层脱敏。
        return value.takeIf { token ->
            token.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in ALLOWED_SYMBOLS }
        }
    }
}

/** JSON 整数读取：非数字（含 null、字符串数字以外的类型）一律返回 null。 */
internal fun jsonInt(element: JsonElement?): Int? {
    val primitive = element as? JsonPrimitive ?: return null
    if (primitive.isString) return null
    return primitive.content.toIntOrNull()
}
