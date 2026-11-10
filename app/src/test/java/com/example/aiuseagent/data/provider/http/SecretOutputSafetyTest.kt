package com.example.aiuseagent.data.provider.http

import com.example.aiuseagent.data.provider.openai.OpenAiCompatibleProvider
import com.example.aiuseagent.data.provider.anthropic.AnthropicMessagesProvider
import com.example.aiuseagent.domain.provider.ChatRequest
import com.example.aiuseagent.domain.provider.ChatRole
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderCredential
import com.example.aiuseagent.domain.provider.ProviderError
import com.example.aiuseagent.domain.provider.ProviderErrorKind
import com.example.aiuseagent.domain.provider.ProviderEvent
import com.example.aiuseagent.domain.provider.ProviderEventContract
import com.example.aiuseagent.domain.provider.ProviderMessage
import com.example.aiuseagent.domain.provider.ProviderType
import com.example.aiuseagent.domain.provider.SecretRedaction
import com.example.aiuseagent.ui.settings.ProviderFormState
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** R1-A H2：伪凭据只能进入请求，不能从服务端错误、诊断或可打印结构返回。 */
class SecretOutputSafetyTest {

    private val secret = "test-id-credential-not-real"

    @Test
    fun requestId_acceptsOnlyBoundedAsciiIdentifiers() {
        assertEquals("req-1_ab.cd:2", SecretRedaction.safeRequestId("req-1_ab.cd:2"))
        assertEquals("x".repeat(128), SecretRedaction.safeRequestId("x".repeat(128)))
        for (invalid in listOf("", " ", "x".repeat(129), "req\nforged", "req/value", "请求编号", "req?value")) {
            assertNull("服务端关联 ID 必须满足长度及格式约束", SecretRedaction.safeRequestId(invalid))
        }
    }

    @Test
    fun requestId_dropsKnownSecretsWithoutAnyKeyPattern() {
        val arbitraryCredential = "credentialAlphabet1234"
        assertNull(SecretRedaction.safeRequestId("req-" + arbitraryCredential, listOf(arbitraryCredential)))
        assertEquals("req-regular", SecretRedaction.safeRequestId("req-regular", listOf(arbitraryCredential)))
    }

    @Test
    fun text_scrubsBeforeCharacterCleaningAndLengthLimit() {
        val punctuationCredential = "test+/=credential"
        val safe = DiagnosticText.safe("prefix\n" + punctuationCredential + " suffix", listOf(punctuationCredential))
        assertEquals("prefix???? suffix", safe)
        assertEquals("???", DiagnosticText.safe(punctuationCredential + "x".repeat(300), listOf(punctuationCredential), 3))
        assertNull(DiagnosticText.safe(null, listOf(secret)))
    }

    @Test
    fun overlappingSecretsAreScrubbedLongestFirst() {
        assertEquals("*** ***", SecretRedaction.scrub("abcdef abcd", listOf("abcd", "abcdef")))
    }

    @Test
    fun diagnostics_redactsEveryTextField() {
        val record = ProviderLogRecord(
            event = "event-" + secret,
            providerType = ProviderType.OPENAI_COMPATIBLE,
            errorKind = secret,
            requestId = "req-" + secret,
            note = "note " + secret + "\r\nforged",
        ).redact(listOf(secret))

        assertFalse(record.event.contains(secret))
        assertNull(record.errorKind)
        assertNull(record.requestId)
        assertFalse(record.note!!.contains(secret))
        assertFalse(record.note!!.contains('\r'))
        assertFalse(record.toString().contains(secret))
    }

    @Test
    fun diagnostics_preservesKnownErrorKindAndBoundsFields() {
        val record = ProviderLogRecord(
            event = "x".repeat(150),
            providerType = ProviderType.ANTHROPIC,
            errorKind = ProviderErrorKind.UNAUTHORIZED.name,
            requestId = "x".repeat(129),
            note = "ordinary note",
        ).redact(emptyList())

        assertEquals(ProviderErrorKind.UNAUTHORIZED.name, record.errorKind)
        assertEquals(ProviderLogRecord.MAX_EVENT_LENGTH, record.event.length)
        assertNull(record.requestId)
        assertEquals("ordinary note", record.note)
    }

    @Test
    fun printableDiagnosticsOmitFreeTextEvenBeforeRedaction() {
        val raw = ProviderLogRecord(secret, ProviderType.DEEPSEEK, secret, requestId = secret, note = secret)
        assertFalse(raw.toString().contains(secret))
    }

    @Test
    fun settingsFormToStringOmitsEveryFreeTextField() {
        val form = ProviderFormState(secret, secret, secret, secret)
        assertFalse(form.toString().contains(secret))
        assertEquals(secret, form.apiKey) // 只修可打印出口，不改变当前内存表单功能。
    }

    @Test
    fun printableRequestsOmitCredentialAndPayloadRegardlessOfLocation() {
        val header = ProviderHeader(secret, secret)
        val request = ProviderHttpRequest(
            url = "https://example.invalid/" + secret,
            headers = listOf(header),
            body = "private prompt " + secret,
        )
        assertFalse(header.toString().contains(secret))
        assertFalse(request.toString().contains(secret))
        assertFalse(request.toString().contains("private prompt"))
    }

    @Test
    fun printableDomainRequestsOmitMessagesAndSystemPrompt() {
        val message = ProviderMessage(ChatRole.USER, secret)
        val request = ChatRequest(messages = listOf(message), system = "private system " + secret)

        assertFalse(message.toString().contains(secret))
        assertFalse(request.toString().contains(secret))
        assertFalse(request.toString().contains("private system"))
        assertEquals(secret, request.messages.single().content)
    }

    @Test
    fun errorBodyProbeRejectsInvalidMachineTokensInsteadOfRewritingSecrets() {
        val punctuationCredential = "test+/=credential"
        val body = "{\"error\":{\"type\":\"" + punctuationCredential + "\",\"message\":\"" + punctuationCredential + "\"}}"
        assertNull(ErrorBodyProbe.tokenOf(body, listOf(punctuationCredential)))
        assertNull(ErrorBodyProbe.tokenOf(body, emptyList()))
        assertEquals("invalid_api_key", ErrorBodyProbe.tokenOf("{\"error\":{\"type\":\"invalid_api_key\"}}", listOf(secret)))
    }

    @Test
    fun fakeTransportEchoedCredentialIsAbsentFromAllFailureOutputs() = runBlocking {
        var closed = false
        val transport = object : HttpTransport {
            override suspend fun open(request: ProviderHttpRequest, timeouts: HttpTimeouts): StreamingHttpResponse =
                object : StreamingHttpResponse {
                    override val status = 401
                    override val requestId = "req-" + secret
                    override val retryAfterMillis: Long? = null
                    override suspend fun readChunk(): ByteArray? = null
                    override suspend fun readErrorBodyText(): String = errorBody()
                    override fun close() { closed = true }
                }
        }
        val diagnostics = ProviderDiagnostics.Recording()
        val provider = provider(transport, diagnostics)
        val events = provider.stream(ChatRequest.ofUserText("local test"), config(), ProviderCredential.of(secret)).toList()

        assertSafeFailure(events, diagnostics)
        assertTrue(closed)
    }

    @Test
    fun anthropicUnknownLabelsCannotLeakTruncatedCredentialPrefixes() = runBlocking {
        val longCredential = "test-long-credential-" + "x".repeat(100)
        val eventsText = "event: " + longCredential + "\ndata: {\"type\":\"" + longCredential + "\"}\n\n" +
            "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"content_block\":{\"type\":\"" +
            longCredential + "\"}}\n\n" +
            "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\n" +
            "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
        val transport = object : HttpTransport {
            override suspend fun open(request: ProviderHttpRequest, timeouts: HttpTimeouts): StreamingHttpResponse =
                object : StreamingHttpResponse {
                    private var consumed = false
                    override val status = 200
                    override val requestId: String? = null
                    override val retryAfterMillis: Long? = null
                    override suspend fun readChunk(): ByteArray? = if (consumed) null else {
                        consumed = true
                        eventsText.toByteArray(Charsets.UTF_8)
                    }
                    override suspend fun readErrorBodyText(): String? = null
                    override fun close() = Unit
                }
        }
        val diagnostics = ProviderDiagnostics.Recording()
        val config = ProviderConfig.forType(ProviderType.ANTHROPIC).copy(model = "test-model")
        val events = AnthropicMessagesProvider(transport, diagnostics = diagnostics)
            .stream(ChatRequest.ofUserText("local test"), config, ProviderCredential.of(longCredential)).toList()

        assertTrue(ProviderEventContract.validate(events).isEmpty())
        assertTrue(events.last() is ProviderEvent.Completed)
        assertEquals(2, diagnostics.notes().size)
        for (record in diagnostics.snapshot()) {
            val outputs = listOfNotNull(record.event, record.errorKind, record.requestId, record.note, record.toString())
            assertTrue(outputs.none { it.contains(longCredential) || it.contains(longCredential.take(64)) })
        }
    }

    @Test
    fun realLoopbackServerEchoedCredentialIsAbsentFromAllFailureOutputs() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse(401, Headers.headersOf("x-request-id", "req-" + secret), errorBody()))
            val diagnostics = ProviderDiagnostics.Recording()
            val provider = provider(OkHttpTransport(), diagnostics)
            val localConfig = config().copy(baseUrl = server.url("/v1").toString(), allowInsecureHttp = true)
            val events = provider.stream(ChatRequest.ofUserText("local test"), localConfig, ProviderCredential.of(secret)).toList()

            assertSafeFailure(events, diagnostics)
            assertEquals(1, server.requestCount)
            assertEquals("Bearer " + secret, server.takeRequest().headers["Authorization"])
        } finally {
            server.close()
        }
    }

    private fun provider(transport: HttpTransport, diagnostics: ProviderDiagnostics) = OpenAiCompatibleProvider(
        ProviderType.OPENAI_COMPATIBLE,
        transport,
        retryPolicy = RetryPolicy(maxAttempts = 1),
        diagnostics = diagnostics,
    )

    private fun config() = ProviderConfig.forType(ProviderType.OPENAI_COMPATIBLE).copy(
        baseUrl = "https://example.invalid/v1",
        model = "test-model",
    )

    private fun errorBody(): String = "{\"error\":{\"type\":\"" + secret + "\",\"message\":\"echo " + secret + "\"}}"

    private fun assertSafeFailure(events: List<ProviderEvent>, diagnostics: ProviderDiagnostics.Recording) {
        assertTrue(ProviderEventContract.validate(events).isEmpty())
        val error: ProviderError = (events.last() as ProviderEvent.Failed).error
        assertEquals(ProviderErrorKind.UNAUTHORIZED, error.kind)
        assertNull(error.requestId)
        val errorOutputs = listOfNotNull(error.detail, error.requestId, error.toString(), error.userMessage)
        assertTrue(errorOutputs.none { it.contains(secret) })
        val records = diagnostics.snapshot()
        assertTrue(records.isNotEmpty())
        for (record in records) {
            val outputs = listOfNotNull(record.event, record.errorKind, record.requestId, record.note, record.toString())
            assertTrue(outputs.none { it.contains(secret) })
        }
    }
}
