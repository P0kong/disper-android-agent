package com.example.aiuseagent.ui

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.aiuseagent.data.execution.PermissionLevel
import com.example.aiuseagent.testing.SyntheticPermissionActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** UI Automator drives the actual Compose dialog; does not require Espresso hidden APIs. */
@RunWith(AndroidJUnit4::class)
class PermissionModeUiTest {
    @Test fun unknownReviewShowsConsumedRecoveryWithoutOfferingReplay() {
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        ActivityScenario.launch(SyntheticPermissionActivity::class.java).use { scenario ->
            val entry=device.wait(Until.findObject(By.text("合成操作核查")),5000)
                ?: error("RECOVERY_REVIEW_ENTRY_MISSING")
            entry.click()
            assertTrue(device.wait(Until.hasObject(By.text("操作核查")),5000))
            assertTrue(device.hasObject(By.textContains("目标：a.txt")))
            assertTrue(device.hasObject(By.textContains("已占用，不能重放")))
            assertFalse(device.hasObject(By.text("重试")))
            requireNotNull(device.findObject(By.text("关闭"))).click()
            assertTrue(device.wait(Until.gone(By.text("操作核查")),5000))
            scenario.onActivity {assertEquals(0,it.callbacks);assertEquals(PermissionLevel.VIEW_ONLY,it.selected)}
        }
    }
    @Test fun fullNeedsSecondConfirmationAndCancelPreservesViewOnlyWithLargeFontAndKeyboard() {
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.wakeUp()
        ActivityScenario.launch(SyntheticPermissionActivity::class.java).use { scenario ->
            val input = device.wait(Until.findObject(By.clazz("android.widget.EditText")),10000)
                ?: error("SYNTHETIC_INPUT_NOT_FOUND")
            input.click()
            input.text = "synthetic"
            assertTrue("Software keyboard must really be visible",
                device.wait(Until.hasObject(By.text("合成键盘已显示")),5000))
            requireNotNull(device.findObject(By.text("打开权限"))).click()
            assertTrue(device.wait(Until.hasObject(By.text("构建会话权限")),5000))
            repeat(4) {
                if (!device.hasObject(By.text("完全权限 · 本进程")))
                    device.findObject(By.scrollable(true))?.scroll(Direction.DOWN,0.7f)
            }
            requireNotNull(device.findObject(By.text("完全权限 · 本进程"))).click()
            assertTrue(device.wait(Until.hasObject(By.text("启用完全权限？")),5000))
            assertTrue(device.hasObject(By.text("确认启用")))
            scenario.onActivity {
                assertTrue(it.imeObserved)
                assertEquals(PermissionLevel.VIEW_ONLY,it.selected)
                assertEquals(0,it.callbacks)
            }
            captureSyntheticUiEvidence("permission-large-font.png")
            requireNotNull(device.findObject(By.text("取消"))).click()
            assertTrue(device.wait(Until.gone(By.text("启用完全权限？")),5000))
            scenario.onActivity {
                assertEquals(PermissionLevel.VIEW_ONLY,it.selected)
                assertEquals(0,it.callbacks)
            }
        }
    }
}
