package com.example.aiuseagent.data.provider.http

import com.example.aiuseagent.data.provider.openai.OpenAiCompatibleProvider
import com.example.aiuseagent.domain.provider.ChatRequest
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderCredential
import com.example.aiuseagent.domain.provider.ProviderErrorKind
import com.example.aiuseagent.domain.provider.ProviderEvent
import com.example.aiuseagent.domain.provider.ProviderType
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertPathValidatorException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.Collections
import java.util.IdentityHashMap
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockResponseBody
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val TRANSPORT_TEST_KEY = "transport-fake-credential"

/** 真实本机 socket 回归；不访问供应商、不使用真实凭据。 */
class TransportHardeningTest {
    private fun request(server: MockWebServer) = ProviderHttpRequest(
        url = server.url("/v1/chat/completions").toString(),
        headers = listOf(
            ProviderHeader("Authorization", "Bearer $TRANSPORT_TEST_KEY"),
            ProviderHeader("x-api-key", TRANSPORT_TEST_KEY),
        ),
        body = "{}",
        allowInsecureHttp = true,
    )

    private fun timeouts() = HttpTimeouts(5_000, 30_000, 40_000)

    private fun config(server: MockWebServer) = ProviderConfig(
        type = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = server.url("/v1").toString(),
        model = "local-test-model",
        allowInsecureHttp = true,
    )

    private fun provider(transport: HttpTransport, attempts: Int) = OpenAiCompatibleProvider(
        type = ProviderType.OPENAI_COMPATIBLE,
        transport = transport,
        retryPolicy = RetryPolicy(maxAttempts = attempts),
        backoff = {},
    )

    @Test
    fun redirectsNeverSendTheCredentialToAnotherHostOrPort() = runBlocking {
        MockWebServer().use { source ->
            MockWebServer().use { target ->
                source.start()
                target.start()
                // 即使调用方传入默认允许重定向的 client，传输层也必须收紧它。
                val transport = OkHttpTransport(OkHttpClient())
                for (code in listOf(301, 302, 307, 308)) {
                    val destination = target.url("/collect").newBuilder().host("127.0.0.1").build()
                    source.enqueue(MockResponse(code, Headers.headersOf("Location", destination.toString()), "moved"))
                    transport.open(request(source), timeouts()).use { response ->
                        assertEquals(code, response.status)
                    }
                    val original = source.takeRequest()
                    assertEquals("Bearer $TRANSPORT_TEST_KEY", original.headers["Authorization"])
                    assertEquals(TRANSPORT_TEST_KEY, original.headers["x-api-key"])
                    assertNull("重定向目标不得收到任何请求或凭据", target.takeRequest(100, TimeUnit.MILLISECONDS))
                }
                assertEquals(0, target.requestCount)
            }
        }
    }

    @Test
    fun httpsRedirectCannotDowngradeOrForwardCredentialsToPlainHttp() = runBlocking {
        withTestTls { sslContext, trustManager ->
            MockWebServer().use { source ->
                MockWebServer().use { target ->
                    source.useHttps(sslContext.socketFactory)
                    source.start()
                    target.start()
                    target.enqueue(MockResponse(200, Headers.headersOf(), "must-not-be-reached"))
                    val client = OkHttpClient.Builder()
                        .sslSocketFactory(sslContext.socketFactory, trustManager)
                        .followRedirects(true)
                        .followSslRedirects(true)
                        .build()
                    for (code in listOf(301, 302, 307, 308)) {
                        source.enqueue(MockResponse(code, Headers.headersOf("Location", target.url("/collect").toString()), "moved"))
                        OkHttpTransport(client).open(request(source), timeouts()).use {
                            assertEquals(code, it.status)
                        }
                        val original = source.takeRequest(5, TimeUnit.SECONDS)
                        assertNotNull(original)
                        assertEquals("Bearer $TRANSPORT_TEST_KEY", original!!.headers["Authorization"])
                        assertEquals(TRANSPORT_TEST_KEY, original.headers["x-api-key"])
                        assertNull("HTTPS 到 HTTP 的目标不得收到凭据", target.takeRequest(100, TimeUnit.MILLISECONDS))
                    }
                    assertEquals(0, target.requestCount)
                }
            }
        }
    }

    @Test
    fun productionTlsTrustStillRejectsAnUntrustedLocalCertificate() = runBlocking {
        withTestTls { sslContext, _ ->
            MockWebServer().use { server ->
                server.useHttps(sslContext.socketFactory)
                server.start(InetAddress.getByName("127.0.0.1"), 0)
                server.enqueue(MockResponse(200, Headers.headersOf(), "must-not-be-trusted"))
                val listener = StageListener()
                val transport = OkHttpTransport(OkHttpTransport.defaultClient().newBuilder().eventListener(listener).build())
                try {
                    // 单一 IPv4 route 避免 localhost 的其他地址失败覆盖首次 TLS 证书异常。
                    val spec = request(server).copy(
                        url = server.url("/v1/chat/completions").newBuilder().host("127.0.0.1").build().toString(),
                    )
                    transport.open(spec, timeouts()).close()
                    fail("生产默认 TLS 必须拒绝测试自签名证书")
                } catch (e: IOException) {
                    val causes = exceptionGraph(e)
                    val classes = causes.joinToString(" -> ") { it.javaClass.name }
                    assertTrue("必须已经进入 TLS 握手；异常类型：$classes", listener.secureConnect.count == 0L)
                    assertTrue(
                        "必须因证书验证失败；异常类型：$classes",
                        causes.any { it is SSLHandshakeException } &&
                            causes.any { it is CertificateException || it is CertPathValidatorException },
                    )
                }
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test
    fun redirectStatusesAreFailuresRatherThanSuccessfulTextStreams() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            for (code in listOf(301, 302, 307, 308)) {
                server.enqueue(MockResponse(code, Headers.headersOf("Location", server.url("/other").toString()), ""))
                val events = provider(OkHttpTransport(), 1)
                    .stream(ChatRequest.ofUserText("hello"), config(server), ProviderCredential.of(TRANSPORT_TEST_KEY))
                    .toList()
                assertEquals(1, events.filterIsInstance<ProviderEvent.Failed>().size)
                assertTrue(events.none { it is ProviderEvent.Completed || it is ProviderEvent.TextDelta })
            }
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun oneCallPreserves503AndRetryAfterZeroWithoutAnInternalFollowUp() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(503, Headers.headersOf("Retry-After", "0", "x-request-id", "req-503"), "busy"))
            server.enqueue(MockResponse(200, Headers.headersOf(), "must-not-be-read"))
            OkHttpTransport().open(request(server), timeouts()).use { response ->
                assertEquals(503, response.status)
                assertEquals(0L, response.retryAfterMillis)
                assertEquals("req-503", response.requestId)
                assertEquals("busy", response.readErrorBodyText())
            }
            assertEquals(1, server.requestCount)
            assertNotNull(server.takeRequest(1, TimeUnit.SECONDS))
            assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS))
        }
    }

    @Test
    fun actual503RequestsExactlyMatchTheOuterOneTwoOrThreeAttemptLimit() = runBlocking {
        for (attempts in 1..3) {
            MockWebServer().use { server ->
                server.start()
                repeat(8) { server.enqueue(MockResponse(503, Headers.headersOf("Retry-After", "0"), "busy")) }
                val events = provider(OkHttpTransport(), attempts)
                    .stream(ChatRequest.ofUserText("hello"), config(server), ProviderCredential.of(TRANSPORT_TEST_KEY))
                    .toList()
                val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
                assertEquals(ProviderErrorKind.SERVER_ERROR, failure.error.kind)
                assertEquals(503, failure.error.httpStatus)
                assertEquals(attempts, server.requestCount)
                assertTrue(server.requestCount <= 3)
            }
        }
    }

    @Test
    fun realSocketStreamIsNotRetriedAfterAnyTextDelta() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val body = "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n" +
                "data: {\"error\":{\"type\":\"rate_limit_error\"}}\n\n"
            server.enqueue(MockResponse(200, Headers.headersOf("Content-Type", "text/event-stream"), body))
            server.enqueue(MockResponse(200, Headers.headersOf(), "must-not-be-used"))
            val events = provider(OkHttpTransport(), 3)
                .stream(ChatRequest.ofUserText("hello"), config(server), ProviderCredential.of(TRANSPORT_TEST_KEY))
                .toList()
            assertEquals("partial", events.filterIsInstance<ProviderEvent.TextDelta>().single().text)
            assertEquals(ProviderErrorKind.RATE_LIMITED, events.filterIsInstance<ProviderEvent.Failed>().single().error.kind)
            assertEquals(1, server.requestCount)
            assertTrue(events.none { it is ProviderEvent.Completed })
        }
    }

    @Test
    fun dateRetryAfterIsConvertedToMillisOnlyOnce() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(
                Instant.now().plusSeconds(60).atZone(ZoneOffset.UTC)
            )
            server.enqueue(MockResponse(429, Headers.headersOf("Retry-After", date), "busy"))
            OkHttpTransport().open(request(server), timeouts()).use { response ->
                val millis = response.retryAfterMillis
                assertNotNull(millis)
                assertTrue("日期秒数只能转一次毫秒", millis!! in 50_000L..60_000L)
            }
        }
    }

    @Test
    fun transportDropsMalformedOrOversizedRequestIdsAndAcceptsBoundedAscii() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val valid = "r".repeat(128)
            server.enqueue(MockResponse(200, Headers.headersOf("x-request-id", "r".repeat(129)), ""))
            server.enqueue(MockResponse(200, Headers.headersOf("x-request-id", "bad id", "request-id", "legacy-ok"), ""))
            server.enqueue(MockResponse(200, Headers.headersOf("x-request-id", valid), ""))
            val transport = OkHttpTransport()
            transport.open(request(server), timeouts()).use { assertNull(it.requestId) }
            transport.open(request(server), timeouts()).use { assertEquals("legacy-ok", it.requestId) }
            transport.open(request(server), timeouts()).use { assertEquals(valid, it.requestId) }
        }
    }

    @Test
    fun callbackBodyConstructionFailureCancelsAndFinishesEvenWhenCloseThrows() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse(200, Headers.headersOf(), "unused"))
            val listener = StageListener()
            val closeAttempted = CountDownLatch(1)
            val client = OkHttpClient.Builder().eventListener(listener).addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val realBody = response.body
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = realBody.contentType()
                    override fun contentLength() = realBody.contentLength()
                    override fun source(): BufferedSource = throw IOException("test source failure")
                    override fun close() {
                        try { realBody.close() } finally { closeAttempted.countDown() }
                        throw IOException("test close failure")
                    }
                }).build()
            }.build()
            val result = withTimeout(2_000) {
                runCatching { OkHttpTransport(client).open(request(server), timeouts()).close() }
            }
            assertTrue("source 构造异常必须完成等待", result.exceptionOrNull() is IOException)
            assertEquals("test source failure", result.exceptionOrNull()?.message)
            assertTrue("必须关闭底层 Call", listener.cancelled.await(2, TimeUnit.SECONDS))
            assertTrue("close 异常不能阻止 continuation 收尾", closeAttempted.await(2, TimeUnit.SECONDS))
        }
    }

    @Test
    fun cancellingWhileWaitingForHeadersImmediatelyCancelsTheCall() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().onResponseStart(SocketEffect.Stall).body("late").build())
            val listener = StageListener()
            val networkEntered = CountDownLatch(1)
            val networkReader = AtomicReference<Thread>()
            val client = OkHttpClient.Builder().eventListener(listener).addNetworkInterceptor { chain ->
                networkReader.set(Thread.currentThread())
                networkEntered.countDown()
                chain.proceed(chain.request())
            }.build()
            val transport = OkHttpTransport(client)
            val job = launch(Dispatchers.IO) { transport.open(request(server), timeouts()).use { it.readChunk() } }
            try {
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
                assertTrue("必须进入真实网络拦截器", networkEntered.await(5, TimeUnit.SECONDS))
                assertTrue("必须已经在响应头读取中阻塞于真实 socket", awaitSocketRead(networkReader, duringHeaders = true))
                assertFalse(job.isCompleted)
                job.cancel()
                withTimeout(2_000) { job.join() }
                assertTrue("Call 必须在取消时立即关闭", listener.cancelled.await(2, TimeUnit.SECONDS))
                assertTrue("底层请求任务必须结束", listener.failed.await(2, TimeUnit.SECONDS))
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun cancellingAfterTheBodyReadBlocksClosesCallSourceAndReader() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val bodyWriterEntered = CountDownLatch(1)
            val releaseBodyWriter = CountDownLatch(1)
            // 中途 Stall 可能先发送半个正文，导致 readChunk 返回后 close/discard 才阻塞。
            // 这里在写出第一个正文 byte 前等待，确保取消命中的就是 readChunk。
            server.enqueue(MockResponse.Builder().body(object : MockResponseBody {
                override val contentLength: Long = 4L
                override fun writeTo(sink: BufferedSink) {
                    bodyWriterEntered.countDown()
                    if (!releaseBodyWriter.await(15, TimeUnit.SECONDS)) throw IOException("test body writer timeout")
                    sink.writeUtf8("late")
                }
            }).build())
            val listener = StageListener()
            val readEntered = CountDownLatch(1)
            val sourceClosed = CountDownLatch(1)
            val readExited = CountDownLatch(1)
            val reader = AtomicReference<Thread>()
            val client = OkHttpClient.Builder().eventListener(listener).addNetworkInterceptor { chain ->
                val response = chain.proceed(chain.request())
                val body = response.body
                val trackedSource = object : ForwardingSource(body.source()) {
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        reader.set(Thread.currentThread())
                        readEntered.countDown()
                        return super.read(sink, byteCount)
                    }

                    override fun close() {
                        try { super.close() } finally { sourceClosed.countDown() }
                    }
                }.buffer()
                response.newBuilder().body(object : ResponseBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun source(): BufferedSource = trackedSource
                }).build()
            }.build()
            val transport = OkHttpTransport(client)
            val job = launch(Dispatchers.IO) {
                try { transport.open(request(server), timeouts()).use { it.readChunk() } }
                finally { readExited.countDown() }
            }
            try {
                assertTrue("服务端必须已发送响应头且尚未发送正文", bodyWriterEntered.await(5, TimeUnit.SECONDS))
                assertTrue("必须已经进入 source.read", readEntered.await(5, TimeUnit.SECONDS))
                assertTrue("必须在 cancellableRead 内进入真实 socket 阻塞读取", awaitSocketRead(reader))
                assertEquals(1L, readExited.count)
                job.cancel()
                withTimeout(2_000) { job.join() }
                assertTrue("读取消必须立刻调用 Call.cancel", listener.cancelled.await(2, TimeUnit.SECONDS))
                assertTrue("响应 source 必须关闭", sourceClosed.await(2, TimeUnit.SECONDS))
                assertTrue("读取任务不得遗留", readExited.await(2, TimeUnit.SECONDS))
                assertTrue(job.isCancelled)
            } finally {
                job.cancel()
                releaseBodyWriter.countDown()
            }
        }
    }

    private fun awaitSocketRead(reader: AtomicReference<Thread>, duringHeaders: Boolean = false): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            val stack = reader.get()?.stackTrace.orEmpty()
            val inSocketRead = stack.any {
                    it.className.contains("SocketDispatcher") && it.methodName.startsWith("read") ||
                        it.className.contains("SocketInputStream") && it.methodName.contains("read", ignoreCase = true) ||
                        it.className.contains("NioSocketImpl") && it.methodName.contains("read", ignoreCase = true)
                }
            val inRequiredPhase = if (duringHeaders) {
                stack.any { it.methodName == "readResponseHeaders" }
            } else {
                stack.any {
                    it.className.contains("OkHttpTransport") &&
                        (it.className.contains("cancellableRead") || it.className.contains("readChunk"))
                }
            }
            if (inSocketRead && inRequiredPhase) return true
            Thread.yield()
        }
        return false
    }

    /** route 聚合异常可能进入 suppressed；只检查有界异常图的类型，不输出自由文本。 */
    private fun exceptionGraph(error: Throwable): List<Throwable> {
        val visited = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = ArrayDeque<Throwable>()
        val result = mutableListOf<Throwable>()
        pending.add(error)
        while (pending.isNotEmpty() && result.size < 32) {
            val next = pending.removeFirst()
            if (!visited.add(next)) continue
            result += next
            next.cause?.let { pending.addLast(it) }
            next.suppressed.take(16).forEach { pending.addLast(it) }
        }
        return result
    }

    /** JDK 自带工具创建一次性测试证书；私钥/keystore 不进入源码或构建产物。 */
    private suspend fun withTestTls(block: suspend (SSLContext, X509TrustManager) -> Unit) {
        val directory = Files.createTempDirectory("provider-local-tls-")
        val store = directory.resolve("fixture.p12")
        val toolOutput = directory.resolve("keytool-output.txt")
        val password = "local-test-only-password".toCharArray()
        try {
            val toolName = if (System.getProperty("os.name").startsWith("Windows")) "keytool.exe" else "keytool"
            val keytool = Path.of(System.getProperty("java.home"), "bin", toolName)
            assertTrue("TLS 回归需要当前 JDK 自带 keytool", Files.isRegularFile(keytool))
            val process = ProcessBuilder(
                keytool.toString(), "-genkeypair", "-alias", "local-test", "-keyalg", "EC",
                "-groupname", "secp256r1", "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", String(password), "-keypass", String(password),
                "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                "-validity", "1", "-noprompt",
            ).redirectErrorStream(true).redirectOutput(toolOutput.toFile()).start()
            val finished = process.waitFor(15, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly().waitFor(2, TimeUnit.SECONDS)
            assertTrue("临时 TLS 测试证书生成必须在期限内结束", finished)
            assertEquals("临时 TLS 测试证书生成失败", 0, process.exitValue())
            val keyStore = KeyStore.getInstance("PKCS12")
            Files.newInputStream(store).use { keyStore.load(it, password) }
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore, password)
            }
            val trusts = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }
            val trustManager = trusts.trustManagers.filterIsInstance<X509TrustManager>().single()
            val context = SSLContext.getInstance("TLS").apply {
                init(keys.keyManagers, arrayOf(trustManager), null)
            }
            block(context, trustManager)
        } finally {
            password.fill('\u0000')
            Files.deleteIfExists(store)
            Files.deleteIfExists(toolOutput)
            Files.deleteIfExists(directory)
        }
    }

    private class StageListener : EventListener() {
        val secureConnect = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val failed = CountDownLatch(1)
        val cancelCount = AtomicInteger()
        override fun secureConnectStart(call: Call) { secureConnect.countDown() }
        override fun canceled(call: Call) { cancelCount.incrementAndGet(); cancelled.countDown() }
        override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
    }
}
