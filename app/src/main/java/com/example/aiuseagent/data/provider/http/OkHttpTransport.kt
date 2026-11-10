package com.example.aiuseagent.data.provider.http

import com.example.aiuseagent.domain.provider.SecretRedaction
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.BufferedSource

/**
 * 真实网络传输层（OkHttp 5）。
 *
 * 三条硬约束（P2-B §5.1 / §7.3）：
 * 1. **取消真实生效**：协程 Job 被取消时立刻 cancel 底层 Call，并把 IOException 重新表达成
 *    CancellationException，绝不把取消伪装成网络错误；
 * 2. **响应必有界且必关闭**：流式响应只按块读取，绝不把无限流读成一个 String；
 * 3. **重试次数完全由上层策略决定**：关闭连接重试、重定向，每个 Call 的请求体只可发送一次。
 */
class OkHttpTransport(
    private val client: OkHttpClient = defaultClient(),
) : HttpTransport {

    override suspend fun open(request: ProviderHttpRequest, timeouts: HttpTimeouts): StreamingHttpResponse =
        suspendCancellableCoroutine { continuation ->
            val call = newCall(request, timeouts)
            // onCompletion 要等阻塞子任务退出才执行；取消 continuation 时则立即掐断 socket。
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val streaming = try {
                        OkHttpStreamingResponse(response, call)
                    } catch (e: Exception) {
                        call.cancel()
                        runCatching { response.close() }
                        if (continuation.isActive) continuation.resumeWithException(e)
                        return
                    }
                    // response 到达与取消之间的竞态：未交付给调用方的响应同样必须关闭。
                    continuation.resume(streaming) { _, value, _ -> runCatching { value.close() } }
                }
            })
        }

    private fun newCall(request: ProviderHttpRequest, timeouts: HttpTimeouts): Call {
        val builder = Request.Builder()
            .url(request.url)
            .post(OneShotBody(request.body.toRequestBody(JSON_MEDIA_TYPE)))
        for (header in request.headers) {
            builder.addHeader(header.name, header.value)
        }
        val perRequestClient = client.newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(timeouts.connectMillis.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.readMillis.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(timeouts.totalMillis.toLong(), TimeUnit.MILLISECONDS)
            .build()
        return perRequestClient.newCall(builder.build())
    }

    private inner class OkHttpStreamingResponse(
        private val response: Response,
        private val call: Call,
    ) : StreamingHttpResponse {

        private val source: BufferedSource = response.body.source()
        private val closed = AtomicBoolean()

        override val status: Int = response.code

        override val requestId: String? =
            SecretRedaction.safeRequestId(response.header(REQUEST_ID_HEADER))
                ?: SecretRedaction.safeRequestId(response.header(REQUEST_ID_HEADER_ALT))

        /**
         * Retry-After 按 HTTP 规范是秒，而 [StreamingHttpResponse.retryAfterMillis] 与
         * [RetryPolicy.delayMillisFor] 都按毫秒处理，因此在这里做一次单位换算。
         */
        override val retryAfterMillis: Long? =
            RetryAfterHeader.parseSeconds(response.header("Retry-After"))?.let { it * MILLIS_PER_SECOND }

        override suspend fun readChunk(): ByteArray? = cancellableRead {
            if (source.exhausted()) return@cancellableRead null
            val available = source.buffer.size
            if (available <= 0L) return@cancellableRead ByteArray(0)
            val count = if (available > MAX_CHUNK_BYTES) MAX_CHUNK_BYTES else available
            source.buffer.readByteArray(count)
        }

        override suspend fun readErrorBodyText(): String? = cancellableRead {
            try {
                response.peekBody(ERROR_BODY_LIMIT).use { body -> body.string() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }

        private suspend fun <T> cancellableRead(block: () -> T): T = withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation {
                    call.cancel()
                    runCatching { close() }
                }
                try {
                    continuation.resume(block())
                } catch (e: Exception) {
                    if (!continuation.isActive || call.isCanceled()) {
                        continuation.cancel(CancellationException("请求已取消"))
                    } else {
                        continuation.resumeWithException(e)
                    }
                }
            }
        }

        override fun close() {
            if (closed.compareAndSet(false, true)) response.close()
        }
    }

    /**
     * OkHttp 对 503 + Retry-After: 0 的 follow-up 不受 retryOnConnectionFailure 控制。
     * 标为 one-shot 让同一个 Call 不得重发 POST；上层重试每次创建全新的 Call。
     * 不改写响应状态、头或正文，上层仍看到服务端的真实 503 和 Retry-After。
     */
    private class OneShotBody(private val delegate: RequestBody) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
        override fun isOneShot(): Boolean = true
    }

    companion object {
        private const val MAX_CHUNK_BYTES = 8_192L
        private const val ERROR_BODY_LIMIT = 4_096L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val REQUEST_ID_HEADER = "x-request-id"
        private const val REQUEST_ID_HEADER_ALT = "request-id"
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()

        /**
         * 默认客户端：不自动重试或重定向；TLS 验证保留 OkHttp 默认值。
         * 超时按请求在 [newCall] 里逐个设置。
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
