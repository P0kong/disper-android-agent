package com.example.aiuseagent.domain.provider

/**
 * API Key 的**内存值对象**。
 *
 * - 刻意不实现 Serializable / Parcelable：不给它任何被写盘或被 IPC 传递的途径；
 * - [toString] 被重写为不含明文，避免它出现在日志、异常与调试快照里；
 * - [reveal] 只允许 Provider 组装鉴权头时调用，禁止用于 UI 展示、日志或持久化。
 */
class ProviderCredential private constructor(private val secret: String?) {

    val isPresent: Boolean = !secret.isNullOrBlank()

    /** 取回明文，仅用于构造请求头。 */
    fun reveal(): String? = if (isPresent) secret else null

    /** 给界面展示的掩码提示：只暴露长度，不暴露任何字符。 */
    fun maskedHint(): String? = SecretRedaction.maskedHint(secret)

    override fun toString(): String = "ProviderCredential(isPresent=" + isPresent + ")"

    override fun equals(other: Any?): Boolean = other is ProviderCredential && other.secret == secret

    override fun hashCode(): Int = secret?.hashCode() ?: 0

    companion object {
        val Absent: ProviderCredential = ProviderCredential(null)

        fun of(apiKey: String?): ProviderCredential =
            if (apiKey.isNullOrBlank()) Absent else ProviderCredential(apiKey.trim())
    }
}
