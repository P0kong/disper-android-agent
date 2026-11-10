package com.example.aiuseagent.testing

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.example.aiuseagent.domain.attachment.*
import com.example.aiuseagent.ui.chat.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** Non-exported production ChatScreen host with no database, credentials or private history. */
class SyntheticAttachmentActivity:ComponentActivity() {
    lateinit var jobId:String
        private set
    var state by mutableStateOf(ChatUiState(isReady=true))
    var selected:AttachmentPickerKind?=null
    var simulateMissingPicker by mutableStateOf(false)
    var sends=0
    var imeObserved=false
    var pipelineViewModel:ChatViewModel? by mutableStateOf(null)
        private set
    private var fixtureDatabase:com.example.aiuseagent.data.local.AppDatabase?=null
    private val picker by lazy {com.example.aiuseagent.platform.AttachmentPickerController(this)}
    // 仅 debug 合成宿主：把测试用 ViewModel 放进本 Activity 的 ViewModelStore，
    // 使用 lifecycle 的内部 put 是刻意为之；release 变体不包含本文件。
    @SuppressLint("RestrictedApi")
    fun startPipeline() {
        check(pipelineViewModel==null)
        val db=androidx.room.Room.inMemoryDatabaseBuilder(this,com.example.aiuseagent.data.local.AppDatabase::class.java).build()
        fixtureDatabase=db
        val sessions=com.example.aiuseagent.data.SessionRepository(db.sessionDao(),db.messageDao(),com.example.aiuseagent.data.RoomGenerationPersistence(db))
        val providers=com.example.aiuseagent.data.provider.ProviderRepository(com.example.aiuseagent.domain.provider.ProviderRegistry(mapOf(
            com.example.aiuseagent.domain.provider.ProviderType.MOCK to com.example.aiuseagent.data.provider.mock.MockProvider())))
        val engine=com.example.aiuseagent.data.generation.GenerationCoordinator(sessions,providers,lifecycleScope,{_,_,_,_->error("SYNTHETIC_NO_WORKSPACE")},{false})
        val vm=ChatViewModel(sessions,coordinator=engine,providers=providers,attachmentRepositoryFactory={ current ->
            com.example.aiuseagent.data.attachment.AttachmentRepository(java.io.File(cacheDir.canonicalFile,"attachment-fixtures/$jobId").toPath(),
                com.example.aiuseagent.platform.AndroidAttachmentSource(contentResolver),current)
        })
        viewModelStore.put("synthetic-attachment-pipeline",vm)
        pipelineViewModel=vm
        lifecycleScope.launch {vm.uiState.collect {state=it}}
    }
    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        jobId=requireNotNull(intent.getStringExtra("bridgeJobId"))
        require(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(jobId))
        setTurnScreenOn(true);window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val scale=intent.getFloatExtra("fontScale",1f).also {require(it==1f || it==1.5f)}
        setContent {
          PickerRegistry {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale)) {
                MaterialTheme {
                    val launchPicker=pipelineViewModel?.let {rememberAttachmentPicker(it,picker)}
                    val ime=WindowInsets.isImeVisible
                    SideEffect {if(ime) imeObserved=true}
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Text("合成附件 · $jobId",maxLines=1)
                        Text(if(ime) "合成键盘已显示" else "合成键盘未显示")
                        ChatScreen(state,{text->pipelineViewModel?.onInputChange(text) ?: run {state=state.copy(input=text)}},
                            {pipelineViewModel?.send() ?: run {sends++}},{},{},{},{},{},{},{},{},
                            onAddAttachment={kind->if(launchPicker!=null) launchPicker(kind) else selected=kind},
                            onRemoveAttachment={id->pipelineViewModel?.removeAttachment(id) ?: run {state=state.copy(attachments=state.attachments.filterNot {it.id==id})}})
                    }
                }
            }
          }
        }
    }
    @Composable private fun PickerRegistry(content:@Composable ()->Unit) {
        if(!simulateMissingPicker) {content();return}
        val owner=remember {object:androidx.activity.result.ActivityResultRegistryOwner {
            override val activityResultRegistry=object:androidx.activity.result.ActivityResultRegistry() {
                override fun <I,O> onLaunch(requestCode:Int,contract:androidx.activity.result.contract.ActivityResultContract<I,O>,input:I,options:androidx.core.app.ActivityOptionsCompat?) {
                    throw android.content.ActivityNotFoundException("SYNTHETIC_PICKER_UNAVAILABLE")
                }
            }
        }}
        CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner,content=content)
    }
    override fun onDestroy() {
        super.onDestroy()
        fixtureDatabase?.close()
    }
    fun cards(count:Int) {
        require(count in 0..10)
        val scope=AttachmentScope(jobId,null,"CHAT",0,null,0,null,0,0)
        state=state.copy(attachments=(1..count).map {index ->
            val id=java.util.UUID.randomUUID().toString()
            DraftAttachment(id,scope,"合成附件$index.txt",AttachmentState.READY,
                AttachmentHandle(id,java.util.UUID.randomUUID().toString(),"a".repeat(64),"text/plain",3))
        })
    }
}
