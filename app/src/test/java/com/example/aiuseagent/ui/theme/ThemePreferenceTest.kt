package com.example.aiuseagent.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 主题偏好的持久化键解析：设置页写入的是 name，读回时必须稳定。 */
class ThemePreferenceTest {

    @Test
    fun fromKey_roundTripsEveryPreference() {
        ThemePreference.entries.forEach { preference ->
            assertEquals(preference, ThemePreference.fromKey(preference.name))
        }
    }

    @Test
    fun fromKey_fallsBackToFollowSystem() {
        assertEquals(ThemePreference.FOLLOW_SYSTEM, ThemePreference.fromKey(null))
        assertEquals(ThemePreference.FOLLOW_SYSTEM, ThemePreference.fromKey("NOT_A_THEME"))
    }

    @Test
    fun everyPreferenceHasChineseLabel() {
        ThemePreference.entries.forEach { preference ->
            assertTrue("主题 " + preference.name + " 缺少界面标签", preference.label.isNotBlank())
        }
        assertFalse(ThemePreference.FOLLOW_SYSTEM.label.contains("FOLLOW_SYSTEM"))
    }
}
