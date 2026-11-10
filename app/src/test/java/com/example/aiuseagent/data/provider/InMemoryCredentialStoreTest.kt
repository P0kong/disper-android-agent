package com.example.aiuseagent.data.provider

import com.example.aiuseagent.domain.provider.CredentialPersistence
import com.example.aiuseagent.domain.provider.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 内存凭据存储：功能正确，且**结构上**不可能写盘。 */
class InMemoryCredentialStoreTest {

    private val secret = "test-key-not-secret"

    @Test
    fun put_thenStatusReportsPresenceAndMaskedHint() {
        val store = InMemoryCredentialStore()
        assertFalse(store.status(ProviderType.DEEPSEEK).isPresent)

        store.put(ProviderType.DEEPSEEK, secret)

        val status = store.status(ProviderType.DEEPSEEK)
        assertTrue(status.isPresent)
        assertFalse("掩码不得包含明文", status.maskedHint!!.contains(secret))
        assertTrue(status.maskedHint!!.contains(secret.length.toString()))
    }

    @Test
    fun credentialFor_returnsAbsentByDefaultAndSecretAfterPut() {
        val store = InMemoryCredentialStore()
        assertFalse(store.credentialFor(ProviderType.ANTHROPIC).isPresent)

        store.put(ProviderType.ANTHROPIC, secret)
        assertEquals(secret, store.credentialFor(ProviderType.ANTHROPIC).reveal())
    }

    @Test
    fun clear_removesCredentialForSingleProvider() {
        val store = InMemoryCredentialStore()
        store.put(ProviderType.DEEPSEEK, secret)
        store.clear(ProviderType.DEEPSEEK)
        assertFalse(store.status(ProviderType.DEEPSEEK).isPresent)
    }

    @Test
    fun blankInputIsTreatedAsClear() {
        val store = InMemoryCredentialStore()
        store.put(ProviderType.DEEPSEEK, secret)
        store.put(ProviderType.DEEPSEEK, "   ")
        assertFalse("空白输入必须被视为清除，而不是存下一个空串", store.status(ProviderType.DEEPSEEK).isPresent)
    }

    @Test
    fun tooShortSecretIsNotStored() {
        val store = InMemoryCredentialStore()
        store.put(ProviderType.DEEPSEEK, "abc")
        assertFalse("过短输入应被拒绝而不是静默存下", store.status(ProviderType.DEEPSEEK).isPresent)
        assertNull(store.credentialFor(ProviderType.DEEPSEEK).reveal())
    }

    @Test
    fun statusIsTrackedPerProvider() {
        val store = InMemoryCredentialStore()
        store.put(ProviderType.DEEPSEEK, secret)
        assertTrue(store.status(ProviderType.DEEPSEEK).isPresent)
        assertFalse(store.status(ProviderType.OPENAI_COMPATIBLE).isPresent)
    }

    @Test
    fun clearAll_removesEveryCredential() {
        val store = InMemoryCredentialStore()
        store.put(ProviderType.DEEPSEEK, secret)
        store.put(ProviderType.ANTHROPIC, "another-test-secret")
        store.clearAll()
        ProviderType.entries.forEach { type ->
            assertFalse(type.name + " 的凭据未被清空", store.status(type).isPresent)
        }
    }

    @Test
    fun persistenceIsMemoryOnlyAndItsLabelMustBeShownVerbatim() {
        val status = InMemoryCredentialStore().status(ProviderType.MOCK)
        assertEquals(CredentialPersistence.MEMORY_ONLY, status.persistence)
        assertEquals("仅保存在内存：应用进程结束后需要重新输入", status.persistence.label)
        assertEquals("未填写", status.summary)
    }

    @Test
    fun summaryIsHonestAboutPresence() {
        val store = InMemoryCredentialStore()
        store.put(ProviderType.DEEPSEEK, secret)
        assertTrue(store.status(ProviderType.DEEPSEEK).summary.startsWith("已填写"))
        assertFalse(store.status(ProviderType.DEEPSEEK).summary.contains(secret))
    }
}
