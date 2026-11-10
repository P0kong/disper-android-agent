package com.example.aiuseagent.data.provider.http

import com.example.aiuseagent.domain.provider.ProviderErrorKind

/**
 * 有限重试策略（P2-B §6）。
 *
 * 上限是**结构性**的：[maxAttempts] 不允许超过 [MAX_ALLOWED_ATTEMPTS]
 * （3 = 首次尝试 + 最多 2 次额外尝试），因此代码里不可能出现无限重试。
 * 另外：只要已经发出过任何一个文本增量，就不允许再重放请求（避免用户看到重复内容）。
 */
data class RetryPolicy(
    val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    val initialBackoffMillis: Long = 500L,
    val maxBackoffMillis: Long = 4_000L,
    val maxRetryAfterMillis: Long = 10_000L,
) {
    init {
        require(maxAttempts in 1..MAX_ALLOWED_ATTEMPTS) {
            "maxAttempts 必须在 1..MAX_ALLOWED_ATTEMPTS（" + MAX_ALLOWED_ATTEMPTS + "）之间"
        }
        require(initialBackoffMillis > 0L) { "initialBackoffMillis 必须为正数" }
        require(maxBackoffMillis >= initialBackoffMillis) { "maxBackoffMillis 不能小于 initialBackoffMillis" }
        require(maxRetryAfterMillis >= 0L) { "maxRetryAfterMillis 不能为负数" }
    }

    val extraAttempts: Int get() = maxAttempts - 1

    /** 是否还能再试一次：类别可重试、没发出过增量、且还有剩余次数。 */
    fun shouldRetry(kind: ProviderErrorKind, attempt: Int, anyDeltaEmitted: Boolean): Boolean =
        kind.retryable && !anyDeltaEmitted && attempt < maxAttempts

    /** 第 retryNumber 次重试（从 1 开始）的等待时间：指数退避 + 单次上限。 */
    fun backoffMillisFor(retryNumber: Int): Long {
        require(retryNumber >= 1) { "retryNumber 从 1 开始" }
        var value = initialBackoffMillis
        var index = 1
        while (index < retryNumber) {
            value = if (value > maxBackoffMillis / 2) maxBackoffMillis else value * 2
            index++
        }
        return value.coerceAtMost(maxBackoffMillis)
    }

    /** 服务端给了 Retry-After 时取较大值，但仍然受单次等待上限约束。 */
    fun delayMillisFor(retryNumber: Int, retryAfterMillis: Long?): Long {
        val backoff = backoffMillisFor(retryNumber)
        val serverSide = retryAfterMillis?.coerceAtLeast(0L) ?: 0L
        return maxOf(backoff, serverSide.coerceAtMost(maxRetryAfterMillis))
    }

    companion object {
        /** 默认：总请求数最多 3 次（首次 + 2 次额外尝试）。 */
        const val DEFAULT_MAX_ATTEMPTS = 3

        /** 硬上限：任何调用方都无法把它配得更大。 */
        const val MAX_ALLOWED_ATTEMPTS = 3

        val SingleAttempt: RetryPolicy = RetryPolicy(maxAttempts = 1)
    }
}
