package com.example.aiuseagent.data.provider.http

import com.example.aiuseagent.domain.provider.ProviderErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** P2-B §6：重试次数是结构上限，且发出过增量之后绝不允许重放。 */
class RetryPolicyTest {

    @Test
    fun defaultPolicyAllowsAtMostThreeRequests() {
        val policy = RetryPolicy()
        assertEquals(3, policy.maxAttempts)
        assertEquals("2 次额外尝试", 2, policy.extraAttempts)
        assertEquals(3, RetryPolicy.MAX_ALLOWED_ATTEMPTS)
    }

    @Test
    fun maxAttemptsAboveTheHardCapIsRejected() {
        try {
            RetryPolicy(maxAttempts = 4)
            fail("不允许配置成超过硬上限的尝试次数")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("MAX_ALLOWED_ATTEMPTS"))
        }
    }

    @Test
    fun retryableKindsAreRetriedOnlyUntilTheCap() {
        val policy = RetryPolicy()
        assertTrue(policy.shouldRetry(ProviderErrorKind.RATE_LIMITED, attempt = 1, anyDeltaEmitted = false))
        assertTrue(policy.shouldRetry(ProviderErrorKind.SERVER_ERROR, attempt = 2, anyDeltaEmitted = false))
        assertFalse("第 3 次尝试已经是最后一次", policy.shouldRetry(ProviderErrorKind.TIMEOUT, attempt = 3, anyDeltaEmitted = false))
    }

    @Test
    fun nonRetryableKindsAreNeverRetried() {
        val policy = RetryPolicy()
        val nonRetryable = listOf(
            ProviderErrorKind.INVALID_CONFIG,
            ProviderErrorKind.MISSING_API_KEY,
            ProviderErrorKind.UNAUTHORIZED,
            ProviderErrorKind.PROTOCOL,
            ProviderErrorKind.EMPTY_RESPONSE,
            ProviderErrorKind.CANCELLED,
            ProviderErrorKind.NOT_IMPLEMENTED,
            ProviderErrorKind.UNKNOWN,
        )
        for (kind in nonRetryable) {
            assertFalse(kind.name + " 不得重试", policy.shouldRetry(kind, attempt = 1, anyDeltaEmitted = false))
        }
    }

    @Test
    fun nothingIsRetriedAfterAFirstDeltaWasEmitted() {
        val policy = RetryPolicy()
        assertFalse(
            "已经发出过文本增量后不得重放请求",
            policy.shouldRetry(ProviderErrorKind.SERVER_ERROR, attempt = 1, anyDeltaEmitted = true),
        )
    }

    @Test
    fun singleAttemptPolicyNeverRetries() {
        assertFalse(RetryPolicy.SingleAttempt.shouldRetry(ProviderErrorKind.NETWORK, 1, false))
    }

    @Test
    fun backoffGrowsAndIsCapped() {
        val policy = RetryPolicy(initialBackoffMillis = 500L, maxBackoffMillis = 4_000L)
        assertEquals(500L, policy.backoffMillisFor(1))
        assertEquals(1_000L, policy.backoffMillisFor(2))
        assertEquals(2_000L, policy.backoffMillisFor(3))
        assertEquals(4_000L, policy.backoffMillisFor(4))
        assertEquals("必须封顶", 4_000L, policy.backoffMillisFor(9))
    }

    @Test
    fun retryAfterIsHonouredButBounded() {
        val policy = RetryPolicy(initialBackoffMillis = 500L, maxRetryAfterMillis = 10_000L)
        assertEquals("服务端要求更久时必须等更久", 1_500L, policy.delayMillisFor(1, 1_500L))
        assertEquals("服务端要求更短时不能少于退避", 500L, policy.delayMillisFor(1, 10L))
        assertEquals("单次等待有上限", 10_000L, policy.delayMillisFor(1, 3_600_000L))
        assertEquals("没有 Retry-After 时用退避", 500L, policy.delayMillisFor(1, null))
        assertEquals("负数按 0 处理", 500L, policy.delayMillisFor(1, -5L))
    }
}
