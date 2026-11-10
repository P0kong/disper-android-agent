package com.example.aiuseagent.domain.provider

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLException

/**
 * 错误分类。它是「用户看到什么」「是否可重试」的唯一来源，
 * 不允许各 Provider 自己拼错误文案。
 */
enum class ProviderErrorKind(val userMessage: String, val retryable: Boolean) {
    INVALID_CONFIG("供应商配置无效：请检查 Base URL、模型名和协议。", false),
    MISSING_API_KEY("缺少 API Key：请先在设置页填写。", false),
    UNAUTHORIZED("鉴权失败：API Key 无效或没有访问权限。", false),
    RATE_LIMITED("请求被限流或额度不足，请稍后再试。", true),
    TIMEOUT("请求超时：网络太慢或服务端长时间无响应。", true),
    NETWORK("网络不可用或连接被拒绝。", true),
    SERVER_ERROR("服务端错误，请稍后重试。", true),
    PROTOCOL("响应格式不符合所选协议，无法解析。", false),
    EMPTY_RESPONSE("模型返回了空内容。", false),
    CANCELLED("请求已取消。", false),
    NOT_IMPLEMENTED("该供应商类型还没有注册任何适配器（尚未实现）。", false),
    UNKNOWN("未知错误。", false),
}

/**
 * 结构化错误。[detail] 和 [requestId] 在离开 Provider 前必须脱敏（见 [redact]）。
 * 刻意不保存请求体、提示词或响应正文。
 */
data class ProviderError(
    val kind: ProviderErrorKind,
    val detail: String? = null,
    val httpStatus: Int? = null,
    val requestId: String? = null,
    val providerType: ProviderType? = null,
) {

    val userMessage: String
        get() = kind.userMessage

    val retryable: Boolean
        get() = kind.retryable

    /** 即使没有 detail 也清洗关联 ID；返回新的对象，不改原对象。 */
    fun redact(vararg secrets: String): ProviderError = copy(
        detail = SecretRedaction.safeText(detail, secrets.toList()),
        requestId = SecretRedaction.safeRequestId(requestId, secrets.toList()),
    )

    /** 只包含可安全记录/展示的字段。 */
    override fun toString(): String = buildString {
        append("ProviderError(kind=").append(kind.name)
        append(", provider=").append(providerType?.name ?: "-")
        append(", httpStatus=").append(httpStatus?.toString() ?: "-")
        // 防止调用方忘记 redact 或直接调用构造函数时，可打印对象回显自由文本。
        append(", hasRequestId=").append(requestId != null)
        append(", hasDetail=").append(detail != null)
        append(")")
    }

    companion object {

        fun of(
            kind: ProviderErrorKind,
            providerType: ProviderType? = null,
            httpStatus: Int? = null,
            requestId: String? = null,
            detail: String? = null,
        ): ProviderError = ProviderError(
            kind = kind,
            // 保留原文直到调用方带实际凭据 redact，避免先改写符号后无法匹配秘密。
            detail = detail?.takeIf { it.isNotBlank() },
            httpStatus = httpStatus,
            requestId = SecretRedaction.safeRequestId(requestId),
            providerType = providerType,
        )

        /**
         * HTTP 状态码 → 错误分类。
         * 401/403 鉴权失败；429 限流；其余 4xx 视为协议/配置问题；5xx 服务端错误。
         */
        fun fromHttpStatus(status: Int, providerType: ProviderType?, requestId: String? = null): ProviderError {
            val kind = when (status) {
                401, 403 -> ProviderErrorKind.UNAUTHORIZED
                429 -> ProviderErrorKind.RATE_LIMITED
                408, 504 -> ProviderErrorKind.TIMEOUT
                in 500..599 -> ProviderErrorKind.SERVER_ERROR
                in 400..499 -> ProviderErrorKind.PROTOCOL
                else -> ProviderErrorKind.UNKNOWN
            }
            return of(kind = kind, providerType = providerType, httpStatus = status, requestId = requestId)
        }
    }
}

/**
 * 异常 → 结构化错误。
 *
 * 注意：本映射**不**尝试从异常文本里猜出 API Key 之外的信息；
 * 调用方仍需用 [ProviderError.redact] 清洗 detail。
 */
object ProviderErrorMapper {

    fun fromThrowable(throwable: Throwable, providerType: ProviderType? = null): ProviderError = when (throwable) {
        is SocketTimeoutException, is TimeoutException ->
            ProviderError.of(ProviderErrorKind.TIMEOUT, providerType, detail = throwable.message)

        is UnknownHostException ->
            ProviderError.of(ProviderErrorKind.NETWORK, providerType, detail = throwable.message)

        is SSLException ->
            ProviderError.of(ProviderErrorKind.NETWORK, providerType, detail = throwable.message)

        is IOException ->
            ProviderError.of(ProviderErrorKind.NETWORK, providerType, detail = throwable.message)

        else ->
            ProviderError.of(ProviderErrorKind.UNKNOWN, providerType, detail = throwable.message)
    }
}
