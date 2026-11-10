package com.example.aiuseagent.ui

import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import java.io.File
import java.util.UUID
import org.junit.Assert.assertTrue

internal fun captureSyntheticUiEvidence(name: String) {
    require(name in setOf("permission-large-font.png","saf-grant.png","attachment-cards.png"))
    val instrumentation=InstrumentationRegistry.getInstrumentation()
    val id=InstrumentationRegistry.getArguments().getString("bridgeJobId") ?: UUID.randomUUID().toString()
    require(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(id))
    if(name=="attachment-cards.png") {
        val device=UiDevice.getInstance(instrumentation)
        // 只断言前台/焦点确实是合成宿主；不绑定 applicationId（发布候选为 .design，类名仍在原命名空间）。
        val expected="\\.testing\\.SyntheticAttachmentActivity"
        assertTrue(Regex("(?m)^\\s*(?:topResumedActivity|mResumedActivity)[=:][^\\r\\n]*$expected(?:\\s|[}])").containsMatchIn(device.executeShellCommand("dumpsys activity activities")))
        assertTrue(Regex("(?m)^\\s*mCurrentFocus=[^\\r\\n]*$expected(?:\\s|[}])").containsMatchIn(device.executeShellCommand("dumpsys window displays")))
    }
    val root=File(requireNotNull(instrumentation.targetContext.getExternalFilesDir("bridge-evidence")),id)
    check(root.mkdirs() || root.isDirectory)
    val file=File(root,name);check(!file.exists())
    assertTrue("Synthetic UI screenshot must be saved",UiDevice.getInstance(instrumentation).takeScreenshot(file))
}
