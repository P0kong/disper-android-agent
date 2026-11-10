package com.example.aiuseagent.ui.i18n

import java.util.Locale

/** 界面语言偏好；默认中文以保持既有行为。 */
enum class LanguagePreference(val labelZh: String, val labelEn: String) {
    FOLLOW_SYSTEM("跟随系统", "Follow system"),
    CHINESE("中文", "中文"),
    ENGLISH("English", "English"),
    ;

    companion object {
        fun fromKey(key: String?): LanguagePreference =
            entries.firstOrNull { it.name == key } ?: CHINESE
    }
}

/**
 * 进程内语言状态。刻意不依赖 Compose：JVM 单元测试也会读取它。
 * UI 在 AppRoot 中用 `key(english)` 在切换时重建界面子树。
 */
object AppLanguage {
    @Volatile
    var preference: LanguagePreference = LanguagePreference.CHINESE
        private set

    @Volatile
    var english: Boolean = false
        private set

    fun apply(preference: LanguagePreference, systemEnglish: Boolean = systemIsEnglish()) {
        this.preference = preference
        english = when (preference) {
            LanguagePreference.CHINESE -> false
            LanguagePreference.ENGLISH -> true
            LanguagePreference.FOLLOW_SYSTEM -> systemEnglish
        }
    }

    fun systemIsEnglish(): Boolean = Locale.getDefault().language.startsWith("en")
}

/** 双语文本：根据当前界面语言返回中文或英文。 */
fun tr(zh: String, en: String): String = if (AppLanguage.english) en else zh
