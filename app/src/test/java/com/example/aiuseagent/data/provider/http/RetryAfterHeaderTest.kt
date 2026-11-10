package com.example.aiuseagent.data.provider.http

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RetryAfterHeaderTest {
    private val now = Instant.parse("2026-10-14T00:00:00Z").toEpochMilli()

    private fun httpDate(millis: Long): String =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC))

    @Test
    fun numericAndHttpDateBothReturnSeconds() {
        assertEquals(2L, RetryAfterHeader.parseSeconds("2", now))
        assertEquals(2L, RetryAfterHeader.parseSeconds(httpDate(now + 2_000), now))
    }

    @Test
    fun dateRoundsAnyRemainingFractionUpToSeconds() {
        assertEquals(1L, RetryAfterHeader.parseSeconds(httpDate(now + 1_000), now + 999))
        assertEquals(2L, RetryAfterHeader.parseSeconds(httpDate(now + 2_000), now + 1))
    }

    @Test
    fun expiredDateAndZeroHaveNoWait() {
        assertEquals(0L, RetryAfterHeader.parseSeconds(httpDate(now - 1_000), now))
        assertEquals(0L, RetryAfterHeader.parseSeconds(httpDate(now), now))
        assertEquals(0L, RetryAfterHeader.parseSeconds("0", now))
    }

    @Test
    fun secondsAndDatesAreCappedAtOneHour() {
        assertEquals(3_600L, RetryAfterHeader.parseSeconds(Long.MAX_VALUE.toString(), now))
        assertEquals(3_600L, RetryAfterHeader.parseSeconds(httpDate(now + 7_200_000), now))
        assertEquals(3_600L, RetryAfterHeader.parseSeconds(httpDate(now), Long.MIN_VALUE))
    }

    @Test
    fun invalidOrMissingHeaderHasNoWaitHint() {
        for (value in listOf(null, "", " ", "tomorrow", "1.5", "Wed, 99 Oct 2026 00:00:00 GMT")) {
            assertNull(RetryAfterHeader.parseSeconds(value, now))
        }
    }

    @Test
    fun printableHeaderAndRequestOmitEveryCallerControlledField() {
        val secret = "arbitrary-fake-credential"
        val header = ProviderHeader("name-$secret", "Bearer $secret")
        val request = ProviderHttpRequest(
            url = "https://example.invalid/$secret?token=$secret",
            headers = listOf(header),
            body = "{\"text\":\"$secret\"}",
        )
        assertFalse(header.toString().contains(secret))
        assertFalse(request.toString().contains(secret))
        assertFalse(request.toString().contains("example.invalid"))
        assertEquals(header, header.copy())
        assertEquals(request, request.copy())
    }
}
