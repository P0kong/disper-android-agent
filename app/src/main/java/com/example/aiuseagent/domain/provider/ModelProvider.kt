package com.example.aiuseagent.domain.provider

import kotlinx.coroutines.flow.Flow

/**
 * 统一的模型接入契约（P2-A 定义）。
 *
 * 1. [stream] 是**冷流**：每 collect 一次发起一次请求。调用方禁止在 Composable 重组中直接 collect；
 *    必须由 ViewModel 持有 Job，避免旋转/重建时重复发起（P2-C 实施）。
 * 2. 取消 = 取消收集协程；实现必须让底层 HTTP 请求一起结束，而不是只停止渲染。
 * 3. 业务错误**不抛异常**，而是以 [ProviderEvent.Failed] 作为终态事件发出；
 *    取消抛 [kotlinx.coroutines.CancellationException]——那是协程语义，不是业务错误。
 * 4. 任何进入 [ProviderError.detail] 的文本都必须先脱敏。
 */
interface ModelProvider {

    val type: ProviderType

    fun stream(
        request: ChatRequest,
        config: ProviderConfig,
        credential: ProviderCredential,
    ): Flow<ProviderEvent>
}

/**
 * 请求发出前的静态校验。
 *
 * 返回非空表示**直接失败**，调用方不得因此发起任何网络动作。
 * 它同时保证「缺 Key / 模型名为空 / URL 非法」这类问题在离屏测试里就能覆盖。
 */
object ProviderPreflight {

    fun check(
        type: ProviderType,
        request: ChatRequest,
        config: ProviderConfig,
        credential: ProviderCredential,
        requiresKey: Boolean = type.isRealNetworkProvider,
    ): ProviderError? {
        if (config.type != type) {
            return ProviderError.of(
                ProviderErrorKind.INVALID_CONFIG,
                type,
                detail = "配置类型（" + config.type.name + "）与 Provider 类型（" + type.name + "）不一致",
            )
        }
        val configIssues = config.validationIssues()
        config.connectionProfile?.let { profile ->
            val protocolMatches=when(profile.protocol) {
                Protocol.CHAT_COMPLETIONS -> type.protocol==ProviderProtocol.OPENAI_COMPATIBLE
                Protocol.ANTHROPIC_MESSAGES -> type.protocol==ProviderProtocol.ANTHROPIC_MESSAGES
                else -> false
            }
            if(!profile.isConfigured() || !protocolMatches || profile.normalizedBaseUrl()!=config.normalizedBaseUrl() ||
                profile.modelId!=config.model || profile.allowInsecureHttp!=config.allowInsecureHttp) {
                return ProviderError.of(ProviderErrorKind.INVALID_CONFIG,type,detail="连接身份已改变，请重新保存配置。")
            }
        }
        if (configIssues.isNotEmpty()) {
            return ProviderError.of(
                ProviderErrorKind.INVALID_CONFIG,
                type,
                detail = configIssues.joinToString(separator = "; ") { it.message },
            )
        }
        val requestIssues = request.validationIssues()
        if (requestIssues.isNotEmpty()) {
            return ProviderError.of(
                ProviderErrorKind.INVALID_CONFIG,
                type,
                detail = requestIssues.joinToString(separator = "; ") { it.message },
            )
        }
        if(request.messages.any { message->message.parts.any {it is ProviderContentPart.Image} } &&
            (type !in setOf(ProviderType.OPENAI_COMPATIBLE,ProviderType.ANTHROPIC) ||
                config.imageCapability?.documentation!=CapabilitySupport.SUPPORTED ||
                config.imageCapability.protocolTest!=VerificationState.PASSED)) {
            return ProviderError.of(ProviderErrorKind.INVALID_CONFIG,type,detail="当前连接尚无图像能力及协议验证，请在模型设置中选择已验证的视觉模型。")
        }
        if ((request.tools.isNotEmpty() || request.messages.any { it.toolCalls.isNotEmpty() || it.role == ChatRole.TOOL }) &&
            type.protocol != ProviderProtocol.OPENAI_COMPATIBLE
        ) {
            return ProviderError.of(ProviderErrorKind.INVALID_CONFIG, type, detail = "该供应商不支持工具调用。")
        }
        if (requiresKey && !credential.isPresent) {
            return ProviderError.of(ProviderErrorKind.MISSING_API_KEY, type)
        }
        return null
    }
}
