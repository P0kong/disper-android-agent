package com.example.aiuseagent.domain.provider

import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 错误分类与脱敏：用户文案、可重试性、状态码映射都必须集中在这里。 */
class ProviderErrorTest {

    private val secret = "test-key-not-secret"

    @Test
    fun fromHttpStatus_mapsDocumentedKinds() {
        val expectations = mapOf(
            401 to ProviderErrorKind.UNAUTHORIZED,
            403 to ProviderErrorKind.UNAUTHORIZED,
            429 to ProviderErrorKind.RATE_LIMITED,
            408 to ProviderErrorKind.TIMEOUT,
            504 to ProviderErrorKind.TIMEOUT,
            500 to ProviderErrorKind.SERVER_ERROR,
            503 to ProviderErrorKind.SERVER_ERROR,
            400 to ProviderErrorKind.PROTOCOL,
            404 to ProviderErrorKind.PROTOCOL,
            302 to ProviderErrorKind.UNKNOWN,
        )
        expectations.forEach { (status, kind) ->
            val error = ProviderError.fromHttpStatus(status, ProviderType.DEEPSEEK, requestId = "req-1")
            assertEquals("HTTP " + status + " 映射错误", kind, error.kind)
            assertEquals(status, error.httpStatus)
            assertEquals("req-1", error.requestId)
            assertEquals(ProviderType.DEEPSEEK, error.providerType)
        }
    }

    @Test
    fun retryableFlags_matchDesign() {
        val retryable = ProviderErrorKind.entries.filter { it.retryable }.toSet()
        assertEquals(
            setOf(
                ProviderErrorKind.RATE_LIMITED,
                ProviderErrorKind.TIMEOUT,
                ProviderErrorKind.NETWORK,
                ProviderErrorKind.SERVER_ERROR,
            ),
            retryable,
        )
    }

    @Test
    fun everyKindHasUserMessage() {
        ProviderErrorKind.entries.forEach { kind ->
            assertTrue(kind.name + " 缺少用户文案", kind.userMessage.isNotBlank())
        }
    }

    @Test
    fun redact_removesSecretFromDetailAndToString() {
        val error = ProviderError.of(
            kind = ProviderErrorKind.UNAUTHORIZED,
            providerType = ProviderType.DEEPSEEK,
            httpStatus = 401,
            detail = "server said: key " + secret + " is invalid",
        )
        val redacted = error.redact(secret)
        assertFalse("detail 必须脱敏", redacted.detail!!.contains(secret))
        assertTrue(redacted.detail!!.contains(SecretRedaction.MASK))
        assertFalse("toString 也不得带出秘密", redacted.toString().contains(secret))
        assertTrue("原始对象不得被就地修改", error.detail!!.contains(secret))
    }

    @Test
    fun redact_removesRequestIdEvenWhenDetailIsNull() {
        val original = ProviderError.fromHttpStatus(401, ProviderType.DEEPSEEK, requestId = "req-" + secret)
        val redacted = original.redact(secret)

        assertEquals(null, redacted.detail)
        assertEquals(null, redacted.requestId)
        assertFalse(redacted.toString().contains(secret))
        assertFalse(redacted.userMessage.contains(secret))
        assertEquals("req-" + secret, original.requestId)
    }

    @Test
    fun toString_omitsUnredactedFreeText() {
        val original = ProviderError(
            kind = ProviderErrorKind.UNAUTHORIZED,
            detail = "unsafe detail " + secret,
            requestId = "unsafe-id-" + secret,
        )
        assertFalse(original.toString().contains(secret))
        assertFalse(original.toString().contains("unsafe detail"))
        assertFalse(original.toString().contains("unsafe-id"))
        assertTrue(original.toString().contains("UNAUTHORIZED"))
    }

    @Test
    fun redact_boundsDetailAndRejectsMalformedRequestIds() {
        val error = ProviderError(
            kind = ProviderErrorKind.PROTOCOL,
            detail = secret + "\n" + "x".repeat(500),
            requestId = "req-1\r\nforged: value",
        ).redact(secret)

        assertEquals(null, error.requestId)
        assertTrue(error.detail!!.length <= SecretRedaction.MAX_SAFE_TEXT_LENGTH)
        assertFalse(error.detail!!.contains(secret))
        assertFalse(error.detail!!.contains('\n'))
    }

    @Test
    fun of_dropsBlankDetail() {
        assertEquals(null, ProviderError.of(ProviderErrorKind.MISSING_API_KEY, ProviderType.ANTHROPIC, detail = "   ").detail)
    }

    @Test
    fun mapper_mapsThrowables() {
        assertEquals(
            ProviderErrorKind.TIMEOUT,
            ProviderErrorMapper.fromThrowable(SocketTimeoutException("read timed out"), ProviderType.DEEPSEEK).kind,
        )
        assertEquals(
            ProviderErrorKind.NETWORK,
            ProviderErrorMapper.fromThrowable(UnknownHostException("api.example.com"), ProviderType.DEEPSEEK).kind,
        )
        assertEquals(
            ProviderErrorKind.NETWORK,
            ProviderErrorMapper.fromThrowable(IOException("connection reset"), ProviderType.DEEPSEEK).kind,
        )
        assertEquals(
            ProviderErrorKind.UNKNOWN,
            ProviderErrorMapper.fromThrowable(IllegalStateException("boom"), ProviderType.DEEPSEEK).kind,
        )
    }

    @Test
    fun notImplementedMessageDoesNotClaimSuccess() {
        val message = ProviderErrorKind.NOT_IMPLEMENTED.userMessage
        assertTrue("未实现必须说清楚", message.contains("尚未实现"))
        assertFalse("不得暗示已经可用", message.contains("可用"))
    }
}
