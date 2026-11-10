package com.example.aiuseagent.ui

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.aiuseagent.domain.attachment.*
import com.example.aiuseagent.testing.SyntheticAttachmentActivity
import com.example.aiuseagent.ui.chat.ChatMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first

/** Production Compose driven without Espresso's removed API 37 hidden method. */
@RunWith(AndroidJUnit4::class)
class AttachmentComposerUiTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(instrumentation)
    private val jobId=InstrumentationRegistry.getArguments().getString("bridgeJobId") ?: UUID.randomUUID().toString()
    private fun launch(scale:Float=1f):ActivityScenario<SyntheticAttachmentActivity> {
        device.wakeUp()
        return ActivityScenario.launch(Intent(instrumentation.targetContext,SyntheticAttachmentActivity::class.java)
            .putExtra("bridgeJobId",jobId).putExtra("fontScale",scale))
    }
    private fun button(description:String)=requireNotNull(device.wait(Until.findObject(By.desc(description)),5000))
    private fun clickFresh(selector:BySelector) {
        repeat(3) {
            device.waitForIdle()
            try {requireNotNull(device.wait(Until.findObject(selector),5000)).click();return}
            catch(_:StaleObjectException) { /* DocumentsUI refreshes its roots asynchronously. */ }
        }
        error("SYSTEM_PICKER_NODE_STALE")
    }
    @Test fun actualSystemFilePickerReturnsSyntheticTextToProductionDraftAndAttachmentOnlySend()=runBlocking {
        val resolver=instrumentation.targetContext.contentResolver
        val name="AAUA_${UUID.randomUUID()}.txt"
        val uri=requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"text/plain")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/")
        }))
        var sourceDeleted=false
        try {
            requireNotNull(resolver.openOutputStream(uri)).use {it.write("synthetic system-picker text".toByteArray())}
            launch().use {scenario ->
                lateinit var vm:com.example.aiuseagent.ui.chat.ChatViewModel
                scenario.onActivity {it.startPipeline();vm=requireNotNull(it.pipelineViewModel)}
                withTimeout(10000) {vm.uiState.first {it.isReady}}
                button("添加附件").click()
                requireNotNull(device.wait(Until.findObject(By.text("文件")),5000)).click()
                val roots=device.wait(Until.findObject(By.desc("Show roots")),3000)
                    ?: device.findObject(By.desc("显示根目录"))
                requireNotNull(roots) {"DOCUMENT_ROOT_NAVIGATION_MISSING"}.click()
                clickFresh(By.pkg("com.google.android.documentsui").text(java.util.regex.Pattern.compile("Downloads|下载")))
                // Only select the uniquely named file this test created; never choose a position in Recents.
                clickFresh(By.text(name))
                println("SYNTHETIC_STAGE document_clicked package="+device.currentPackageName)
                val ready=withTimeoutOrNull(15000) {vm.uiState.first {it.attachments.singleOrNull()?.state in setOf(AttachmentState.READY,AttachmentState.FAILED)}}
                assertNotNull("Return/parse timeout: package=${device.currentPackageName}, states=${vm.uiState.value.attachments.map {it.state}}, notice=${vm.uiState.value.notice}",ready)
                requireNotNull(ready)
                assertEquals(ready.attachments.single().error,AttachmentState.READY,ready.attachments.single().state)
                println("SYNTHETIC_STAGE draft_ready")
                assertEquals(name,ready.attachments.single().name)
                assertTrue(ready.messages.isEmpty());assertTrue(ready.input.isEmpty())
                assertEquals(1,resolver.delete(uri,null,null))
                sourceDeleted=true
                button("发送到当前模型").click()
                val sent=withTimeoutOrNull(15000) {vm.uiState.first {it.messages.size>=2 && !it.isGenerating}}
                assertNotNull("Send timeout: messages=${vm.uiState.value.messages.size}, error=${vm.uiState.value.generationError}, notice=${vm.uiState.value.notice}",sent)
                requireNotNull(sent)
                assertEquals("",sent.messages.first().content)
                assertTrue(sent.attachments.isEmpty())
                assertTrue("Sent attachment must remain visible in its message",device.wait(Until.hasObject(By.text(name)),5000))
                val sessionId=requireNotNull(sent.activeSessionId)
                scenario.onActivity {vm.newSession()}
                withTimeout(10000) {vm.uiState.first {it.activeSessionId==null && it.messages.isEmpty()}}
                scenario.onActivity {vm.selectSession(sessionId)}
                withTimeout(10000) {vm.uiState.first {state -> state.messages.any {message -> message.attachments.any {it.name==name}}}}
                assertTrue("Reopened session must retain attachment metadata",device.wait(Until.hasObject(By.text(name)),5000))
            }
        } finally {if(!sourceDeleted) assertEquals(1,resolver.delete(uri,null,null))}
    }
    @Test fun missingCameraActivityShowsNoticeAndReleasesOwnedOutput()=runBlocking {
        val root=java.io.File(instrumentation.targetContext.cacheDir,"attachment-camera")
        val before=root.list()?.toSet().orEmpty()
        launch().use {scenario ->
            lateinit var vm:com.example.aiuseagent.ui.chat.ChatViewModel
            scenario.onActivity {it.simulateMissingPicker=true;it.startPipeline();vm=requireNotNull(it.pipelineViewModel)}
            withTimeout(10000) {vm.uiState.first {it.isReady}}
            button("添加附件").click();clickFresh(By.text("拍照"))
            withTimeout(5000) {vm.uiState.first {it.notice=="没有可用的系统相机。"}}
            assertTrue(device.wait(Until.hasObject(By.text("没有可用的系统相机。")),5000))
            assertTrue(vm.uiState.value.attachments.isEmpty());assertTrue(vm.uiState.value.messages.isEmpty())
            assertEquals(before,root.list()?.toSet().orEmpty())
        }
    }
    @Test fun actualPhotoPickerCancelReturnsWithoutCreatingAttachment()=runBlocking {
        launch().use {scenario ->
            lateinit var vm:com.example.aiuseagent.ui.chat.ChatViewModel
            scenario.onActivity {it.startPipeline();vm=requireNotNull(it.pipelineViewModel)}
            withTimeout(10000) {vm.uiState.first {it.isReady}}
            button("添加附件").click()
            clickFresh(By.text("照片"))
            assertTrue("System picker must take foreground",device.wait(Until.gone(By.pkg(instrumentation.targetContext.packageName)),5000))
            device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.text("合成附件 · $jobId")),5000))
            scenario.onActivity {
                assertTrue(vm.uiState.value.attachments.isEmpty())
                assertTrue(vm.uiState.value.messages.isEmpty())
                assertFalse(vm.uiState.value.canSendMessage)
            }
            Unit
        }
    }
    @Test fun chatAndBuildExposeExactlyThreeActionsWithoutSending() {
        launch().use {scenario ->
            for(mode in listOf(ChatMode.CONVERSATION,ChatMode.BUILD)) {
                scenario.onActivity {it.state=it.state.copy(mode=mode)}
                for((label,kind) in listOf("文件" to AttachmentPickerKind.FILE,"照片" to AttachmentPickerKind.PHOTO,"拍照" to AttachmentPickerKind.CAMERA)) {
                    button("添加附件").click()
                    for(text in listOf("文件","照片","拍照")) assertTrue(device.wait(Until.hasObject(By.text(text)),5000))
                    requireNotNull(device.findObject(By.text(label))).click()
                    assertTrue(device.wait(Until.gone(By.text("拍照")),5000))
                    scenario.onActivity {assertEquals(kind,it.selected);assertEquals(0,it.sends)}
                }
                button("发送到当前模型").click()
                scenario.onActivity {assertFalse(it.state.canSendMessage);assertEquals(0,it.sends)}
            }
        }
    }
    @Test fun removingOnlyReadyCardDisablesAttachmentOnlySend() {
        launch().use {scenario ->
            scenario.onActivity {it.cards(1)}
            assertTrue(device.wait(Until.hasObject(By.text("合成附件1.txt")),5000))
            button("发送到当前模型").click()
            scenario.onActivity {assertTrue(it.state.canSendMessage);assertEquals(1,it.sends)}
            button("移除附件：合成附件1.txt").click()
            assertTrue(device.wait(Until.gone(By.text("合成附件1.txt")),5000))
            button("发送到当前模型").click()
            scenario.onActivity {assertFalse(it.state.canSendMessage);assertEquals(1,it.sends)}
        }
    }
    @Test fun tenCardsLargeFontAndKeyboardKeepPlusAndSendReachable() {
        launch(1.5f).use {scenario ->
            scenario.onActivity {it.cards(10)}
            val input=requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")),5000))
            input.click();input.text="synthetic"
            assertTrue("Software keyboard must be visible",device.wait(Until.hasObject(By.text("合成键盘已显示")),5000))
            scenario.onActivity {assertTrue(it.imeObserved);assertEquals(jobId,it.jobId);assertEquals(10,it.state.attachments.size)}
            assertTrue(button("添加附件").visibleBounds.height()>0)
            assertTrue(button("发送到当前模型").visibleBounds.height()>0)
            captureSyntheticUiEvidence("attachment-cards.png")
            button("发送到当前模型").click()
            scenario.onActivity {assertEquals(1,it.sends)}
        }
    }
}
