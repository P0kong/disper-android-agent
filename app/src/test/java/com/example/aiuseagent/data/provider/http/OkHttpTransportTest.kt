package com.example.aiuseagent.data.provider.http

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 单测里一律使用虚构凭据：真实 API Key 绝不进入仓库。 */
private const val FAKE_API_KEY = "test-key-not-secret"

/**
 * 用真实 socket（MockWebServer 监听 127.0.0.1）验证 [OkHttpTransport]：请求组装、分块读取、
 * 有界错误正文、Request-Id/Retry-After 解析，以及「协程取消必须真的取消底层 Call」。
 *
 * 只连本机回环地址，凭据是虚构串，不会访问任何真实服务。
 */
class OkHttpTransportTest {

    private lateinit var server: MockWebServer

    private val requestBody = "{\"model\":\"test-model\",\"stream\":true}"

    @Before
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun stopServer() {
        server.close()
    }

    private fun request(path: String = "/v1/chat/completions") = ProviderHttpRequest(
        url = server.url(path).toString(),
        headers = listOf(
            ProviderHeader("Authorization", "Bearer " + FAKE_API_KEY),
            ProviderHeader("Content-Type", "application/json"),
        ),
        body = requestBody,
        allowInsecureHttp = true,
    )

    private fun timeouts() = HttpTimeouts(connectMillis = 5_000, readMillis = 5_000, totalMillis = 10_000)

    private fun sseResponse(body: String): MockResponse =
        MockResponse(200, Headers.headersOf("Content-Type", "text/event-stream"), body)

    private suspend fun readAll(response: StreamingHttpResponse): String {
        val builder = StringBuilder()
        while (true) {
            val chunk = response.readChunk() ?: break
            builder.append(String(chunk, Charsets.UTF_8))
        }
        return builder.toString()
    }

    @Test
    fun sendsPostRequestWithHeadersAndReadsTheBodyInChunks() = runBlocking {
        server.enqueue(sseResponse("data: hello\n\n"))
        val transport = OkHttpTransport()

        val text = transport.open(request(), timeouts()).use { response ->
            assertEquals(200, response.status)
            assertNull("成功响应不解析 Retry-After", response.retryAfterMillis)
            readAll(response)
        }
        assertEquals("data: hello\n\n", text)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/v1/chat/completions", recorded.target)
        assertEquals("Bearer " + FAKE_API_KEY, recorded.headers["Authorization"])
        assertTrue(
            "Content-Type 必须是 application/json（OkHttp 会补 charset）",
            recorded.headers["Content-Type"]?.startsWith("application/json") == true,
        )
        assertEquals(requestBody, recorded.body!!.utf8())
    }

    @Test
    fun readsRequestIdFromEitherHeaderSpelling() = runBlocking {
        server.enqueue(MockResponse(200, Headers.headersOf("x-request-id", "req-x"), "data: a\n\n"))
        server.enqueue(MockResponse(200, Headers.headersOf("request-id", "req-legacy"), "data: a\n\n"))
        val transport = OkHttpTransport()

        transport.open(request(), timeouts()).use { assertEquals("req-x", it.requestId) }
        transport.open(request(), timeouts()).use { assertEquals("req-legacy", it.requestId) }
    }

    @Test
    fun parsesRetryAfterAndReadsTheBoundedErrorBody() = runBlocking {
        server.enqueue(
            MockResponse(
                429,
                Headers.headersOf("Content-Type", "application/json", "Retry-After", "2"),
                "{\"error\":{\"type\":\"rate_limit_error\"}}",
            )
        )
        val transport = OkHttpTransport()

        transport.open(request(), timeouts()).use { response ->
            assertEquals(429, response.status)
            assertEquals(2_000L, response.retryAfterMillis)
            val body = response.readErrorBodyText()
            assertNotNull(body)
            assertTrue("错误正文按有界读取返回", body!!.contains("rate_limit_error"))
        }
    }

    @Test
    fun errorBodyIsAlwaysReadBounded() = runBlocking {
        server.enqueue(
            MockResponse(
                500,
                Headers.headersOf("Content-Type", "application/json"),
                "x".repeat(10_000),
            )
        )
        val transport = OkHttpTransport()

        val body = transport.open(request(), timeouts()).use { it.readErrorBodyText() }
        assertNotNull(body)
        val text = body!!
        assertTrue("错误正文不得为空", text.isNotEmpty())
        assertTrue("错误正文必须被截断读取（上限 4096 字符），实际 " + text.length, text.length <= 4_096)
    }

    @Test
    fun returnsNullAtEndOfStreamAndCloseIsIdempotent() = runBlocking {
        server.enqueue(sseResponse("data: x\n\n"))
        val transport = OkHttpTransport()

        transport.open(request(), timeouts()).use { response ->
            assertNotNull(response.readChunk())
            assertNull("流结束后必须返回 null", response.readChunk())
            response.close()
        }
    }

    @Test
    fun cancellingTheCollectorCancelsTheUnderlyingCall() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .setHeader("Content-Type", "text/event-stream")
                .bodyDelay(30, TimeUnit.SECONDS)
                .body("data: late\n\n")
                .build()
        )
        val listener = CancelCountingListener()
        val transport = OkHttpTransport(
            OkHttpClient.Builder()
                .retryOnConnectionFailure(false)
                .eventListener(listener)
                .build()
        )

        val job = launch(Dispatchers.IO) {
            transport.open(request(), timeouts()).use { it.readChunk() }
        }
        assertNotNull("请求必须已经到达服务端", server.takeRequest(10, TimeUnit.SECONDS))
        job.cancelAndJoin()

        assertTrue("协程必须处于已取消状态", job.isCancelled)
        assertTrue(
            "协程取消必须真的调用 Call.cancel()，实际次数 " + listener.canceledCalls.get(),
            listener.canceledCalls.get() >= 1,
        )
    }

    private class CancelCountingListener : EventListener() {
        val canceledCalls = AtomicInteger()

        override fun canceled(call: Call) {
            canceledCalls.incrementAndGet()
        }
    }
}
