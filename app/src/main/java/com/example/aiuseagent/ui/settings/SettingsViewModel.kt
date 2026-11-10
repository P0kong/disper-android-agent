package com.example.aiuseagent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiuseagent.data.SavedModelConfig
import com.example.aiuseagent.data.SettingsRepository
import com.example.aiuseagent.data.generation.GenerationCoordinator
import com.example.aiuseagent.data.provider.ProviderRepository
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderType
import com.example.aiuseagent.domain.provider.ConnectionProfile
import com.example.aiuseagent.domain.provider.ProviderBrand
import com.example.aiuseagent.domain.provider.Protocol
import com.example.aiuseagent.domain.provider.CapabilitySupport
import com.example.aiuseagent.domain.provider.hasConnectionControls
import com.example.aiuseagent.ui.theme.ThemePreference
import java.net.URI
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.example.aiuseagent.ui.i18n.tr

/** Credential drafts and applied credentials are process memory only. */
data class ProviderFormState(
    val baseUrl: String = "", val apiKey: String = "", val model: String = "",
    val notice: String? = null, val providerType: String = "DEEPSEEK",
    val credentialConfigured: Boolean = false, val appliedLabel: String? = null,
    val apiVersion: String = "", val imageCapabilityLabel: String = tr("图像能力尚未确认", "Image support not confirmed yet"),
    /** 非敏感掩码提示，例如“****（共 32 位）”；永不包含明文。 */
    val credentialMask: String? = null,
    /** 曾保存但无法解密：提示重新输入，避免静默当成“从未配置”。 */
    val credentialUnreadable: Boolean = false,
) {
    override fun toString() = "ProviderFormState(fields=omitted)"
}

class SettingsViewModel(
    private val settings: SettingsRepository,
    private val providers: ProviderRepository? = null,
    private val coordinator: GenerationCoordinator? = null,
) : ViewModel() {
    val theme: StateFlow<ThemePreference> = settings.theme
    val language: StateFlow<com.example.aiuseagent.ui.i18n.LanguagePreference> = settings.language
    val chatLaunch = settings.chatLaunch
    fun setStartNewChat(enabled: Boolean) = settings.setStartNewChat(enabled)
    fun setTheme(preference: ThemePreference) = settings.setTheme(preference)
    fun setLanguage(preference: com.example.aiuseagent.ui.i18n.LanguagePreference) = settings.setLanguage(preference)
    private val _providerForm = MutableStateFlow(if (providers == null) ProviderFormState() else {
        val type = providers.activeType()
        val config = providers.configFor(type)
        ProviderFormState(baseUrl = config.baseUrl, model = config.model, providerType = type.name,
            credentialConfigured = providers.credentialStatus(type).isPresent,
            credentialMask = providers.credentialStatus(type).maskedHint,
            credentialUnreadable = providers.credentialStatus(type).isUnreadable,
            apiVersion=config.connectionProfile?.apiVersion ?: if(type==ProviderType.ANTHROPIC) "2023-06-01" else "",
            imageCapabilityLabel=imageLabel(providers.prepare(type).config),notice=settings.restorationIssue)
    })
    val providerForm: StateFlow<ProviderFormState> = _providerForm.asStateFlow()

    init {
        viewModelScope.launch {
            settings.language.collect {
                val repository = providers ?: return@collect
                _providerForm.update { form ->
                    form.copy(imageCapabilityLabel = imageLabel(repository.prepare().config))
                }
            }
        }
    }
    private var editRevision = 0L
    fun onBaseUrlChange(value: String) { editRevision++; _providerForm.update { it.copy(baseUrl = value, notice = null, imageCapabilityLabel=EDITED_CAPABILITY) } }
    fun onApiKeyChange(value: String) { editRevision++; _providerForm.update { it.copy(apiKey = value, notice = null) } }
    fun onModelChange(value: String) { editRevision++; _providerForm.update { it.copy(model = value, notice = null, imageCapabilityLabel=EDITED_CAPABILITY) } }
    fun onApiVersionChange(value:String) {editRevision++;_providerForm.update {it.copy(apiVersion=value,notice=null,imageCapabilityLabel=tr("配置已编辑，保存后重新核对图像能力", "Config edited; recheck image support after saving"))}}
    fun onProviderTypeChange(value: String) {
        val type = ProviderType.entries.firstOrNull {it.name==value} ?: return
        editRevision++
        coordinator?.stop(tr("更换模型配置，已停止", "Model config changed, stopped"))
        val config = providers?.configFor(type) ?: ProviderConfig.forType(type)
        _providerForm.value = ProviderFormState(baseUrl = config.baseUrl,
            model = config.model.ifBlank { if (type == ProviderType.DEEPSEEK) "deepseek-flash" else if(type==ProviderType.MOCK) "mock-echo" else "" },
            providerType = type.name, credentialConfigured = providers?.credentialStatus(type)?.isPresent == true,
            credentialMask = providers?.credentialStatus(type)?.maskedHint,
            credentialUnreadable = providers?.credentialStatus(type)?.isUnreadable == true,
            apiVersion=config.connectionProfile?.apiVersion ?: if(type==ProviderType.ANTHROPIC) "2023-06-01" else "")
    }
    private fun validConfig(form: ProviderFormState): ProviderConfig? {
        val type = ProviderType.entries.firstOrNull {it.name==form.providerType} ?: return null
        val candidate = form.apiKey.trim()
        val fields=listOf(form.baseUrl,form.model,form.apiVersion)
        if(fields.any {providers?.containsKnownCredential(it)==true || it.hasConnectionControls() || candidate.length>=4 && it.contains(candidate)}) return null
        val old=providers?.prepare(type)
        val profile=if(type==ProviderType.MOCK) null else ConnectionProfile(old?.profileId ?: "builtin:${type.name}",
            Math.addExact(old?.profileRevision ?: 0L,1L),when(type) {
                ProviderType.DEEPSEEK -> ProviderBrand.DEEPSEEK
                ProviderType.ANTHROPIC -> ProviderBrand.CLAUDE
                else -> ProviderBrand.OPENAI
            },if(type==ProviderType.ANTHROPIC) Protocol.ANTHROPIC_MESSAGES else Protocol.CHAT_COMPLETIONS,
            form.baseUrl.trim(),form.model.trim(),form.apiVersion.takeIf {it.isNotBlank()},configurationSaved=true)
        if(profile!=null && !profile.isConfigured()) return null
        val config = if (type == ProviderType.MOCK) ProviderConfig.forType(type)
            else ProviderConfig(type, form.baseUrl.trim(), form.model.trim(),connectionProfile=profile)
        if (config.validationIssues().isNotEmpty()) return null
        if (type == ProviderType.DEEPSEEK) {
            val uri = runCatching { URI(config.baseUrl) }.getOrNull() ?: return null
            if (uri.scheme != "https" || uri.host != "api.deepseek.com" || uri.port != -1 ||
                uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null || uri.path.trimEnd('/') !in setOf("", "/v1")) return null
        }
        return config
    }
    fun checkProviderFormLocally() {
        if (providers == null) {
            _providerForm.update { it.copy(notice = tr("尚未连接：已实现模型网络适配层，当前界面尚未接入模型调用。表单内容不会保存、不会发送。", "Not connected: the model network layer exists, but this UI doesn't call models yet. Form contents are neither saved nor sent.")) }
            return
        }
        val valid = validConfig(_providerForm.value) != null
        _providerForm.update { it.copy(notice = if (valid) tr("本地配置格式有效；尚未发送请求，不能据此确认连接。", "The local config format is valid; no request has been sent, so this doesn't confirm a connection.") else tr("配置无效或含已知凭据。DeepSeek 仅允许官方 HTTPS 地址，请检查模型名称。", "The config is invalid or contains known credentials. DeepSeek allows only the official HTTPS endpoint; check the model name.")) }
    }
    fun submitProviderForm() = checkProviderFormLocally()
    fun saveProviderConfig() {
        val repository = providers ?: return
        val form = _providerForm.value
        val config = validConfig(form)
        if (config == null) { checkProviderFormLocally(); return }
        val revision = ++editRevision
        viewModelScope.launch {
            if (coordinator?.stopAndJoin(tr("应用新配置，已停止", "New config applied, stopped")) == false) {
                _providerForm.update { it.copy(notice = tr("活动请求尚未完成停止或保存，配置未更换。", "An active request hasn't finished stopping or saving, so the config wasn't changed.")) }
                return@launch
            }
            if (revision != editRevision) return@launch
            if(config.connectionProfile!=null) repository.useConnectionProfile(config.connectionProfile)
            else {repository.updateConfig(config);repository.selectActive(config.type)}
            if (form.apiKey.isNotBlank()) repository.putCredential(config.type, form.apiKey)
            val applied=repository.prepare()
            val saved=runCatching {
                check(!applied.configurationContainsCredential)
                if(applied.config.connectionProfile!=null) settings.saveConnectionProfile(applied.config.connectionProfile)
                else settings.saveModelConfig(SavedModelConfig(config.type.name, config.baseUrl, config.model,applied.profileId,applied.profileRevision))
            }.isSuccess
            _providerForm.value = form.copy(apiKey = "", credentialConfigured = repository.credentialStatus(config.type).isPresent,
                credentialMask = repository.credentialStatus(config.type).maskedHint,
                credentialUnreadable = repository.credentialStatus(config.type).isUnreadable,
                appliedLabel = if(!saved) tr("仅当前进程应用 · 未持久保存", "This process only · Not persisted") else if (config.type == ProviderType.MOCK) tr("本地 Mock（不联网）", "Local Mock (offline)") else tr("已配置 · 尚未验证本次连接", "Configured · Connection not verified"),
                imageCapabilityLabel=imageLabel(applied.config),
                notice = if(saved) tr("非秘密配置已保存；API Key 已用 Android Keystore 加密保存在本机，保存不会自动发请求。", "Non-secret config saved; the API Key is stored encrypted with Android Keystore on this device, and saving never sends a request.")
                    else tr("配置保存失败；当前进程已应用，重启后的配置未确认。请重试保存；未发起请求。", "Failed to save config; it's applied in this process but not confirmed after a restart. Retry saving; no request was made."))
        }
    }
    fun clearCredential() {
        val type = runCatching { ProviderType.valueOf(_providerForm.value.providerType) }.getOrNull() ?: return
        editRevision++
        providers?.clearCredential(type)
        _providerForm.update { it.copy(apiKey = "", credentialConfigured = false, credentialMask = null,
            credentialUnreadable = false, appliedLabel = null,
            notice = tr("本机加密凭据已清除；后续请求需要重新填写。", "Encrypted credentials were cleared from this device; future requests need them entered again.")) }
        coordinator?.stop(tr("凭据已清除，已停止", "Credentials cleared, stopped"))
    }
    fun clearProviderForm() {
        editRevision++
        val viewedType = runCatching { ProviderType.valueOf(_providerForm.value.providerType) }.getOrNull()
        _providerForm.value = ProviderFormState(providerType = _providerForm.value.providerType,
            credentialConfigured = viewedType?.let { providers?.credentialStatus(it)?.isPresent } == true,
            credentialMask = viewedType?.let { providers?.credentialStatus(it)?.maskedHint },
            credentialUnreadable = viewedType?.let { providers?.credentialStatus(it)?.isUnreadable } == true)
    }
    companion object {
        private const val EDITED_CAPABILITY="配置已编辑，保存后重新核对图像能力"
        const val NOT_CONNECTED_LABEL = "未连接"
        private fun imageLabel(config:ProviderConfig)=if(config.imageCapability?.documentation==CapabilitySupport.SUPPORTED)
            tr("图像：文档支持，已通过本地协议测试；真实服务未验证", "Images: documented as supported, local protocol tests passed; real service unverified") else tr("图像：此连接身份尚无完整能力证据，发送将被阻止", "Images: no full capability evidence for this connection identity; sending will be blocked")
    }
}
