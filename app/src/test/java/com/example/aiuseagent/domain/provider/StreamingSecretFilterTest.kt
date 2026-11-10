package com.example.aiuseagent.domain.provider

import org.junit.Assert.*
import org.junit.Test

class StreamingSecretFilterTest {
    @Test
    fun everyPossibleCredentialSplitIsMasked() {
        val secret = "fake-secret-only"
        for (split in 0..secret.length) {
            val filter = StreamingSecretFilter(listOf(secret))
            val result = filter.feed("before " + secret.take(split)) + filter.feed(secret.drop(split) + " after") + filter.finish()
            assertEquals("before *** after", result)
        }
    }

    @Test
    fun unresolvedPrefixAtFinishIsMaskedAndFinishIsIdempotent() {
        val filter = StreamingSecretFilter(listOf("fake-secret-only"))
        assertEquals("before ", filter.feed("before fake-se"))
        assertEquals("***", filter.finish())
        assertEquals("", filter.finish())
    }

    @Test
    fun overlappingSecretsAndArbitraryUnicodeFragmentsRemainSafe() {
        val filter = StreamingSecretFilter(listOf("abcd", "abcdef", "测试密钥"))
        assertEquals("", filter.feed("abcde"))
        val result = filter.feed("f / 测") + filter.feed("试密") + filter.feed("钥 end") + filter.finish()
        assertEquals("*** / *** end", result)
    }

    @Test
    fun unrelatedTextIsPreservedAndPrintableStateOmitsPendingSecret() {
        val filter = StreamingSecretFilter(listOf("unique-fake-secret"))
        assertEquals("ordinary text", filter.feed("ordinary text"))
        filter.feed("unique-fake-")
        assertFalse(filter.toString().contains("unique-fake-"))
    }
}
