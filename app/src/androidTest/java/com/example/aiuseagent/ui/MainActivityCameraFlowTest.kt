package com.example.aiuseagent.ui

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import com.example.aiuseagent.*
import com.example.aiuseagent.data.local.AppDatabase
import com.example.aiuseagent.data.provider.http.*
import com.example.aiuseagent.domain.attachment.AttachmentState
import com.example.aiuseagent.ui.chat.ChatViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MainActivityCameraFlowTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(instrumentation)
    private fun click(selector:BySelector) {
        repeat(3) {
            try {
                requireNotNull(device.wait(Until.findObject(selector),5000)) {"CAMERA_CONTROL_MISSING: $selector"}.click()
                return
            } catch(_:StaleObjectException) {device.waitForIdle()}
        }
        error("PICKER_NODE_STALE")
    }
    private suspend fun inMain(cacheFailure:Boolean=false,action:suspend (ChatViewModel,ActivityScenario<MainActivity>)->Unit) {
        val base=instrumentation.targetContext;val id=UUID.randomUUID().toString()
        val broken=File(base.cacheDir,"camera-fault-$id")
        if(cacheFailure) check(broken.createNewFile())
        val context=object:MainActivityAttachmentFlowTest.SyntheticContext(base,id) {
            override fun getCacheDir():File=if(cacheFailure) broken else super.getCacheDir()
        }
        val db=Room.databaseBuilder(base,AppDatabase::class.java,"main-camera-$id.db").build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val opens=AtomicInteger()
        lateinit var container:AppContainer
        instrumentation.runOnMainSync {
            container=AppContainer(context,databaseFactory={db},externalScope=scope,transport=object:HttpTransport {
                override suspend fun open(request:ProviderHttpRequest,timeouts:HttpTimeouts):StreamingHttpResponse {
                    opens.incrementAndGet();error("CAMERA_TEST_NETWORK_FORBIDDEN")
                }
            })
            ApplicationContainerFactory.instrumentationContainer=container
        }
        try {
            ActivityScenario.launch<MainActivity>(Intent(base,MainActivity::class.java)).use {scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("添加附件")),15000))
                lateinit var vm:ChatViewModel
                scenario.onActivity {
                    assertEquals(MainActivity::class.java.name,it.componentName.className)
                    assertSame(container,(it.application as AgentApplication).container)
                    vm=ViewModelProvider(container)[ChatViewModel::class.java]
                }
                withTimeout(15000) {vm.uiState.first {it.isReady}}
                action(vm,scenario)
                assertEquals(0,opens.get());assertTrue(vm.uiState.value.messages.isEmpty())
            }
        } finally {
            instrumentation.runOnMainSync {container.viewModelStore.clear()}
            scope.coroutineContext[Job]!!.cancelAndJoin();db.close()
            ApplicationContainerFactory.instrumentationContainer=null
            if(cacheFailure) check(broken.delete())
        }
    }
    private fun cameraFiles()=File(instrumentation.targetContext.cacheDir,"attachment-camera").list()?.toSet().orEmpty()
    private fun openCamera() {
        click(By.desc("添加附件"));click(By.text("拍照"))
        assertTrue(device.wait(Until.gone(By.desc("添加附件")),5000))
        // Grant only a camera-specific prompt; location access is not needed for this test.
        if(device.wait(Until.hasObject(By.textContains("take pictures")),1500)) {
            click(By.res("com.android.permissioncontroller","permission_allow_foreground_only_button"))
        }
        device.findObject(By.text(java.util.regex.Pattern.compile("No thanks|不用了|不，谢谢")))?.click()
    }
    @Test fun cameraConfirmAndCancelUseActualMainActivityWithoutSending()=runBlocking {
        val before=cameraFiles()
        inMain {vm,_ ->
            openCamera()
            val shutter=device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile(".*:id/shutter_button"))),5000)
                ?: device.findObject(By.desc(java.util.regex.Pattern.compile("Shutter|Take photo|Capture photo|拍照|快门")))
            requireNotNull(shutter) {"CAMERA_SHUTTER_MISSING"}.click()
            val done=device.wait(Until.findObject(By.res(java.util.regex.Pattern.compile(".*:id/done_button|.*:id/btn_done"))),5000)
                ?: device.findObject(By.desc(java.util.regex.Pattern.compile("Done|OK|完成|确定")))
            requireNotNull(done) {"CAMERA_CONFIRM_MISSING"}.click()
            assertTrue(device.wait(Until.hasObject(By.desc("添加附件")),5000))
            val ready=withTimeout(15000) {vm.uiState.first {it.attachments.singleOrNull()?.state in setOf(AttachmentState.READY,AttachmentState.FAILED)}}
            assertEquals(ready.attachments.single().error,AttachmentState.READY,ready.attachments.single().state)
            assertTrue(ready.attachments.single().handle!!.sizeBytes>0)
            click(By.text("预览"))
            val preview=withTimeout(5000) {vm.uiState.first {it.attachmentPreview!=null}}.attachmentPreview!!
            val image=requireNotNull(preview.image)
            val bitmap=requireNotNull(android.graphics.BitmapFactory.decodeByteArray(image,0,image.size))
            try {assertTrue(bitmap.width>0 && bitmap.height>0)} finally {bitmap.recycle()}
            click(By.text("关闭"));click(By.text("移除"))
            withTimeout(5000) {vm.uiState.first {it.attachments.isEmpty()}}
            assertEquals(before,cameraFiles())
            openCamera();device.pressBack()
            assertTrue(device.wait(Until.hasObject(By.desc("添加附件")),5000))
            instrumentation.waitForIdleSync()
            assertTrue(vm.uiState.value.attachments.isEmpty());assertEquals(before,cameraFiles())
        }
    }
    @Test fun switchScopeDropsLatePickerResult()=runBlocking {
        val resolver=instrumentation.targetContext.contentResolver
        val name="AAUA_LATE_"+UUID.randomUUID()+".txt"
        val uri=requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"text/plain")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/")
        }))
        try {
            resolver.openOutputStream(uri)!!.use {it.write("synthetic late result".toByteArray())}
            inMain {vm,scenario ->
                click(By.desc("添加附件"));click(By.text("文件"))
                val roots=device.wait(Until.findObject(By.desc("Show roots")),3000) ?: device.findObject(By.desc("显示根目录"))
                requireNotNull(roots).click()
                click(By.pkg("com.google.android.documentsui").text(java.util.regex.Pattern.compile("Downloads|下载")))
                assertTrue(device.wait(Until.hasObject(By.text(name)),5000))
                // Simulate a real scope transition while the external picker owns the foreground.
                scenario.onActivity {vm.setMode(com.example.aiuseagent.ui.chat.ChatMode.BUILD)}
                withTimeout(5000) {vm.uiState.first {it.mode==com.example.aiuseagent.ui.chat.ChatMode.BUILD}}
                click(By.text(name))
                assertTrue(device.wait(Until.hasObject(By.desc("添加附件")),5000))
                instrumentation.waitForIdleSync()
                assertNull("Old picker must not populate the new draft",withTimeoutOrNull(2000) {vm.uiState.first {it.attachments.isNotEmpty()}})
                assertEquals(com.example.aiuseagent.ui.chat.ChatMode.BUILD,vm.uiState.value.mode)
                assertTrue(vm.uiState.value.attachments.isEmpty())
                click(By.desc("添加附件"))
                for(label in listOf("文件","照片","拍照")) assertTrue(device.wait(Until.hasObject(By.text(label)),5000))
                device.pressBack()
            }
        } finally {assertEquals(1,resolver.delete(uri,null,null))}
    }
    @Test fun cameraOutputFailureShowsNoticeAndCreatesNoCard()=runBlocking {
        inMain(cacheFailure=true) {vm,_ ->
            click(By.desc("添加附件"));click(By.text("拍照"))
            withTimeout(5000) {vm.uiState.first {it.notice=="无法打开附件入口，请重试。"}}
            assertTrue(vm.uiState.value.attachments.isEmpty())
            assertTrue(device.wait(Until.hasObject(By.text("无法打开附件入口，请重试。")),5000))
        }
    }
}
