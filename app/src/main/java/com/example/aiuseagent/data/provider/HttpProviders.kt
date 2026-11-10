package com.example.aiuseagent.data.provider

import com.example.aiuseagent.data.provider.anthropic.AnthropicMessagesProvider
import com.example.aiuseagent.data.provider.http.HttpTransport
import com.example.aiuseagent.data.provider.http.OkHttpTransport
import com.example.aiuseagent.data.provider.http.ProviderDiagnostics
import com.example.aiuseagent.data.provider.http.RetryPolicy
import com.example.aiuseagent.data.provider.mock.MockProvider
import com.example.aiuseagent.data.provider.openai.OpenAiCompatibleProvider
import com.example.aiuseagent.domain.provider.CredentialStore
import com.example.aiuseagent.domain.provider.ModelProvider
import com.example.aiuseagent.domain.provider.ProviderProtocol
import com.example.aiuseagent.domain.provider.ProviderRegistry
import com.example.aiuseagent.domain.provider.ProviderType

/**
 * 阶段 P2-B 的供应商装配点。
 *
 * 这里只把适配器注册进注册表，**不**接入 AppContainer、设置界面或聊天界面（P2-C 才做），
 * 因此 P2-B 交付的是一层可独立测试、可随时接线的网络适配层。
 */
object HttpProviders {

    /**
     * 注册全部已实现的适配器。
     *
     * - DEEPSEEK 与 OPENAI_COMPATIBLE 共用 [OpenAiCompatibleProvider]（同一份协议实现，两个类型身份）；
     * - MOCK 仍然保留，未注册的类型依然会失败而不是回退到 Mock。
     */
    fun registry(
        transport: HttpTransport,
        retryPolicy: RetryPolicy = RetryPolicy(),
        diagnostics: ProviderDiagnostics = ProviderDiagnostics.NoOp,
    ): ProviderRegistry {
        val providers = mutableMapOf<ProviderType, ModelProvider>(
            ProviderType.MOCK to MockProvider(),
        )
        for (type in REAL_PROVIDER_TYPES) {
            providers[type] = providerFor(type, transport, retryPolicy, diagnostics)
        }
        return ProviderRegistry(providers)
    }

    /** 用真实 OkHttp 传输层构造注册表（应用接线时用这个入口）。 */
    fun registryWithOkHttp(
        retryPolicy: RetryPolicy = RetryPolicy(),
        diagnostics: ProviderDiagnostics = ProviderDiagnostics.NoOp,
    ): ProviderRegistry = registry(OkHttpTransport(), retryPolicy, diagnostics)

    /** 已实现真实网络适配层的供应商类型（Mock 不含在内）。 */
    val REAL_PROVIDER_TYPES: List<ProviderType> = listOf(
        ProviderType.DEEPSEEK,
        ProviderType.OPENAI_COMPATIBLE,
        ProviderType.ANTHROPIC,
    )

    private fun providerFor(
        type: ProviderType,
        transport: HttpTransport,
        retryPolicy: RetryPolicy,
        diagnostics: ProviderDiagnostics,
    ): ModelProvider = when (type.protocol) {
        ProviderProtocol.OPENAI_COMPATIBLE ->
            OpenAiCompatibleProvider(type, transport, retryPolicy, diagnostics)
        ProviderProtocol.ANTHROPIC_MESSAGES ->
            AnthropicMessagesProvider(transport, retryPolicy, diagnostics)
        ProviderProtocol.MOCK ->
            MockProvider()
    }

    /** 便捷入口：注册表 + 凭据存储；生产装配传入 Keystore 实现与工厂。 */
    fun repository(
        transport: HttpTransport = OkHttpTransport(),
        retryPolicy: RetryPolicy = RetryPolicy(),
        diagnostics: ProviderDiagnostics = ProviderDiagnostics.NoOp,
        credentialStore: CredentialStore = InMemoryCredentialStore(),
        credentialStoreFactory: ((String) -> CredentialStore)? = null,
    ): ProviderRepository = ProviderRepository(
        registry = registry(transport, retryPolicy, diagnostics),
        credentialStore = credentialStore,
        credentialStoreFactory = credentialStoreFactory,
    )
}
