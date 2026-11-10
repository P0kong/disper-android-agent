package com.example.aiuseagent.data.provider

import com.example.aiuseagent.domain.provider.CredentialPersistence
import com.example.aiuseagent.domain.provider.CredentialStatus
import com.example.aiuseagent.domain.provider.CredentialStore
import com.example.aiuseagent.domain.provider.ProviderCredential
import com.example.aiuseagent.domain.provider.ProviderType
import com.example.aiuseagent.domain.provider.SecretRedaction

/**
 * P2-A 唯一的凭据实现：**纯内存**。
 *
 * - 进程结束即丢失。这是设计选择而不是缺陷：设置页必须原样显示
 *   [CredentialPersistence.MEMORY_ONLY] 的文案，不允许出现「看起来已保存」的误导。
 * - 刻意不接受 Context、不提供任何序列化方法：从代码结构上杜绝被写盘或被日志记录。
 * - 读写都在同一把锁内，避免多线程出现半写入状态。
 */
class InMemoryCredentialStore(
    private val persistence: CredentialPersistence = CredentialPersistence.MEMORY_ONLY,
) : CredentialStore {

    private val lock = Any()
    private val secrets = mutableMapOf<ProviderType, String>()

    override fun status(type: ProviderType): CredentialStatus = synchronized(lock) {
        val secret = secrets[type]
        CredentialStatus(
            type = type,
            isPresent = !secret.isNullOrBlank(),
            maskedHint = SecretRedaction.maskedHint(secret),
            persistence = persistence,
        )
    }

    /** 空白输入视为**清除**，避免存下一个无意义的空串。 */
    override fun put(type: ProviderType, apiKey: String) = synchronized(lock) {
        val value = apiKey.trim()
        if (value.isEmpty() || value.length < SecretRedaction.MIN_SECRET_LENGTH) {
            secrets.remove(type)
        } else {
            secrets[type] = value
        }
        Unit
    }

    override fun clear(type: ProviderType) = synchronized(lock) {
        secrets.remove(type)
        Unit
    }

    override fun credentialFor(type: ProviderType): ProviderCredential = synchronized(lock) {
        ProviderCredential.of(secrets[type])
    }

    /** 仅用于测试与「清空内存凭据」场景。 */
    fun clearAll() = synchronized(lock) {
        secrets.clear()
        Unit
    }
}
