package com.example.aiuseagent.domain.provider

/**
 * 已注册 Provider 的查表。
 *
 * P2-A 只注册 MOCK。请求尚未实现的类型时返回 null，
 * 由上层转成 [ProviderErrorKind.NOT_IMPLEMENTED]——**不允许**退化成 Mock 或假成功。
 */
class ProviderRegistry(private val providers: Map<ProviderType, ModelProvider> = emptyMap()) {

    fun providerFor(type: ProviderType): ModelProvider? = providers[type]

    fun registeredTypes(): Set<ProviderType> = providers.keys.toSet()

    fun missingTypes(): Set<ProviderType> = ProviderType.entries.toSet() - registeredTypes()

    fun isImplemented(type: ProviderType): Boolean = providers.containsKey(type)
}
