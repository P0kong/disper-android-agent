package com.example.aiuseagent.domain.provider

import java.net.URI

/** 配置校验问题。文案集中在枚举里，避免各处自己拼错误信息。 */
enum class ConfigIssue(val message: String) {
    BLANK_BASE_URL("Base URL 不能为空。"),
    MALFORMED_URL("Base URL 不是合法 URL。"),
    UNSUPPORTED_SCHEME("Base URL 的协议不受支持（真实供应商只允许 http/https）。"),
    INSECURE_HTTP_NOT_ALLOWED("明文 HTTP 默认被拒绝；如确实需要本地调试，请显式打开「允许明文 HTTP」。"),
    MISSING_HOST("Base URL 缺少主机名。"),
    ENDPOINT_IN_BASE_URL("Base URL 只能是 API 前缀（例如 https://host/v1），不能包含具体消息路径（如 chat/completions 或 /messages），否则会重复拼接。"),
    BLANK_MODEL("模型 ID 不能为空。"),
    TIMEOUT_OUT_OF_RANGE("超时时间超出允许范围（1 秒到 10 分钟）。"),
}

sealed interface ConfigValidation {
    val isValid: Boolean
    val issues: List<ConfigIssue>

    data object Valid : ConfigValidation {
        override val isValid: Boolean get() = true
        override val issues: List<ConfigIssue> get() = emptyList()
    }

    data class Invalid(override val issues: List<ConfigIssue>) : ConfigValidation {
        override val isValid: Boolean get() = false

        fun messages(): List<String> = issues.map { it.message }
    }
}

/**
 * 供应商配置（**非敏感**信息）。
 *
 * 关键语义：**Base URL 是完整 API 前缀**，用户填到 `/v1` 为止；
 * 适配层只拼接相对路径（如 `chat/completions`），
 * 因此不会自动补 `/v1`，也不会出现重复拼接或双斜杠。
 *
 * API Key 不在这里：它由 [CredentialStore] 单独持有（P2-A 仅内存）。
 */
data class ProviderConfig(
    val type: ProviderType,
    val baseUrl: String = "",
    val model: String = "",
    /** 默认关闭：明文 HTTP 只允许通过这个显式开关打开。 */
    val allowInsecureHttp: Boolean = false,
    val connectTimeoutMillis: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    val readTimeoutMillis: Int = DEFAULT_READ_TIMEOUT_MS,
    val totalTimeoutMillis: Int = DEFAULT_TOTAL_TIMEOUT_MS,
    val imageCapability: FeatureCapability? = null,
    val connectionProfile: ConnectionProfile? = null,
) {

    override fun toString(): String = "ProviderConfig(type=$type, baseUrl=<omitted>, model=<omitted>)"

    /** 去掉首尾空白与结尾斜杠，便于统一拼接。 */
    fun normalizedBaseUrl(): String = baseUrl.trim().trimEnd('/')

    /** 拼接相对路径。空路径返回 Base URL 本身。 */
    fun resolvePath(relativePath: String): String {
        val base = normalizedBaseUrl()
        val relative = relativePath.trim().trimStart('/')
        if (relative.isEmpty()) return base
        if (base.isEmpty()) return "/" + relative
        return base + "/" + relative
    }

    fun validationIssues(): List<ConfigIssue> {
        val issues = mutableListOf<ConfigIssue>()
        val raw = baseUrl.trim()
        if (raw.isEmpty()) {
            issues += ConfigIssue.BLANK_BASE_URL
        } else {
            val uri = runCatching { URI(raw) }.getOrNull()
            if (uri == null || uri.scheme.isNullOrBlank()) {
                issues += ConfigIssue.MALFORMED_URL
            } else {
                val scheme = uri.scheme.lowercase()
                if (type.protocol == ProviderProtocol.MOCK) {
                    if (scheme != "mock") issues += ConfigIssue.UNSUPPORTED_SCHEME
                } else {
                    when (scheme) {
                        "https" -> Unit
                        "http" -> if (!allowInsecureHttp) issues += ConfigIssue.INSECURE_HTTP_NOT_ALLOWED
                        else -> issues += ConfigIssue.UNSUPPORTED_SCHEME
                    }
                    if (uri.host.isNullOrBlank()) issues += ConfigIssue.MISSING_HOST
                }
                val path = uri.path.orEmpty().lowercase().trimEnd('/')
                if (path.endsWith("chat/completions") || path.endsWith("messages")) {
                    issues += ConfigIssue.ENDPOINT_IN_BASE_URL
                }
            }
        }
        if (model.isBlank()) issues += ConfigIssue.BLANK_MODEL
        val timeouts = listOf(connectTimeoutMillis, readTimeoutMillis, totalTimeoutMillis)
        if (timeouts.any { it < MIN_TIMEOUT_MS || it > MAX_TIMEOUT_MS }) issues += ConfigIssue.TIMEOUT_OUT_OF_RANGE
        return issues
    }

    fun validation(): ConfigValidation {
        val issues = validationIssues()
        return if (issues.isEmpty()) ConfigValidation.Valid else ConfigValidation.Invalid(issues)
    }

    fun isReady(): Boolean = validationIssues().isEmpty()

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 15_000
        const val DEFAULT_READ_TIMEOUT_MS = 60_000
        const val DEFAULT_TOTAL_TIMEOUT_MS = 120_000
        const val MIN_TIMEOUT_MS = 1_000
        const val MAX_TIMEOUT_MS = 600_000

        /**
         * 新建配置时的预填值。
         *
         * - [ProviderType.exampleBaseUrl] 只是预填，必须由用户核对官方文档；
         * - 真实供应商的 [model] **留空**（示例模型名不得冒充默认值，也不得冒充官方最新值）；
         * - MOCK 是本地假数据，因此允许预填示例模型名，便于测试与界面演示。
         */
        fun forType(type: ProviderType): ProviderConfig = ProviderConfig(
            type = type,
            baseUrl = type.exampleBaseUrl,
            model = if (type == ProviderType.MOCK) type.exampleModel else "",
        )
    }
}
