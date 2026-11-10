package com.example.aiuseagent.domain.provider

/**
 * 凭据持久化模式。
 *
 * P2-A 只有 [MEMORY_ONLY] 一种，并且它的文案**必须原样显示在设置页**：
 * 不允许出现「界面看起来已保存、实际只在内存」的误导性 UX。
 */
enum class CredentialPersistence(val label: String) {
    MEMORY_ONLY("仅保存在内存：应用进程结束后需要重新输入"),
    ENCRYPTED_LOCAL("已使用 Android Keystore 加密保存在本机：应用重启后仍可使用"),
}

/** 凭据状态。只包含非敏感信息，可以安全地展示和记录。 */
data class CredentialStatus(
    val type: ProviderType,
    val isPresent: Boolean,
    val maskedHint: String?,
    val persistence: CredentialPersistence,
    /** true = 曾经保存过，但当前无法解密（不同于“从未配置”）。 */
    val isUnreadable: Boolean = false,
) {
    val summary: String
        get() = if (isPresent) "已填写（" + (maskedHint ?: "已填写") + "）"
        else if (isUnreadable) "已保存但无法解密，需要重新输入" else "未填写"
}

/**
 * 凭据读写边界。
 *
 * 接口只允许读写当前进程内的凭据；P2-A 的实现是纯内存（见 InMemoryCredentialStore）。
 * 未来若要落盘，必须换成 Keystore 支持的加密实现，并先完成库评估，
 * 不允许直接把明文写 SharedPreferences / DataStore / Room / 文件。
 */
interface CredentialStore {
    fun status(type: ProviderType): CredentialStatus

    fun put(type: ProviderType, apiKey: String)

    fun clear(type: ProviderType)

    /** 只交给 Provider 组装鉴权头使用。 */
    fun credentialFor(type: ProviderType): ProviderCredential
}
