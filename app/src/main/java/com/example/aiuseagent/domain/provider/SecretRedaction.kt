package com.example.aiuseagent.domain.provider

/**
 * 秘密脱敏工具。日志与错误信息在离开 Provider 之前必须过这一层。
 */
object SecretRedaction {

    const val MASK = "***"

    /**
     * 过短的字符串不当作秘密替换，避免把普通文本打满掩码。
     * 同时也是「多长才算 API Key」的下限，由凭据存储复用，避免两处各写一个魔数。
     */
    const val MIN_SECRET_LENGTH = 4

    /** 服务端关联 ID 只允许短 ASCII 标识；不能承载任意服务端文本。 */
    const val MAX_REQUEST_ID_LENGTH = 128
    const val MAX_SAFE_TEXT_LENGTH = 200

    private const val SAFE_TEXT_SYMBOLS = " ._:/-,;()=+*"

    fun scrub(text: String?, secrets: Collection<String>): String {
        val source = text ?: return ""
        var result = source
        for (secret in secrets.filter { it.isNotBlank() && it.length >= MIN_SECRET_LENGTH }
            .distinct().sortedByDescending { it.length }) {
            result = result.replace(secret, MASK)
        }
        return result
    }

    fun scrub(text: String?, vararg secrets: String): String = scrub(text, secrets.toList())

    /** 先按实际凭据清洗，命中秘密的 ID 整体丢弃，再检查长度及严格字符集。 */
    fun safeRequestId(text: String?, secrets: Collection<String> = emptyList()): String? {
        val raw = text ?: return null
        val scrubbed = scrub(raw, secrets)
        if (scrubbed != raw) return null
        val value = scrubbed.trim()
        if (value.isEmpty() || value.length > MAX_REQUEST_ID_LENGTH) return null
        return value.takeIf { id ->
            id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._:-" }
        }
    }

    /** 先清洗秘密，再清洗控制字符/符号并截断；顺序不能反过来。 */
    fun safeText(
        text: String?,
        secrets: Collection<String>,
        maxLength: Int = MAX_SAFE_TEXT_LENGTH,
    ): String? {
        require(maxLength > 0) { "诊断文本长度上限必须为正数" }
        val raw = text ?: return null
        val scrubbed = scrub(raw, secrets)
        val cleaned = buildString {
            for (ch in scrubbed) {
                append(if (ch.isLetterOrDigit() || ch in SAFE_TEXT_SYMBOLS) ch else '?')
            }
        }.trim()
        return cleaned.takeIf { it.isNotEmpty() }?.take(maxLength)
    }

    /** 只暴露长度，不暴露任何字符（连前几位都不给）。 */
    fun maskedHint(secret: String?): String? {
        val value = secret?.trim().orEmpty()
        if (value.isEmpty()) return null
        return "****（共 " + value.length + " 位）"
    }
}
