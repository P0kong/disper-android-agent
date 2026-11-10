package com.example.aiuseagent.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.example.aiuseagent.ui.theme.ThemePreference
import com.example.aiuseagent.ui.i18n.LanguagePreference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 非敏感的界面偏好持久化。只保存主题等界面选项，
 * **不保存**任何 API Key、令牌或账号信息。
 */
class SettingsRepository(
    private val prefs: SharedPreferences,
) : ChatPreferences {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val _theme = MutableStateFlow(ThemePreference.fromKey(prefs.getString(KEY_THEME, null)))
    val theme: StateFlow<ThemePreference> = _theme.asStateFlow()

    private val _language = MutableStateFlow(LanguagePreference.fromKey(prefs.getString(KEY_LANGUAGE, null)))
    val language: StateFlow<LanguagePreference> = _language.asStateFlow()

    fun setLanguage(preference: LanguagePreference) {
        prefs.edit { putString(KEY_LANGUAGE, preference.name) }
        _language.value = preference
    }

    /** 更新检查节流与“已关闭”记录：只保存公开版本元数据，不涉及用户内容。 */
    fun updateLastAutoCheckAt(): Long = prefs.getLong(KEY_UPDATE_LAST_AUTO_CHECK, 0L)
    fun setUpdateLastAutoCheckAt(value: Long) {
        prefs.edit { putLong(KEY_UPDATE_LAST_AUTO_CHECK, value) }
    }
    fun dismissedUpdateTag(): String? = prefs.getString(KEY_UPDATE_DISMISSED_TAG, null)
    fun setDismissedUpdateTag(tag: String?) {
        prefs.edit { if (tag == null) remove(KEY_UPDATE_DISMISSED_TAG) else putString(KEY_UPDATE_DISMISSED_TAG, tag) }
    }

    /** 当前偏好（同步读取，便于非 Compose 环境使用）。 */
    fun currentLanguage(): LanguagePreference = _language.value

    private val _chatLaunch = MutableStateFlow<ChatLaunchPreferences?>(
        ChatLaunchPreferences(
            startNewChat = prefs.getBoolean(KEY_START_NEW_CHAT, true),
            lastSessionId = if (prefs.contains(KEY_LAST_SESSION)) prefs.getLong(KEY_LAST_SESSION, 0L) else null,
            lastBuildSessionId = if(prefs.contains(KEY_LAST_BUILD_SESSION)) prefs.getLong(KEY_LAST_BUILD_SESSION,0L) else null,
        ),
    )
    override val chatLaunch: StateFlow<ChatLaunchPreferences?> = _chatLaunch.asStateFlow()

    fun setStartNewChat(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_START_NEW_CHAT, enabled) }
        _chatLaunch.value = _chatLaunch.value?.copy(startNewChat = enabled)
    }

    override fun rememberSession(id: Long?) {
        if (_chatLaunch.value?.lastSessionId == id) return
        prefs.edit { if (id == null) remove(KEY_LAST_SESSION) else putLong(KEY_LAST_SESSION, id) }
        _chatLaunch.value = _chatLaunch.value?.copy(lastSessionId = id)
    }
    override fun rememberSession(id: Long?,mode: String) {
        if(mode!="BUILD") { rememberSession(id);return }
        if(_chatLaunch.value?.lastBuildSessionId==id) return
        prefs.edit { if(id==null) remove(KEY_LAST_BUILD_SESSION) else putLong(KEY_LAST_BUILD_SESSION,id) }
        _chatLaunch.value=_chatLaunch.value?.copy(lastBuildSessionId=id)
    }

    fun setTheme(preference: ThemePreference) {
        prefs.edit { putString(KEY_THEME, preference.name) }
        _theme.value = preference
    }

    /** 当前偏好（同步读取，便于非 Compose 环境使用）。 */
    fun currentTheme(): ThemePreference = _theme.value

    fun saveConnectionProfile(profile: com.example.aiuseagent.domain.provider.ConnectionProfile) {
        val encoded=ConnectionProfileCodec.encode(profile)
        val type=requireNotNull(ConnectionProfileCodec.adapterType(profile)) {"Protocol not implemented"}
        // One durable write; the versioned identity is authoritative, old fields are compatibility mirrors.
        check(prefs.edit().putString(KEY_CONNECTION_PROFILE,encoded)
            .putString(KEY_PROVIDER_TYPE,type.name).putString(KEY_PROVIDER_BASE,profile.baseUrl)
            .putString(KEY_PROVIDER_MODEL,profile.modelId).putString(KEY_PROFILE_ID,profile.profileId)
            .putLong(KEY_PROFILE_REVISION,profile.revision).commit()) {"Configuration save failed"}
    }
    fun savedConnectionProfile(): com.example.aiuseagent.domain.provider.ConnectionProfile? =
        prefs.getString(KEY_CONNECTION_PROFILE,null)?.let(ConnectionProfileCodec::decode)

    var restorationIssue:String?=null
        private set
    fun restoreProviderConfiguration(providers: com.example.aiuseagent.data.provider.ProviderRepository): Boolean {
        restorationIssue=null
        if(prefs.contains(KEY_CONNECTION_PROFILE)) {
            val profile=savedConnectionProfile()
            val type=profile?.let(ConnectionProfileCodec::adapterType)
            if(profile==null || type==null) {
                restorationIssue="保存的连接版本或协议尚不可用，请重新配置；原记录已保留。";return false
            }
            providers.restoreProfile(profile.profileId,com.example.aiuseagent.domain.provider.ProviderConfig(type,
                profile.baseUrl,profile.modelId,profile.allowInsecureHttp,connectionProfile=profile),profile.revision)
            return true
        }
        val old=savedModelConfig()
        // Version 0 preferences were written only by the old DeepSeek/Mock form.
        val type=when(old.providerType) {
            "DEEPSEEK" -> com.example.aiuseagent.domain.provider.ProviderType.DEEPSEEK
            "MOCK" -> com.example.aiuseagent.domain.provider.ProviderType.MOCK
            else -> {restorationIssue="旧连接类型无法确认，请重新配置；原记录已保留。";return false}
        }
        providers.restoreProfile(old.profileId,if(type==com.example.aiuseagent.domain.provider.ProviderType.MOCK)
            com.example.aiuseagent.domain.provider.ProviderConfig.forType(type)
            else com.example.aiuseagent.domain.provider.ProviderConfig(type,old.baseUrl,old.model),old.profileRevision)
        return true
    }

    /** Three explicitly saved non-secret fields; credential drafts never reach this boundary. */
    fun savedModelConfig(): SavedModelConfig = SavedModelConfig(
        prefs.getString(KEY_PROVIDER_TYPE, null) ?: "DEEPSEEK",
        prefs.getString(KEY_PROVIDER_BASE, null) ?: "https://api.deepseek.com",
        prefs.getString(KEY_PROVIDER_MODEL, null) ?: "deepseek-flash",
        prefs.getString(KEY_PROFILE_ID,null) ?: "builtin:${prefs.getString(KEY_PROVIDER_TYPE,null) ?: "DEEPSEEK"}",
        prefs.getLong(KEY_PROFILE_REVISION,0L),
    )

    fun saveModelConfig(config: SavedModelConfig) {
        require(config.providerType in setOf("DEEPSEEK", "MOCK"))
        require(config.profileId.matches(Regex("[A-Za-z0-9:_-]{1,100}")) && config.profileRevision>=0)
        prefs.edit {
            remove(KEY_CONNECTION_PROFILE)
            putString(KEY_PROVIDER_TYPE, config.providerType)
            putString(KEY_PROVIDER_BASE, config.baseUrl)
            putString(KEY_PROVIDER_MODEL, config.model)
            putString(KEY_PROFILE_ID,config.profileId)
            putLong(KEY_PROFILE_REVISION,config.profileRevision)
        }
    }

    companion object {
        const val PREFS_NAME = "ai_use_agent_settings"
        const val KEY_THEME = "theme_preference"
        const val KEY_LANGUAGE = "language_preference"
        const val KEY_UPDATE_LAST_AUTO_CHECK = "update_last_auto_check_at"
        const val KEY_UPDATE_DISMISSED_TAG = "update_dismissed_tag"
        const val KEY_START_NEW_CHAT = "start_new_chat"
        const val KEY_LAST_SESSION = "last_session_id"
        const val KEY_LAST_BUILD_SESSION = "last_build_session_id"
        const val KEY_PROVIDER_TYPE = "provider_type"
        const val KEY_PROVIDER_BASE = "provider_base_url"
        const val KEY_PROVIDER_MODEL = "provider_model"
        const val KEY_PROFILE_ID = "connection_profile_id"
        const val KEY_PROFILE_REVISION = "connection_profile_revision"
        const val KEY_CONNECTION_PROFILE = "connection_profile_v1"
    }
}

data class SavedModelConfig(val providerType: String, val baseUrl: String, val model: String,
    val profileId: String = "builtin:$providerType",val profileRevision: Long = 0) {
    override fun toString() = "SavedModelConfig(fields=<omitted>)"
}
