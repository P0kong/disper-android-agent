package com.example.aiuseagent.ui

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.example.aiuseagent.data.SafImportContractTest
import com.example.aiuseagent.testing.SyntheticSafActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Genuine DocumentsUI picker; test runner prepares only a fresh AAUA_T01_MCP UUID root. */
@RunWith(AndroidJUnit4::class)
class SafPickerUiTest {
    @Test fun systemPickerGrantsOnlyThePreparedSyntheticTreeThenRunsFileContract() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val expected=InstrumentationRegistry.getArguments().getString("safTree") ?: error("SYNTHETIC_TREE_REQUIRED")
        val id=android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(expected))
        require(Regex("primary:Download/AAUA_T01_MCP_[a-f0-9-]{36}").matches(id))
        val device=UiDevice.getInstance(instrumentation)
        device.wakeUp()
        val intent=Intent(instrumentation.targetContext,SyntheticSafActivity::class.java)
            .putExtra("syntheticTree",expected).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<SyntheticSafActivity>(intent).use { scenario ->
            // API 37 DocumentsUI uses the platform positive button for tree selection.
            // Verify the exact synthetic folder before either supported selector is clicked.
            assertTrue(device.wait(Until.hasObject(By.textContains(id.substringAfterLast('/'))),10000))
            val select=device.wait(Until.findObject(By.pkg("com.google.android.documentsui").res("android","button1")),5000)
                ?: device.wait(Until.findObject(By.pkg("com.android.documentsui").res("android","button1")),1000)
                ?: device.wait(Until.findObject(By.res("com.google.android.documentsui","action_menu_select")),5000)
                ?: device.wait(Until.findObject(By.res("com.android.documentsui","action_menu_select")),5000)
                ?: error("SYSTEM_PICKER_SELECT_NOT_FOUND")
            assertTrue(select.isEnabled)
            select.click()
            // Both screens use button1; wait for the new dialog before resolving its button.
            val message=device.wait(Until.findObject(By.res("android","message")),5000) ?: error("SYSTEM_GRANT_MESSAGE_NOT_FOUND")
            assertTrue("Confirmation must name the exact synthetic folder",message.text.contains(id.substringAfterLast('/')))
            val allow=device.wait(Until.findObject(By.res("android","button1")),5000) ?: error("SYSTEM_GRANT_CONFIRMATION_NOT_FOUND")
            allow.click()
            assertTrue(device.wait(Until.hasObject(By.text("合成目录授权完成")),10000))
            scenario.onActivity { assertEquals(expected,it.grantedTree) }
            captureSyntheticUiEvidence("saf-grant.png")
            SafImportContractTest().txtAndMarkdownNamesContentAndConflictArePreserved()
        }
    }
}
