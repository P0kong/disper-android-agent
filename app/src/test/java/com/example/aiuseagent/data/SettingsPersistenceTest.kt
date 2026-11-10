package com.example.aiuseagent.data

import android.content.SharedPreferences
import com.example.aiuseagent.platform.PreferenceWorkFolderStore
import com.example.aiuseagent.ui.settings.SettingsViewModel
import com.example.aiuseagent.ui.theme.ThemePreference
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class SettingsPersistenceTest {
    @Test fun saveRestorePreservesProfileIdentity() {
        val memory=Preferences();val settings=SettingsRepository(memory.prefs)
        val profile=com.example.aiuseagent.domain.provider.ConnectionProfile("profile:visual",8,
            com.example.aiuseagent.domain.provider.ProviderBrand.CLAUDE,
            com.example.aiuseagent.domain.provider.Protocol.ANTHROPIC_MESSAGES,
            "https://api.anthropic.com/v1","claude-sonnet-5-5","2023-06-01",configurationSaved=true)
        settings.saveConnectionProfile(profile)
        val reopened=SettingsRepository(memory.prefs)
        assertEquals(profile,reopened.savedConnectionProfile())
        assertFalse(memory.values.values.any {it.toString().contains("apiKey")})
    }
    @Test fun unknownLegacyBrandIsNotGuessedOrOverwritten() {
        val memory=Preferences()
        memory.values[SettingsRepository.KEY_PROVIDER_TYPE]="OLD_UNKNOWN_BRAND"
        val before=memory.values.toMap()
        assertNull(SettingsRepository(memory.prefs).savedConnectionProfile())
        assertEquals(before,memory.values)
    }
    @Test fun invalidConfigurationIsRejectedBeforePersistence() {
        val memory=Preferences();val settings=SettingsRepository(memory.prefs)
        val invalid=com.example.aiuseagent.domain.provider.ConnectionProfile("p",1,
            com.example.aiuseagent.domain.provider.ProviderBrand.CUSTOM,
            com.example.aiuseagent.domain.provider.Protocol.CHAT_COMPLETIONS,"https://user:secret@example.invalid/v1","m")
        assertThrows(IllegalArgumentException::class.java) {settings.saveConnectionProfile(invalid)}
        assertTrue(memory.values.isEmpty())
    }
    private class Preferences {
        val values = mutableMapOf<String, Any>()
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getBoolean", "getLong" -> values[args!![0]] ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "getAll" -> values.toMap()
                "edit" -> editor()
                else -> null
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val writes = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putBoolean", "putLong" -> { writes[args!![0] as String] = args[1]; proxy }
                    "remove" -> { writes[args!![0] as String] = null; proxy }
                    "apply", "commit" -> { writes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }; if (method.name == "commit") true else null }
                    else -> null
                }
            } as SharedPreferences.Editor
        }
    }

    @Test fun themeAndLaunchChoiceRoundTripIncludingDefaultEnabled() {
        val memory = Preferences(); val settings = SettingsRepository(memory.prefs)
        assertTrue(settings.chatLaunch.value!!.startNewChat)
        settings.setTheme(ThemePreference.DARK); settings.setStartNewChat(false); settings.rememberSession(42)
        val reopened = SettingsRepository(memory.prefs)
        assertEquals(ThemePreference.DARK, reopened.currentTheme())
        assertEquals(ChatLaunchPreferences(false, 42), reopened.chatLaunch.value)
        reopened.rememberSession(null); assertFalse(memory.values.containsKey(SettingsRepository.KEY_LAST_SESSION))
    }

    @Test fun migratedModelFormRemainsMemoryOnlyWhileOnlyApprovedNonsecretFieldsPersist() {
        val memory = Preferences(); val settings = SettingsRepository(memory.prefs)
        settings.setTheme(ThemePreference.LIGHT); settings.setStartNewChat(false); settings.rememberSession(9)
        val folders = PreferenceWorkFolderStore(memory.prefs)
        folders.save(SavedWorkFolder("content://synthetic/tree/empty", "Synthetic empty"))
        val vm = SettingsViewModel(settings)
        vm.onBaseUrlChange("https://synthetic.invalid/v1"); vm.onApiKeyChange("FAKE_UI_NOT_SECRET"); vm.onModelChange("fake-model"); vm.submitProviderForm()
        assertEquals(setOf("theme_preference", "start_new_chat", "last_session_id", "work_folder_uri", "work_folder_name"), memory.values.keys)
        assertFalse(memory.values.values.any { it.toString().contains("FAKE_UI_NOT_SECRET") || it.toString().contains("synthetic.invalid") || it.toString().contains("fake-model") })
        assertEquals("ProviderFormState(fields=omitted)", vm.providerForm.value.toString())
        val fresh = SettingsViewModel(SettingsRepository(memory.prefs))
        assertEquals("", fresh.providerForm.value.apiKey); assertEquals("", fresh.providerForm.value.baseUrl); assertEquals("", fresh.providerForm.value.model)
        folders.save(null); assertEquals(setOf("theme_preference", "start_new_chat", "last_session_id"), memory.values.keys)
    }
}
