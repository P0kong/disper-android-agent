package com.example.aiuseagent.data.provider

import com.example.aiuseagent.domain.provider.ChatRequest
import com.example.aiuseagent.domain.provider.ConfigIssue
import com.example.aiuseagent.domain.provider.CredentialStore
import com.example.aiuseagent.domain.provider.ModelProvider
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderCredential
import com.example.aiuseagent.domain.provider.ProviderError
import com.example.aiuseagent.domain.provider.ProviderErrorKind
import com.example.aiuseagent.domain.provider.ProviderEvent
import com.example.aiuseagent.domain.provider.ProviderPreflight
import com.example.aiuseagent.domain.provider.ProviderRegistry
import com.example.aiuseagent.domain.provider.ProviderType
import com.example.aiuseagent.domain.provider.SecretRedaction
import com.example.aiuseagent.domain.provider.StreamingSecretFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * 某个供应商在 P2-A 的真实可用状态快照。
 * 所有字段都来自内存里的真实状态；**没有任何一项是写死的**，
 * 因此界面（P2-B/P2-C 接入时）不需要也不允许自己判断「是否已连接」。
 */
data class ProviderAvailability(
    val type: ProviderType,
    val isRegistered: Boolean,
    val config: ProviderConfig,
    val configIssues: List<ConfigIssue>,
    val hasCredential: Boolean,
    val credentialHint: String?,
    val credentialPersistenceLabel: String,
) {
    /** 只有「已注册 + 配置合法 + （真实供应商时）有 Key」才算就绪。 */
    val isReady: Boolean
        get() {
            if (!isRegistered) return false
            if (configIssues.isNotEmpty()) return false
            return !type.isRealNetworkProvider || hasCredential
        }

    val statusLabel: String
        get() = when {
            !isRegistered -> "尚未实现"
            !isReady -> "未配置完成"
            type.isRealNetworkProvider -> "已配置（尚未发起过真实请求）"
            else -> "本地 Mock（不联网）"
        }

    val detail: String
        get() = when {
            !isRegistered -> type.label + " 还没有注册任何适配器（尚未实现）；" +
                "P2-B 的适配层只覆盖 OpenAI-compatible（含 DeepSeek）与 Anthropic Messages。"
            configIssues.isNotEmpty() -> configIssues.joinToString(separator = "；") { it.message }
            !isReady && type.isRealNetworkProvider -> "还需要填写 API Key。"
            type.isRealNetworkProvider -> "配置与内存凭据已就绪；发送时才会发起请求，尚未据此验证服务端连接。"
            else -> "只在内存中生成假数据，不读写网络、不写入日志。"
        }
}

/**
 * Provider 查询与调度的唯一入口（P2-B 版本：已能调度真实适配层，但还没有 UI 接进来）。
 *
 * 职责边界：
 * - 它**不**持久化任何东西（配置与凭据都在内存；凭据由 [CredentialStore] 持有）；
 * - 它**不**读写 Room，也不改写历史消息；切换供应商只影响后续请求；
 * - 未注册的供应商返回离线失败流（[ProviderErrorKind.NOT_IMPLEMENTED]），
 *   绝不退化为 Mock，也绝不假装成功。
 */
class ProviderRepository(
    private val registry: ProviderRegistry = ProviderRegistry(),
    private val credentialStore: CredentialStore = InMemoryCredentialStore(),
    private val capabilityResolver: CapabilityResolver = CapabilityResolver(),
    /** 自定义连接（非 builtin）的凭据存储工厂；生产环境注入 Keystore 实现。 */
    private val credentialStoreFactory: ((String) -> CredentialStore)? = null,
) {

    private val lock = Any()
    private val selectedProfiles = mutableMapOf<ProviderType,String>()
    private val profileConfigs = mutableMapOf<String,ProviderConfig>()
    private val profileCredentials = mutableMapOf<String,CredentialStore>()
    private val profileRevisions = mutableMapOf<String,Long>()
    private var active: ProviderType = ProviderType.MOCK
    private val knownSecrets = mutableSetOf<String>()

    fun activeType(): ProviderType = synchronized(lock) { active }

    fun useConnectionProfile(profile: com.example.aiuseagent.domain.provider.ConnectionProfile) = synchronized(lock) {
        val encoded=com.example.aiuseagent.data.ConnectionProfileCodec.encode(profile)
        require(!containsKnownCredential(encoded))
        val type=requireNotNull(com.example.aiuseagent.data.ConnectionProfileCodec.adapterType(profile))
        useProfile(profile.profileId,ProviderConfig(type,profile.baseUrl,profile.modelId,profile.allowInsecureHttp,
            connectionProfile=profile.copy(configurationSaved=true)))
        profileRevisions[profile.profileId]=maxOf(profileRevisions[profile.profileId] ?: 0L,profile.revision)
    }

    /** 只影响后续请求；历史消息不会被改写。 */
    fun selectActive(type: ProviderType) = synchronized(lock) {
        if (active != type) bump(profileId(type))
        active = type
        Unit
    }

    fun configFor(type: ProviderType): ProviderConfig = synchronized(lock) {
        profileConfigs[profileId(type)] ?: ProviderConfig.forType(type)
    }

    /** 配置里只允许出现非敏感字段；API Key 必须走 [putCredential]。 */
    fun updateConfig(config: ProviderConfig) = synchronized(lock) {
        val id=profileId(config.type)
        if (configFor(config.type)!=config) bump(id)
        profileConfigs[id]=config
        Unit
    }

    private fun profileId(type: ProviderType)=selectedProfiles[type] ?: "builtin:${type.name}"
    private fun storeFor(type: ProviderType): CredentialStore =
        if (profileId(type)=="builtin:${type.name}") credentialStore
        else profileCredentials.getOrPut(profileId(type)) {
            credentialStoreFactory?.invoke(profileId(type)) ?: InMemoryCredentialStore()
        }

    /** 持久化凭据在重启后要重新纳入“已知秘密”，供配置/导出内容检查使用。 */
    private fun rememberSecret(type: ProviderType) {
        storeFor(type).credentialFor(type).reveal()?.takeIf { it.isNotBlank() }?.let { knownSecrets += it }
    }
    private fun bump(id: String) { profileRevisions[id]=Math.addExact(profileRevisions[id] ?: 0L,1L) }

    /** Separate same-protocol connection instances never share their credential store. */
    fun useProfile(id: String,config: ProviderConfig) = synchronized(lock) {
        require(id.matches(Regex("[A-Za-z0-9:_-]{1,100}")))
        require(!containsKnownCredential(id))
        require(profileConfigs[id]?.type?.let { it==config.type } != false)
        val changed=active!=config.type || profileId(config.type)!=id
        selectedProfiles[config.type]=id
        if (changed) bump(id)
        updateConfig(config)
        active=config.type
        rememberSecret(config.type)
    }

    fun restoreProfile(id: String,config: ProviderConfig,revision: Long) = synchronized(lock) {
        require(revision>=0)
        require(profileConfigs[id]==null) { "Restore is startup-only" }
        useProfile(id,config)
        profileRevisions[id]=revision
    }

    /** Merge the durable catalogue before requests become ready, without selecting another profile.
     * Settings may contain a newer unsent config; keep it and advance beyond the stored revision.
     */
    fun restoreStoredProfile(id: String,config: ProviderConfig,revision: Long) = synchronized(lock) {
        require(id.matches(Regex("[A-Za-z0-9:_-]{1,100}")) && revision>=0)
        require(!containsKnownCredential(id) && !containsKnownCredential(config.baseUrl) && !containsKnownCredential(config.model))
        val existing=profileConfigs[id]
        require(existing==null || existing.type==config.type)
        if(existing==null) profileConfigs[id]=config
        val floor=if(existing!=null && existing!=config) Math.addExact(revision,1) else revision
        profileRevisions[id]=maxOf(profileRevisions[id] ?: 0L,floor)
    }

    fun isCurrent(prepared: PreparedProvider): Boolean = synchronized(lock) {
        active==prepared.type && profileId(active)==prepared.profileId &&
            (profileRevisions[prepared.profileId] ?: 0L)==prepared.profileRevision
    }

    fun credentialStatus(type: ProviderType) = synchronized(lock) { storeFor(type).status(type) }

    fun putCredential(type: ProviderType, apiKey: String) = synchronized(lock) {
        storeFor(type).put(type, apiKey)
        storeFor(type).credentialFor(type).reveal()?.let { knownSecrets += it }
        bump(profileId(type))
        Unit
    }

    fun clearCredential(type: ProviderType) = synchronized(lock) { storeFor(type).clear(type);bump(profileId(type)) }

    private fun secretSnapshot(): List<String> {
        ProviderType.entries.mapNotNull { credentialStore.credentialFor(it).reveal() }.forEach { knownSecrets += it }
        profileCredentials.values.forEach { store -> ProviderType.entries.mapNotNull { store.credentialFor(it).reveal() }.forEach { knownSecrets += it } }
        return knownSecrets.toList()
    }

    fun containsKnownCredential(text: String): Boolean = synchronized(lock) {
        secretSnapshot().any { text.contains(it) }
    }

    /** Freezes selection, configuration and credential for all tool rounds in one run. */
    fun prepare(type: ProviderType = activeType()): PreparedProvider = synchronized(lock) {
        val raw=configFor(type)
        val profile=raw.connectionProfile?.copy(profileId=profileId(type),revision=profileRevisions[profileId(type)] ?: 1L)
        val config=if(profile==null) raw.copy() else raw.copy(connectionProfile=profile,
            imageCapability=capabilityResolver.resolve(profile).feature(com.example.aiuseagent.domain.provider.ModelFeature.IMAGES))
        PreparedProvider(type, config, storeFor(type).credentialFor(type),
            registry.providerFor(type), secretSnapshot(), unavailableError(type),profileId(type),profileRevisions[profileId(type)] ?: 0L)
    }

    fun providerFor(type: ProviderType): ModelProvider? = registry.providerFor(type)

    fun registeredTypes(): Set<ProviderType> = registry.registeredTypes()

    /** 本阶段没有真实实现、只会返回 NOT_IMPLEMENTED 的类型（供界面如实展示）。 */
    fun missingTypes(): Set<ProviderType> = registry.missingTypes()

    fun availability(type: ProviderType = activeType()): ProviderAvailability {
        val config = configFor(type)
        val credential = credentialStatus(type)
        return ProviderAvailability(
            type = type,
            isRegistered = registry.isImplemented(type),
            config = config,
            configIssues = config.validationIssues(),
            hasCredential = credential.isPresent,
            credentialHint = credential.maskedHint,
            credentialPersistenceLabel = credential.persistence.label,
        )
    }

    /** 未实现供应商的标准错误；文案里的实现集合来自注册表，不写死。 */
    fun unavailableError(type: ProviderType): ProviderError = ProviderError.of(
        kind = ProviderErrorKind.NOT_IMPLEMENTED,
        providerType = type,
        detail = "当前已注册的供应商：" +
            registry.registeredTypes().sortedBy { it.name }.joinToString(separator = ", ") { it.name },
    )

    /**
     * 发起一次流式请求。
     *
     * Freeze the current configuration and credential before constructing a cold stream.
     * Missing adapters fail explicitly and never fall back to Mock.
     */
    fun stream(request: ChatRequest, type: ProviderType = activeType()): Flow<ProviderEvent> {
        return prepare(type).stream(request)
    }
}

/** No credential accessors, data-class copying or secret-bearing printable fields. */
class PreparedProvider internal constructor(
    val type: ProviderType,
    private val frozenConfig: ProviderConfig,
    private val credential: ProviderCredential,
    private val provider: ModelProvider?,
    private val secrets: List<String>,
    private val unavailable: ProviderError,
    val profileId: String,
    val profileRevision: Long,
) {
    val configurationContainsCredential: Boolean = listOfNotNull(profileId,frozenConfig.model,frozenConfig.baseUrl,
        frozenConfig.connectionProfile?.profileId,frozenConfig.connectionProfile?.modelId,
        frozenConfig.connectionProfile?.baseUrl,frozenConfig.connectionProfile?.apiVersion).any(::containsKnownCredential)
    val config: ProviderConfig = frozenConfig.copy(
        model = SecretRedaction.scrub(frozenConfig.model, secrets),
        baseUrl = SecretRedaction.scrub(frozenConfig.baseUrl, secrets),
        connectionProfile=frozenConfig.connectionProfile?.let { profile -> profile.copy(
            profileId=SecretRedaction.scrub(profile.profileId,secrets),
            modelId=SecretRedaction.scrub(profile.modelId,secrets),
            baseUrl=SecretRedaction.scrub(profile.baseUrl,secrets),
            apiVersion=profile.apiVersion?.let {SecretRedaction.scrub(it,secrets)}) },
    )
    val model: String get() = config.model

    fun containsKnownCredential(text: String): Boolean = secrets.any { text.contains(it) }
    fun newSecretFilter(): StreamingSecretFilter = StreamingSecretFilter(secrets)

    fun preflight(request: ChatRequest): ProviderError? {
        if (provider == null) return unavailable.redact(*secrets.toTypedArray())
        val textFields = buildList {
            add(frozenConfig.model)
            add(frozenConfig.baseUrl)
            frozenConfig.connectionProfile?.apiVersion?.let(::add)
            request.system?.let(::add)
            request.messages.forEach { message ->
                add(message.content)
                message.parts.filterIsInstance<com.example.aiuseagent.domain.provider.ProviderContentPart.Text>().forEach {
                    add(it.text);it.source?.let(::add)
                }
                message.toolCallId?.let(::add)
                message.toolCalls.forEach { add(it.id); add(it.name); add(it.arguments) }
            }
            request.tools.forEach { add(it.name); add(it.description); add(it.parameters.toString()) }
        }
        if (configurationContainsCredential || textFields.any(::containsKnownCredential)) {
            return ProviderError.of(ProviderErrorKind.INVALID_CONFIG, type, detail = "请求包含已知凭据，已拒绝发送。")
        }
        return ProviderPreflight.check(type, request, frozenConfig, credential)?.redact(*secrets.toTypedArray())
    }

    fun stream(request: ChatRequest): Flow<ProviderEvent> = flow {
        val error = preflight(request)
        if (error != null || provider == null) {
            emit(ProviderEvent.Started(type, model))
            emit(ProviderEvent.Failed(error ?: unavailable))
            return@flow
        }
        val filter = newSecretFilter()
        var rejected = false
        provider.stream(request, frozenConfig, credential).collect { event ->
            if (!rejected) when (event) {
                is ProviderEvent.Started -> emit(event.copy(model = model))
                is ProviderEvent.TextDelta -> filter.feed(event.text).takeIf { it.isNotEmpty() }?.let {
                    emit(ProviderEvent.TextDelta(it))
                }
                is ProviderEvent.ToolCalls -> {
                    if (event.calls.any { containsKnownCredential(it.id) || containsKnownCredential(it.name) ||
                            containsKnownCredential(it.arguments) }) {
                        filter.finish().takeIf { it.isNotEmpty() }?.let { emit(ProviderEvent.TextDelta(it)) }
                        emit(ProviderEvent.Failed(ProviderError.of(ProviderErrorKind.PROTOCOL, type,
                            detail = "工具调用包含已知凭据，已拒绝执行。")))
                        rejected = true
                    } else emit(event)
                }
                is ProviderEvent.Completed, is ProviderEvent.Failed -> {
                    filter.finish().takeIf { it.isNotEmpty() }?.let { emit(ProviderEvent.TextDelta(it)) }
                    emit(if (event is ProviderEvent.Failed) event.copy(error = event.error.redact(*secrets.toTypedArray())) else event)
                }
            }
        }
        // Cancellation deliberately drops the unresolved prefix held by the filter.
    }

    override fun toString(): String = "PreparedProvider(type=$type, model=<omitted>, config=<omitted>)"
}
