package com.example.aiuseagent.data.provider

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.example.aiuseagent.domain.provider.CredentialPersistence
import com.example.aiuseagent.domain.provider.CredentialStatus
import com.example.aiuseagent.domain.provider.CredentialStore
import com.example.aiuseagent.domain.provider.ProviderCredential
import com.example.aiuseagent.domain.provider.ProviderType
import com.example.aiuseagent.domain.provider.SecretRedaction
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 设备本地加密凭据存储。
 *
 * - 加密密钥由 Android Keystore 生成并持有，**不可导出**；应用只保存密文。
 * - 每个作用域（内置类型或自定义连接 profileId）一个独立别名，互不可读。
 * - 密文格式：`v1:<base64(iv)>:<base64(ciphertext)>`，AES/GCM/NoPadding，96-bit nonce。
 * - 任何解密/Keystore 异常都退化为“未配置”，由 UI 要求重新输入，不泄漏异常细节。
 * - 本存储不写日志；SharedPreferences 属于应用私有目录，并被备份排除规则覆盖。
 */
class KeystoreCredentialStore(
    context: Context,
    private val scope: String,
) : CredentialStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private val alias = ALIAS_PREFIX + sha256(scope).take(24)

    override fun status(type: ProviderType): CredentialStatus = synchronized(lock) {
        val raw = prefs.getString(storageKey(type), null)
        val secret = raw?.let { runCatching { decrypt(it) }.getOrNull() }
        CredentialStatus(
            type = type,
            isPresent = !secret.isNullOrBlank(),
            maskedHint = SecretRedaction.maskedHint(secret),
            persistence = CredentialPersistence.ENCRYPTED_LOCAL,
            isUnreadable = raw != null && secret == null,
        )
    }

    /** 空白或过短输入视为清除，避免写入无意义密文。 */
    override fun put(type: ProviderType, apiKey: String) = synchronized(lock) {
        val value = apiKey.trim()
        if (value.length < SecretRedaction.MIN_SECRET_LENGTH) {
            prefs.edit().remove(storageKey(type)).apply()
        } else {
            val payload = encrypt(value)
            if (payload == null) prefs.edit().remove(storageKey(type)).apply()
            else prefs.edit().putString(storageKey(type), payload).apply()
        }
        Unit
    }

    override fun clear(type: ProviderType) = synchronized(lock) {
        prefs.edit().remove(storageKey(type)).apply()
        Unit
    }

    override fun credentialFor(type: ProviderType): ProviderCredential = synchronized(lock) {
        ProviderCredential.of(runCatching { read(type) }.getOrNull())
    }

    private fun storageKey(type: ProviderType) = scope + "|" + type.name

    private fun read(type: ProviderType): String? {
        val payload = prefs.getString(storageKey(type), null) ?: return null
        return decrypt(payload)
    }

    private fun encrypt(value: String): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ciphertext = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        "v1:" + Base64.getEncoder().encodeToString(cipher.iv) + ":" +
            Base64.getEncoder().encodeToString(ciphertext)
    }.getOrNull()

    private fun decrypt(payload: String): String? = runCatching {
        val parts = payload.split(':')
        require(parts.size == 3 && parts[0] == "v1")
        val iv = Base64.getDecoder().decode(parts[1])
        val ciphertext = Base64.getDecoder().decode(parts[2])
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }.getOrNull()

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        val generated = generator.generateKey()
        keyStore.setEntry(alias, KeyStore.SecretKeyEntry(generated), null)
        return generated
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val PREFS_NAME = "disper_credentials"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val ALIAS_PREFIX = "disper.credential."
    }
}
