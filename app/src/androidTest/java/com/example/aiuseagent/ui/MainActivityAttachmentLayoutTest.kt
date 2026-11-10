package com.example.aiuseagent.ui

import android.content.Intent
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.aiuseagent.*
import com.example.aiuseagent.data.local.AppDatabase
import com.example.aiuseagent.data.provider.http.*
import com.example.aiuseagent.domain.attachment.*
import com.example.aiuseagent.ui.chat.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class MainActivityAttachmentLayoutTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(instrumentation)
    private fun shell(command:String):String=android.os.ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText().trim()}
    private fun click(selector:BySelector) {
        repeat(3) {try {requireNotNull(device.wait(Until.findObject(selector),5000)).click();return}
            catch(_:StaleObjectException) {device.waitForIdle()}}
        error("UI_NODE_STALE")
    }
    @Test fun actualMainActivityTenCardsLargeFontAndImeKeepControlsReachable()=runBlocking {
        val originalScale=shell("settings get system font_scale")
        require(originalScale=="null" || originalScale.toFloatOrNull()?.let {it in 0.5f..3f}==true)
        val base=instrumentation.targetContext
        val id=UUID.randomUUID().toString()
        val job=requireNotNull(InstrumentationRegistry.getArguments().getString("bridgeJobId"))
        require(runCatching {UUID.fromString(job)}.isSuccess)
        val context=MainActivityAttachmentFlowTest.SyntheticContext(base,id)
        val db=Room.databaseBuilder(base,AppDatabase::class.java,"main-layout-$id.db").build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val opens=AtomicInteger();val uris=mutableListOf<android.net.Uri>()
        lateinit var container:AppContainer
        instrumentation.runOnMainSync {
            container=AppContainer(context,databaseFactory={db},externalScope=scope,transport=object:HttpTransport {
                override suspend fun open(request:ProviderHttpRequest,timeouts:HttpTimeouts):StreamingHttpResponse {
                    opens.incrementAndGet();error("LAYOUT_TEST_NETWORK_FORBIDDEN")
                }
            })
            ApplicationContainerFactory.instrumentationContainer=container
        }
        try {
            shell("settings put system font_scale 1.5")
            withTimeout(10000) {while(kotlin.math.abs(base.resources.configuration.fontScale-1.5f)>0.01f) delay(100)}
            repeat(10) {index ->
                val uri=requireNotNull(base.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"合成_$index"+"长名称".repeat(12)+".txt")
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/AAUA_STRESS_$id/")
                }))
                uris+=uri;base.contentResolver.openOutputStream(uri)!!.use {it.write("synthetic attachment $index".toByteArray())}
            }
            ActivityScenario.launch<MainActivity>(Intent(base,MainActivity::class.java)).use {scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("添加附件")),15000))
                lateinit var vm:ChatViewModel
                scenario.onActivity {
                    assertEquals(MainActivity::class.java.name,it.componentName.className)
                    assertSame(container,(it.application as AgentApplication).container)
                    assertEquals(1.5f,it.resources.configuration.fontScale,0.01f)
                    vm=ViewModelProvider(container)[ChatViewModel::class.java]
                }
                withTimeout(15000) {vm.uiState.first {it.isReady}}
                val workspace=File(context.filesDir,"workspaces/default")
                assertTrue(workspace.isDirectory);assertTrue(workspace.listFiles()!!.isEmpty())
                val evidence=File(base.getExternalFilesDir(null),"bridge-evidence/$job").apply {check(mkdirs())}
                val receipts=mutableListOf<JsonObject>()
                for(mode in listOf(ChatMode.CONVERSATION,ChatMode.BUILD)) {
                    scenario.onActivity {vm.setMode(mode)}
                    withTimeout(10000) {vm.uiState.first {it.mode==mode && it.isReady && it.attachments.isEmpty()}}
                    val ticket=withContext(Dispatchers.Main) {requireNotNull(vm.prepareAttachmentPick(AttachmentPickerKind.FILE))}
                    if(mode==ChatMode.CONVERSATION) assertNull(ticket.scope.workspaceId)
                    // Stress fixture enters the real staging/parser path; picker success is tested separately.
                    scenario.onActivity {vm.acceptAttachmentResult(ticket,uris.map {it.toString()})}
                    withTimeout(15000) {vm.uiState.first {it.attachments.size==10 && it.attachments.all {card -> card.state==AttachmentState.READY}}}
                    val input=requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText")),5000))
                    input.click();input.text="synthetic layout input"
                    withTimeout(5000) {
                        while(true) {
                            var visible=false
                            scenario.onActivity {visible=ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true}
                            if(visible) break
                            delay(100)
                        }
                    }
                    for(selector in listOf(By.desc("添加附件"),By.desc("发送到当前模型"),By.text(vm.uiState.value.modelLabel))) {
                        val bounds=requireNotNull(device.wait(Until.findObject(selector),5000)).visibleBounds
                        assertTrue("Control must have visible tap bounds",bounds.width()>0 && bounds.height()>0)
                    }
                    if(mode==ChatMode.BUILD) assertTrue(requireNotNull(device.findObject(By.text("仅查看"))).visibleBounds.height()>0)
                    val file=if(mode==ChatMode.BUILD) "main-build-large-font.png" else "main-chat-large-font.png"
                    scenario.onActivity {
                        assertEquals(MainActivity::class.java.name,it.componentName.className)
                        assertSame(container,(it.application as AgentApplication).container)
                    }
                    assertTrue(device.takeScreenshot(File(evidence,file)))
                    receipts+=buildJsonObject {put("mode",mode.name);put("cards",10);put("fontScale",1.5);put("imeVisible",true);put("screen",file)}
                    click(By.desc("添加附件"))
                    for(label in listOf("文件","照片","拍照")) assertTrue(device.wait(Until.hasObject(By.text(label)),5000))
                    device.pressBack()
                    click(By.text(vm.uiState.value.modelLabel))
                    assertTrue(device.wait(Until.hasObject(By.text("当前供应商")),5000))
                    click(By.desc("返回"))
                    if(mode==ChatMode.BUILD) {
                        click(By.text("仅查看"))
                        assertTrue(device.wait(Until.hasObject(By.text("构建会话权限")),5000))
                        click(By.text("取消"))
                    }
                    assertEquals(10,vm.uiState.value.attachments.size)
                    assertTrue(workspace.listFiles()!!.isEmpty());assertEquals(0,opens.get());assertTrue(vm.uiState.value.messages.isEmpty())
                }
                File(evidence,"main-layout.json").writeText(buildJsonObject {
                    put("component",MainActivity::class.java.name);put("fixtureId",id);put("jobId",job)
                    put("networkRequests",opens.get());put("workspaceFiles",0);put("cases",JsonArray(receipts))
                }.toString())
            }
        } finally {
            shell(if(originalScale=="null") "settings delete system font_scale" else "settings put system font_scale $originalScale")
            assertEquals(originalScale,shell("settings get system font_scale"))
            for(uri in uris) assertEquals(1,base.contentResolver.delete(uri,null,null))
            instrumentation.runOnMainSync {container.viewModelStore.clear()}
            scope.coroutineContext[Job]!!.cancelAndJoin();db.close();ApplicationContainerFactory.instrumentationContainer=null
        }
    }
}
