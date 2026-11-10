package com.example.aiuseagent.ui

import android.content.Context
import android.content.ContextWrapper
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
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Actual MainActivity/AppRoot, private synthetic state, and a fail-closed network boundary. */
@RunWith(AndroidJUnit4::class)
class MainActivityAttachmentFlowTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val device=UiDevice.getInstance(instrumentation)
    private fun click(selector:BySelector) {
        repeat(3) {
            try {requireNotNull(device.wait(Until.findObject(selector),5000)).click();return}
            catch(_:StaleObjectException) {device.waitForIdle()}
        }
        error("PICKER_NODE_STALE")
    }
    private fun scrollTo(selector:BySelector):UiObject2 {
        repeat(8) {
            device.findObject(selector)?.let {return it}
            val scrollable=device.findObject(By.scrollable(true))
            if(scrollable!=null) scrollable.scroll(Direction.DOWN,0.65f)
            else device.swipe(device.displayWidth/2,device.displayHeight*7/10,
                device.displayWidth/2,device.displayHeight*3/10,20)
            device.waitForIdle()
        }
        error("SETTINGS_FIELD_NOT_REACHABLE")
    }
    private fun enterText(selector:BySelector,value:String) {
        val node=scrollTo(selector)
        node.click()
        val editor=requireNotNull(device.wait(Until.findObject(By.clazz("android.widget.EditText").focused(true)),3000)) {
            "SETTINGS_FOCUSED_EDITOR_MISSING: node=${node.className}; editors=${device.findObjects(By.clazz("android.widget.EditText")).size}"
        }
        editor.text=value
        device.waitForIdle()
    }
    private fun selectDownload(name:String) {
        click(By.desc("添加附件"));click(By.text("文件"))
        val roots=device.wait(Until.findObject(By.desc("Show roots")),3000) ?: device.findObject(By.desc("显示根目录"))
        requireNotNull(roots).click()
        click(By.pkg("com.google.android.documentsui").text(java.util.regex.Pattern.compile("Downloads|下载")))
        click(By.text(name))
    }
    private fun selectPhoto(markerYear:String) {
        click(By.desc("添加附件"));click(By.text("照片"))
        // AOSP picker albums need not expose custom folders. Match our artificial capture
        // date in accessibility metadata, never a positional/first photo from Recents.
        val selector=By.desc(java.util.regex.Pattern.compile("(?is)(?=.*"+markerYear+")(?=.*(photo|拍摄)).*"))
        assertTrue("SYNTHETIC_PHOTO_DATE_NOT_VISIBLE",device.wait(Until.hasObject(selector),10000))
        val matches=device.findObjects(selector)
        assertEquals("Synthetic photo marker must be unique",1,matches.size)
        matches.single().click()
        click(By.text(java.util.regex.Pattern.compile("Add.*|添加.*")))
    }
    @Test fun actualSettingsAndMainActivitySendNormalizedImagePixelsToLoopbackOnly()=imageFlow(false)
    @Test fun photoUiProducesSelectedImageBytes()=imageFlow(true)
    @Test fun historyUiShowsSavedImageWithoutAnAdditionalRequest()=imageFlow(false,history=true)
    internal fun imageFlow(photo:Boolean,restartStage:String?=null,fixtureId:String?=null,history:Boolean=false)=runBlocking {
        AttachmentLoopbackServer().use {server ->
            val base=instrumentation.targetContext
            val id=fixtureId ?: UUID.randomUUID().toString();require(runCatching {UUID.fromString(id)}.isSuccess)
            require(restartStage==null || restartStage in setOf("seed","restore") && photo)
            val context=SyntheticContext(base,id)
            val restartMarker=File(context.filesDir,"restart-profile.json")
            val db=Room.databaseBuilder(base,AppDatabase::class.java,"main-attachment-$id.db").build()
            val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
            lateinit var container:AppContainer
            instrumentation.runOnMainSync {
                container=AppContainer(context,databaseFactory={db},externalScope=scope,transport=server.transport)
                ApplicationContainerFactory.instrumentationContainer=container
            }
            val bitmap=android.graphics.Bitmap.createBitmap(4,3,android.graphics.Bitmap.Config.ARGB_8888)
            var bytes=try {
                bitmap.eraseColor(android.graphics.Color.BLUE)
                bitmap.setPixel(0,0,android.graphics.Color.RED);bitmap.setPixel(3,2,android.graphics.Color.GREEN)
                java.io.ByteArrayOutputStream().also {check(bitmap.compress(if(photo) android.graphics.Bitmap.CompressFormat.JPEG else android.graphics.Bitmap.CompressFormat.PNG,100,it))}.toByteArray()
            } finally {bitmap.recycle()}
            if(photo) {
                val exifFile=File(context.filesDir,"synthetic-source-$id.jpg")
                try {
                    exifFile.writeBytes(bytes)
                    android.media.ExifInterface(exifFile.absolutePath).apply {
                        setAttribute(android.media.ExifInterface.TAG_DATETIME_ORIGINAL,"2098:07:16 13:47:00")
                        setAttribute(android.media.ExifInterface.TAG_DATETIME,"2098:07:16 13:47:00")
                        setAttribute(android.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL,"+00:00")
                        setAttribute(android.media.ExifInterface.TAG_ORIENTATION,android.media.ExifInterface.ORIENTATION_ROTATE_90.toString())
                        saveAttributes()
                    }
                    bytes=exifFile.readBytes()
                } finally {check(exifFile.delete())}
            }
            val resolver=base.contentResolver;val name="AAUA_IMAGE_$id."+if(photo) "jpg" else "png"
            val album="AAUA_$id"
            val capture=java.time.Instant.parse("2098-07-16T13:47:00Z").toEpochMilli()
            val collection=if(photo) android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI else android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val uri=requireNotNull(resolver.insert(collection,android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE,if(photo) "image/jpeg" else "image/png")
                if(photo) put(android.provider.MediaStore.MediaColumns.IS_PENDING,1)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,if(photo) "Pictures/$album/" else "Download/")
            }))
            var deleted=false
            try {
                resolver.openOutputStream(uri)!!.use {it.write(bytes)}
                if(photo) {
                    assertEquals(1,resolver.update(uri,android.content.ContentValues().apply {put(android.provider.MediaStore.MediaColumns.IS_PENDING,0)},null,null))
                    resolver.query(uri,arrayOf(android.provider.MediaStore.Images.Media.BUCKET_DISPLAY_NAME,android.provider.MediaStore.Images.Media.DATE_TAKEN),null,null,null)!!.use {
                        assertTrue(it.moveToFirst());assertEquals(album,it.getString(0));assertEquals(capture,it.getLong(1))
                    }
                }
                device.wakeUp()
                ActivityScenario.launch<MainActivity>(Intent(base,MainActivity::class.java)).use {scenario ->
                    assertTrue(device.wait(Until.hasObject(By.desc("添加附件")),15000))
                    lateinit var vm:ChatViewModel
                    scenario.onActivity {
                        assertEquals(MainActivity::class.java.name,it.componentName.className)
                        assertSame(container,(it.application as AgentApplication).container)
                        vm=ViewModelProvider(container)[ChatViewModel::class.java]
                    }
                    withTimeout(15000) {vm.uiState.first {it.isReady}}
                    var seedPid:Int?=null
                    if(restartStage=="restore") {
                        val marker=Json.parseToJsonElement(restartMarker.readText()).jsonObject
                        seedPid=marker.getValue("pid").jsonPrimitive.int
                        assertNotEquals("Must restart the app process, not only recreate Activity",seedPid,android.os.Process.myPid())
                        val old=com.example.aiuseagent.data.ConnectionProfileCodec.decode(marker.getValue("profile").jsonPrimitive.content)!!
                        assertEquals(old,container.settingsRepository.savedConnectionProfile())
                        assertEquals(old,container.providerRepository.prepare().config.connectionProfile)
                        val restored=container.providerRepository.credentialStatus(container.providerRepository.activeType())
                        assertTrue("Keystore 凭据应跨进程重启保留", restored.isPresent)
                        assertTrue("重启后持久凭据应被仓库识别为已知秘密",
                            container.providerRepository.containsKnownCredential(AttachmentLoopbackServer.KEY))
                        assertEquals(0,server.attempts.get())
                        if(history) {
                            val oldSession=marker.getValue("sessionId").jsonPrimitive.long
                            val oldMessage=marker.getValue("messageId").jsonPrimitive.long
                            val oldAttachment=marker.getValue("attachmentId").jsonPrimitive.content
                            withTimeout(5000) {vm.uiState.first {state -> state.sessions.any {it.id==oldSession}}}
                            scenario.onActivity {vm.selectSession(oldSession)}
                            withTimeout(5000) {vm.uiState.first {state -> state.activeSessionId==oldSession &&
                                state.messages.any {it.id==oldMessage && it.attachments.any {a -> a.id==oldAttachment}}}}
                            val row=requireNotNull(db.attachmentObjectDao().attachment(oldAttachment))
                            assertEquals(marker.getValue("localToken").jsonPrimitive.content,row.localToken)
                            assertEquals(marker.getValue("sourceSha256").jsonPrimitive.content,row.sha256)
                            val source=File(context.filesDir,"attachments/${row.localToken}.blob").readBytes()
                            assertEquals(row.sha256,java.security.MessageDigest.getInstance("SHA-256").digest(source).joinToString("") {"%02x".format(it)})
                            showHistory(base,server,0,"history-restored.png")
                            assertEquals(0,server.attempts.get());assertEquals(0,server.requests.get())
                            scenario.onActivity {vm.newSession()}
                            withTimeout(5000) {vm.uiState.first {it.activeSessionId!=oldSession && it.messages.isEmpty()}}
                        }
                        selectPhoto("2098")
                        val uncredentialed=withTimeout(15000) {vm.uiState.first {it.attachments.singleOrNull()?.state==AttachmentState.READY}}
                        val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
                        assertEquals(digest,uncredentialed.attachments.single().handle!!.sha256)
                        // 凭据已恢复：仅验证“不发送就不会有网络请求”，随后移除附件，保持与后续发送计数断言隔离。
                        assertEquals(0,server.attempts.get());assertEquals(0,server.requests.get())
                        click(By.text("移除"))
                        withTimeout(5000) {vm.uiState.first {it.attachments.isEmpty()}}
                    }
                    val settings=ViewModelProvider(container)[com.example.aiuseagent.ui.settings.SettingsViewModel::class.java]
                    click(By.desc("设置"));click(By.text("模型配置"))
                    if(restartStage!="restore") {
                        click(By.text("OpenAI"))
                        enterText(By.desc("连接地址"),"https://api.openai.com/v1")
                        enterText(By.desc("模型凭据输入"),AttachmentLoopbackServer.KEY)
                        enterText(By.desc("模型名称输入"),"gpt-4.1-mini")
                    }
                    assertEquals("OPENAI_COMPATIBLE",settings.providerForm.value.providerType)
                    // On restart the endpoint/model must already be present before entering a new synthetic credential.
                    assertEquals("https://api.openai.com/v1",settings.providerForm.value.baseUrl)
                    assertEquals("gpt-4.1-mini",settings.providerForm.value.model)
                    if(restartStage=="restore") {
                        // 新策略：凭据跨进程保留，重启后不需要重新输入。
                        assertTrue("Restored form should show saved credential",settings.providerForm.value.credentialConfigured)
                        assertTrue("Credential must not be echoed as plaintext",settings.providerForm.value.apiKey.isEmpty())
                    } else {
                        assertTrue("Synthetic credential input did not reach form",
                            settings.providerForm.value.apiKey==AttachmentLoopbackServer.KEY)
                    }
                    scrollTo(By.text("保存并应用配置")).click()
                    val notice=withTimeout(5000) {settings.providerForm.first {it.notice!=null}}.notice
                    assertTrue("Save notice: $notice",notice!!.contains("非秘密配置已保存"))
                    assertTrue(scrollTo(By.textContains("非秘密配置已保存")).isEnabled)
                    assertEquals("gpt-4.1-mini",container.settingsRepository.savedConnectionProfile()?.modelId)
                    assertEquals(0,server.requests.get())
                    click(By.desc("返回"));click(By.desc("返回"))
                    if(photo) selectPhoto("2098") else selectDownload(name)
                    val ready=withTimeout(15000) {vm.uiState.first {it.attachments.singleOrNull()?.state in setOf(AttachmentState.READY,AttachmentState.FAILED)}}
                    assertEquals(ready.attachments.single().error,AttachmentState.READY,ready.attachments.single().state)
                    assertEquals(0,server.requests.get());assertTrue(ready.messages.isEmpty())
                    val originalHash=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
                    assertEquals("Selected copy must be our synthetic image before any send",originalHash,ready.attachments.single().handle!!.sha256)
                    assertEquals(1,resolver.delete(uri,null,null));deleted=true
                    click(By.desc("发送到当前模型"))
                    val sent=withTimeoutOrNull(20000) {vm.uiState.first {it.messages.size>=2 && !it.isGenerating}}
                    assertNotNull("Image send failed: state=${vm.uiState.value.generationError}; fixture=${server.failure?.javaClass?.simpleName}",sent)
                    val body=requireNotNull(server.bodies.poll(5,java.util.concurrent.TimeUnit.SECONDS))
                    val parts=Json.parseToJsonElement(body).jsonObject["messages"]!!.jsonArray.last().jsonObject["content"]!!.jsonArray
                    val image=parts.single {it.jsonObject["type"]?.jsonPrimitive?.content=="image_url"}.jsonObject
                    val data=image["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content
                    val actual=android.util.Base64.decode(data.substringAfter(','),android.util.Base64.DEFAULT)
                    val decoded=requireNotNull(android.graphics.BitmapFactory.decodeByteArray(actual,0,actual.size))
                    val raw=requireNotNull(android.graphics.BitmapFactory.decodeByteArray(bytes,0,bytes.size))
                    val expected=if(photo) android.graphics.Bitmap.createBitmap(raw,0,0,raw.width,raw.height,
                        android.graphics.Matrix().apply {postRotate(90f)},true) else raw
                    try {
                        assertEquals(expected.width,decoded.width);assertEquals(expected.height,decoded.height)
                        val expectedPixels=IntArray(expected.width*expected.height)
                        val actualPixels=IntArray(decoded.width*decoded.height)
                        expected.getPixels(expectedPixels,0,expected.width,0,0,expected.width,expected.height)
                        decoded.getPixels(actualPixels,0,decoded.width,0,0,decoded.width,decoded.height)
                        assertArrayEquals("Normalized image pixels and EXIF orientation",expectedPixels,actualPixels)
                    } finally {decoded.recycle();expected.recycle();if(expected!==raw) raw.recycle()}

                    assertFalse(body.contains("content://"));assertFalse(body.contains(name))
                    assertEquals(1,server.requests.get());assertEquals(1,sent!!.messages.first().attachments.size)
                    if(history) showHistory(base,server,1,"history-image.png")
                    if(restartStage!=null) {
                        val profile=com.example.aiuseagent.data.ConnectionProfileCodec.encode(requireNotNull(container.settingsRepository.savedConnectionProfile()))
                        if(restartStage=="seed") restartMarker.writeText(buildJsonObject {
                            put("pid",android.os.Process.myPid());put("profile",profile)
                            val row=requireNotNull(db.attachmentObjectDao().attachment(sent.messages.first().attachments.single().id))
                            put("sessionId",row.sessionId);put("messageId",row.messageId);put("attachmentId",row.id)
                            put("localToken",row.localToken);put("sourceSha256",row.sha256)
                        }.toString())
                        val job=requireNotNull(InstrumentationRegistry.getArguments().getString("bridgeJobId"))
                        require(runCatching {UUID.fromString(job)}.isSuccess)
                        val evidence=File(base.getExternalFilesDir(null),"bridge-evidence/$job").apply {check(mkdirs() || isDirectory)}
                        File(evidence,"restart-$restartStage.json").writeText(buildJsonObject {
                            put("stage",restartStage);put("component",MainActivity::class.java.name);put("fixtureId",id)
                            put("pid",android.os.Process.myPid());seedPid?.let {put("previousPid",it)}
                            put("credentialRestored",restartStage=="restore");put("credentialPersistenceExpected","ENCRYPTED_LOCAL")
                            put("historicPreviewWithoutCredential",false)
                            put("loopbackRequests",server.requests.get());put("transportAttempts",server.attempts.get())
                            put("realServiceRequests",0);put("profileSha256",java.security.MessageDigest.getInstance("SHA-256").digest(profile.toByteArray()).joinToString("") {"%02x".format(it)})
                        }.toString())
                    }

                }
            } finally {
                if(!deleted) resolver.delete(uri,null,null)
                instrumentation.runOnMainSync {container.viewModelStore.clear()}
                scope.coroutineContext[Job]!!.cancelAndJoin();db.close()
                ApplicationContainerFactory.instrumentationContainer=null
            }
        }
    }
    private fun showHistory(base:Context,server:AttachmentLoopbackServer,expectedRequests:Int,screenshot:String) {
        click(By.desc("查看已发送附件"))
        assertTrue(device.wait(Until.hasObject(By.text("已发送附件")),5000))
        assertTrue(device.wait(Until.hasObject(By.desc("已发送图片")),5000))
        val shown=requireNotNull(device.findObject(By.desc("已发送图片"))).visibleBounds
        assertTrue("Tiny images still need a usable preview area",shown.height()>=64*base.resources.displayMetrics.density)
        assertEquals(expectedRequests,server.requests.get())
        val job=requireNotNull(InstrumentationRegistry.getArguments().getString("bridgeJobId"))
        require(runCatching {UUID.fromString(job)}.isSuccess)
        val evidence=File(base.getExternalFilesDir(null),"bridge-evidence/$job").apply {check(mkdirs() || isDirectory)}
        check(device.takeScreenshot(File(evidence,screenshot)))
        click(By.text("关闭"))
        assertTrue(device.wait(Until.gone(By.text("已发送附件")),5000))
        assertEquals(expectedRequests,server.requests.get())
    }
    internal open class SyntheticContext(base:Context,val id:String):ContextWrapper(base) {
        init {require(runCatching {UUID.fromString(id)}.isSuccess)}
        private val owned=File(base.filesDir.canonicalFile,"main-attachment-tests/$id").apply {check(mkdirs() || isDirectory);check(canonicalFile==absoluteFile)}
        override fun getApplicationContext():Context=this
        override fun getFilesDir():File=owned
        // Camera FileProvider deliberately retains its production, narrowly scoped cache path.
        override fun getSharedPreferences(name:String,mode:Int)=super.getSharedPreferences("main-attachment-$id-$name",mode)
        override fun getDatabasePath(name:String)=super.getDatabasePath("main-attachment-$id-$name")
    }
    private class NoNetwork:HttpTransport {
        var requests=0
        override suspend fun open(request:ProviderHttpRequest,timeouts:HttpTimeouts):StreamingHttpResponse {
            requests++;error("MAIN_ACTIVITY_TEST_NETWORK_FORBIDDEN")
        }
    }
    @Test fun actualMainActivityFileSelectionAndCancellationNeverSendBeforeUserSend()=runBlocking {
        val base=instrumentation.targetContext
        val id=UUID.randomUUID().toString()
        val context=SyntheticContext(base,id)
        val db=Room.databaseBuilder(base,AppDatabase::class.java,"main-attachment-$id.db").build()
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
        val transport=NoNetwork()
        lateinit var container:AppContainer
        instrumentation.runOnMainSync {
            container=AppContainer(context,databaseFactory={db},externalScope=scope,transport=transport)
            ApplicationContainerFactory.instrumentationContainer=container
        }
        val resolver=base.contentResolver
        val name="AAUA_MAIN_$id.txt"
        val uri=requireNotNull(resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"text/plain")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/")
        }))
        var deleted=false
        try {
            resolver.openOutputStream(uri)!!.use {it.write("main activity synthetic attachment".toByteArray())}
            device.wakeUp()
            ActivityScenario.launch<MainActivity>(Intent(base,MainActivity::class.java)).use {scenario ->
                assertTrue(device.wait(Until.hasObject(By.desc("添加附件")),15000))
                lateinit var vm:ChatViewModel
                scenario.onActivity {
                    assertEquals(MainActivity::class.java.name,it.componentName.className)
                    assertSame(container,(it.application as AgentApplication).container)
                    vm=ViewModelProvider(container)[ChatViewModel::class.java]
                }
                withTimeout(15000) {vm.uiState.first {it.isReady}}
                click(By.desc("添加附件"));click(By.text("文件"))
                val roots=device.wait(Until.findObject(By.desc("Show roots")),3000) ?: device.findObject(By.desc("显示根目录"))
                requireNotNull(roots).click()
                click(By.pkg("com.google.android.documentsui").text(java.util.regex.Pattern.compile("Downloads|下载")))
                click(By.text(name))
                val ready=withTimeout(15000) {vm.uiState.first {it.attachments.singleOrNull()?.state in setOf(AttachmentState.READY,AttachmentState.FAILED)}}
                assertEquals(ready.attachments.single().error,AttachmentState.READY,ready.attachments.single().state)
                assertEquals(name,ready.attachments.single().name)
                assertEquals(0,transport.requests);assertTrue(ready.messages.isEmpty())
                assertEquals(1,resolver.delete(uri,null,null));deleted=true
                click(By.text("预览"))
                assertTrue(device.wait(Until.hasObject(By.textContains("main activity synthetic attachment")),5000))
                click(By.text("关闭"));click(By.text("移除"))
                withTimeout(5000) {vm.uiState.first {it.attachments.isEmpty()}}
                for(entry in listOf("文件","照片")) {
                    click(By.desc("添加附件"));click(By.text(entry))
                    assertTrue(device.wait(Until.gone(By.pkg(base.packageName)),5000))
                    device.pressBack()
                    assertTrue(device.wait(Until.hasObject(By.desc("添加附件")),5000))
                    assertTrue(vm.uiState.value.attachments.isEmpty());assertTrue(vm.uiState.value.messages.isEmpty())
                }
                assertEquals(0,transport.requests)
            }
        } finally {
            if(!deleted) resolver.delete(uri,null,null)
            instrumentation.runOnMainSync {container.viewModelStore.clear()}
            scope.coroutineContext[Job]!!.cancelAndJoin()
            db.close()
            ApplicationContainerFactory.instrumentationContainer=null
            // Keep this UUID-owned evidence locally; never clear the real app database or preferences.
        }
    }
}
