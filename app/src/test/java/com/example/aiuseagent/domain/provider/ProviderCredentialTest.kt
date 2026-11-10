package com.example.aiuseagent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 凭据值对象与脱敏工具：秘密不得出现在日志、异常或界面提示里。 */
class ProviderCredentialTest {

    private val secret = "test-key-not-secret"

    @Test
    fun of_returnsAbsentForBlankOrNull() {
        assertFalse(ProviderCredential.of(null).isPresent)
        assertFalse(ProviderCredential.of("").isPresent)
        assertFalse(ProviderCredential.of("   ").isPresent)
        assertNull(ProviderCredential.Absent.reveal())
    }

    @Test
    fun of_trimsAndReportsPresence() {
        val credential = ProviderCredential.of("  " + secret + "  ")
        assertTrue(credential.isPresent)
        assertEquals(secret, credential.reveal())
    }

    @Test
    fun toString_neverExposesSecret() {
        val text = ProviderCredential.of(secret).toString()
        assertFalse("toString 不得包含明文", text.contains(secret))
        assertTrue(text.contains("isPresent=true"))
    }

    @Test
    fun maskedHint_exposesLengthOnly() {
        val hint = ProviderCredential.of(secret).maskedHint()
        assertTrue(hint != null)
        assertFalse("掩码不得包含秘密的任何字符", hint!!.contains(secret))
        assertTrue("掩码只应暴露长度", hint.contains(secret.length.toString()))
        assertNull(ProviderCredential.Absent.maskedHint())
    }

    @Test
    fun equalsAndHashCode_areBasedOnSecretOnly() {
        assertEquals(ProviderCredential.of(secret), ProviderCredential.of(secret))
        assertEquals(ProviderCredential.of(secret).hashCode(), ProviderCredential.of(secret).hashCode())
        assertNotEquals(ProviderCredential.of(secret), ProviderCredential.of(secret + "x"))
        assertEquals(ProviderCredential.Absent, ProviderCredential.of(""))
    }

    @Test
    fun redaction_replacesEveryOccurrence() {
        val text = "key=" + secret + " and again " + secret
        val scrubbed = SecretRedaction.scrub(text, secret)
        assertFalse(scrubbed.contains(secret))
        assertTrue(scrubbed.contains(SecretRedaction.MASK))
    }

    @Test
    fun redaction_replacesAcrossMultipleSecrets() {
        val text = "a=" + secret + " b=another-test-secret"
        val scrubbed = SecretRedaction.scrub(text, secret, "another-test-secret")
        assertFalse(scrubbed.contains(secret))
        assertFalse(scrubbed.contains("another-test-secret"))
    }

    @Test
    fun redaction_ignoresShortAndBlankSecrets() {
        assertEquals("abc", SecretRedaction.scrub("abc", "abc"))
        assertEquals("text", SecretRedaction.scrub("text", "", "  "))
    }

    @Test
    fun redaction_treatsNullTextAsEmpty() {
        assertEquals("", SecretRedaction.scrub(null, secret))
    }
}
