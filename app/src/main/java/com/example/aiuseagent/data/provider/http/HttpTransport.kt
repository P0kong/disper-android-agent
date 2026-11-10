package com.example.aiuseagent.data.provider.http

import java.io.Closeable
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * 一个请求头。值可能是秘密（Authorization / x-api-key），
 * 因此它的值**禁止**进入日志、异常信息或诊断记录。
 */
data class ProviderHeader(val name: String, val value: String) {
    // 连头名称也不回显：调用方提供的任意字符串都可能包含凭据。
    override fun toString(): String = "ProviderHeader(name=<omitted>, value=<omitted>)"
}

/** 协议层组装好的一个 HTTP 请求（还没有发出去）。 */
data class ProviderHttpRequest(
    val url: String,
    val headers: List<ProviderHeader>,
    val body: String,
    /** 只有显式打开时才允许明文 HTTP；默认只在 HTTPS 上发请求。 */
    val allowInsecureHttp: Boolean = false,
) {
    override fun toString(): String =
        "ProviderHttpRequest(url=<omitted>, headers=<omitted>, body=<omitted>, allowInsecureHttp=$allowInsecureHttp)"
}

/** 单次请求的超时（来自 ProviderConfig，默认 15s / 60s / 120s）。 */
data class HttpTimeouts(
    val connectMillis: Int,
    val readMillis: Int,
    val totalMillis: Int,
)

/**
 * 已经打开的流式响应。
 *
 * **必须** close：无论读到流末尾还是中途 break，都要关闭底层连接，
 * 否则连接池与 socket 会被泄漏。
 */
interface StreamingHttpResponse : Closeable {

    val status: Int

    /** 服务端返回的请求关联 ID（没有就是 null）。 */
    val requestId: String?

    /** Retry-After 头解析出来的等待毫秒数（没有就返回 null）。 */
    val retryAfterMillis: Long?

    /** 阻塞读取下一段字节（实现内部切到 IO 线程）；流结束返回 null。 */
    suspend fun readChunk(): ByteArray?

    /** 非 2xx 时读取**有界**错误正文（最多 4 KiB），只用于提取机器可读的短标记。 */
    suspend fun readErrorBodyText(): String?
}

/**
 * HTTP 传输层边界。
 *
 * 只做「发请求 + 读字节」两件事：状态码分类、SSE 解析、重试策略都在上层，
 * 这样传输层可以被替换（例如测试里的假传输层）而不影响协议语义。
 */
interface HttpTransport {
    suspend fun open(request: ProviderHttpRequest, timeouts: HttpTimeouts): StreamingHttpResponse
}

/** Retry-After 解析：秒数或 HTTP-date；无法解析时返回 null。 */
object RetryAfterHeader {

    private const val MAX_SECONDS = 3_600L

    fun parseSeconds(raw: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
        val value = raw?.trim().orEmpty()
        if (value.isEmpty()) return null
        value.toLongOrNull()?.let { return it.coerceIn(0L, MAX_SECONDS) }
        val instant = runCatching {
            DateTimeFormatter.RFC_1123_DATE_TIME.parse(value, Instant::from)
        }.getOrNull() ?: return null
        val targetMillis = runCatching { instant.toEpochMilli() }.getOrNull() ?: return null
        if (targetMillis <= nowMillis) return 0L
        // 先比较再相减；极端 nowMillis 输入也不能让减法溢出后变成负等待。
        val delta = runCatching { Math.subtractExact(targetMillis, nowMillis) }
            .getOrElse { return MAX_SECONDS }
        if (delta >= MAX_SECONDS * 1_000L) return MAX_SECONDS
        // 与数字形式一样返回秒，亚秒向上取整，调用方只换算一次毫秒。
        return (delta + 999L) / 1_000L
    }
}

/** URL 安全策略：真实供应商默认只允许 HTTPS，明文 HTTP 必须显式打开。 */
object HttpUrlPolicy {

    fun schemeOf(url: String): String? = runCatching { java.net.URI(url) }.getOrNull()?.scheme?.lowercase()

    fun isAllowed(url: String, allowInsecureHttp: Boolean): Boolean {
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return false
        if (uri.host.isNullOrBlank()) return false
        return when (uri.scheme?.lowercase()) {
            "https" -> true
            "http" -> allowInsecureHttp
            else -> false
        }
    }
}
