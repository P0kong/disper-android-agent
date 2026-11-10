package com.example.aiuseagent.domain.provider

/**
 * Provider 协议族。DeepSeek 复用 OpenAI 兼容协议，因此它不是独立协议。
 */
enum class ProviderProtocol {
    OPENAI_COMPATIBLE,
    ANTHROPIC_MESSAGES,
    MOCK,
}

/**
 * 供应商类型。
 *
 * P2-A 约定：
 * - [exampleBaseUrl] 只是**表单预填值**，它可能随时间变化，必须由用户核对官方文档后确认；
 * - [exampleModel] 只是**界面提示文本**，绝不作为默认模型提交（MOCK 除外，它是本地假数据）。
 */
enum class ProviderType(
    val label: String,
    val protocol: ProviderProtocol,
    val exampleBaseUrl: String,
    val exampleModel: String,
) {
    DEEPSEEK(
        label = "DeepSeek",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        exampleBaseUrl = "https://api.deepseek.com/v1",
        exampleModel = "deepseek-chat",
    ),
    OPENAI_COMPATIBLE(
        label = "通用 OpenAI 兼容服务",
        protocol = ProviderProtocol.OPENAI_COMPATIBLE,
        exampleBaseUrl = "",
        exampleModel = "",
    ),
    ANTHROPIC(
        label = "Anthropic",
        protocol = ProviderProtocol.ANTHROPIC_MESSAGES,
        exampleBaseUrl = "https://api.anthropic.com/v1",
        exampleModel = "",
    ),
    MOCK(
        label = "本地 Mock（不联网）",
        protocol = ProviderProtocol.MOCK,
        exampleBaseUrl = "mock://local",
        exampleModel = "mock-echo",
    ),
    ;

    /** 是否会发出真实网络请求。MOCK 永远为 false。 */
    val isRealNetworkProvider: Boolean
        get() = protocol != ProviderProtocol.MOCK

    companion object {
        /** P2-A 真正注册了实现的类型：只有本地 Mock。 */
        val implementedInP2A: Set<ProviderType> = setOf(MOCK)

        /**
         * P2-B 真正注册了实现的类型：本地 Mock + 三个真实网络类型。
         * 它们共用同一套 HTTP/SSE 适配层（[com.example.aiuseagent.data.provider.HttpProviders] 注册）。
         */
        val implementedInP2B: Set<ProviderType> = setOf(DEEPSEEK, OPENAI_COMPATIBLE, ANTHROPIC, MOCK)
    }
}
