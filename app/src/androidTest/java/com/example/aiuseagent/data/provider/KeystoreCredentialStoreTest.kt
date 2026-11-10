package com.example.aiuseagent.data.provider

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiuseagent.domain.provider.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * 设备侧 Keystore 凭据验收（合成数据）：
 * 1) 跨实例持久化 + 掩码 + 明文不落盘；
 * 2) 密文损坏时安全失败且不自动删除原密文；
 * 3) 清除只影响本作用域，不同连接互不读取。
 *
 * 只使用随机 scope，不触碰用户已配置的 builtin 凭据。
 */
@RunWith(AndroidJUnit4::class)
class KeystoreCredentialStoreTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val type = ProviderType.DEEPSEEK
    private val secret = "sk-keystore-synthetic-20261106"
    private fun scope() = "test-" + UUID.randomUUID().toString()

    private fun prefs() = context.getSharedPreferences("disper_credentials", Context.MODE_PRIVATE)

    @Test
    fun persistsAcrossInstancesMaskedAndWithoutPlaintext() {
        val scope = scope()
        KeystoreCredentialStore(context, scope).put(type, secret)

        val reopened = KeystoreCredentialStore(context, scope)
        val status = reopened.status(type)
        assertTrue("应为已配置", status.isPresent)
        assertNotNull("应有掩码提示", status.maskedHint)
        assertEquals("解密后应得到同一 Key", secret, reopened.credentialFor(type).reveal())

        val dump = prefs().all.values.joinToString("|")
        assertFalse("prefs 不得出现明文", dump.contains(secret))
        assertTrue("prefs 应为 v1 密文", dump.contains("v1:"))
    }

    @Test
    fun corruptedCipherDegradesSafelyAndKeepsPayload() {
        val scope = scope()
        KeystoreCredentialStore(context, scope).put(type, secret)
        val key = scope + "|" + type.name
        val original = requireNotNull(prefs().getString(key, null))

        prefs().edit().putString(key, "v1:AAAAAAAAAAAAAAAA:BBBBBBBBBBBBBBBB").commit()

        val reopened = KeystoreCredentialStore(context, scope)
        assertFalse("损坏密文应显示未配置", reopened.status(type).isPresent)
        assertTrue("损坏密文应标记为不可读，而不是从未配置", reopened.status(type).isUnreadable)
        assertNull("损坏密文不得返回 Key", reopened.credentialFor(type).reveal())
        assertEquals("不得自动删除原密文", "v1:AAAAAAAAAAAAAAAA:BBBBBBBBBBBBBBBB", prefs().getString(key, null))

        prefs().edit().putString(key, original).commit()
        assertEquals("恢复原密文后应可读", secret, KeystoreCredentialStore(context, scope).credentialFor(type).reveal())
    }

    @Test
    fun clearIsIsolatedPerScope() {
        val scopeA = scope()
        val scopeB = scope()
        val a = KeystoreCredentialStore(context, scopeA)
        val b = KeystoreCredentialStore(context, scopeB)
        a.put(type, secret)
        b.put(type, secret + "-b")

        a.clear(type)
        assertFalse("A 清除后应为未配置", KeystoreCredentialStore(context, scopeA).status(type).isPresent)
        assertFalse("A 清除后不应保留“不可读”标记", KeystoreCredentialStore(context, scopeA).status(type).isUnreadable)
        assertEquals("B 不得受 A 影响", secret + "-b", KeystoreCredentialStore(context, scopeB).credentialFor(type).reveal())

        b.clear(type)
        assertFalse("B 清除后应为未配置", b.status(type).isPresent)
    }
}
