package com.example.aiuseagent.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.aiuseagent.AppContainer
import com.example.aiuseagent.R
import com.example.aiuseagent.data.workspace.textMimeType
import com.example.aiuseagent.ui.capabilities.CapabilitiesScreen
import com.example.aiuseagent.ui.chat.*
import com.example.aiuseagent.ui.diagnostics.DiagnosticsScreen
import com.example.aiuseagent.ui.diagnostics.DiagnosticsViewModel
import com.example.aiuseagent.ui.settings.*
import com.example.aiuseagent.ui.skills.SkillsScreen
import com.example.aiuseagent.ui.theme.LocalDisper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.example.aiuseagent.ui.i18n.tr

enum class AppDestination(
    @StringRes val labelRes: Int,
    private val labelZh: String,
    private val labelEn: String,
) {
    CHAT(R.string.nav_chat, "对话", "Chat"),
    SETTINGS(R.string.nav_settings, "设置", "Settings"),
    MODEL(R.string.nav_model, "模型配置", "Model"),
    GENERAL(R.string.nav_general, "通用设置", "General"),
    TOOLS(R.string.nav_tools, "工具", "Tools"),
    ABOUT(R.string.nav_about, "关于", "About"),
    FOLDER(R.string.nav_folder, "工作文件夹", "Workspace"),
    SKILLS(R.string.nav_skills, "Skills", "Skills"),
    MCP(R.string.nav_mcp, "MCP", "MCP"),
    CAPABILITIES(R.string.nav_capabilities, "权限与能力", "Permissions & capabilities"),
    DIAGNOSTICS(R.string.nav_diagnostics, "诊断", "Diagnostics"),
    ;
    fun title(): String = tr(labelZh, labelEn)
}

/** 进程内导航：主题重建保留页面；冷进程默认回到主界面，不保存任何表单或草稿。 */
class AppNavigationViewModel : ViewModel() {
    private val pages = MutableStateFlow(listOf(AppDestination.CHAT))
    val stack = pages.asStateFlow()
    fun open(destination: AppDestination) {
        if (pages.value.last() != destination) pages.value = pages.value + destination
    }
    fun back() { if (pages.value.size > 1) pages.value = pages.value.dropLast(1) }
}

private data class WorkspacePickerTarget(val sessionId: Long?, val workspaceId: String, val selectionVersion: Long) {
    fun matches(chat: ChatViewModel) = chat.uiState.value.activeSessionId == sessionId &&
        chat.uiState.value.workspaceId == workspaceId && chat.selectionVersion() == selectionVersion
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(container: AppContainer) {
    val languagePreference by container.settingsRepository.language.collectAsStateWithLifecycle()
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val systemEnglish = configuration.locales[0]?.language?.startsWith("en") == true
    com.example.aiuseagent.ui.i18n.AppLanguage.apply(languagePreference, systemEnglish)
    val english = com.example.aiuseagent.ui.i18n.AppLanguage.english
    val chatFactory = remember { viewModelFactory { initializer { ChatViewModel(container.sessionRepository, container.settingsRepository,
        container.generationCoordinator, container.workspaceService, container.providerRepository,container::createAttachmentRepository,
        container.historyAttachmentReader::open) } } }
    val chat: ChatViewModel = viewModel(viewModelStoreOwner = container, factory = chatFactory)
    val addAttachment=rememberAttachmentPicker(chat,container.attachmentPickerController)
    val settingsFactory = remember { viewModelFactory { initializer { SettingsViewModel(container.settingsRepository, container.providerRepository, container.generationCoordinator) } } }
    val settings: SettingsViewModel = viewModel(viewModelStoreOwner = container, factory = settingsFactory)
    val diagnosticFactory = remember { viewModelFactory { initializer { DiagnosticsViewModel(container.statusInspector, container.databasePath()) } } }
    val diagnostics: DiagnosticsViewModel = viewModel(viewModelStoreOwner = container, factory = diagnosticFactory)
    val folderFactory = remember { viewModelFactory { initializer { WorkspaceViewModel(container.workspaceService) } } }
    val folder: WorkspaceViewModel = viewModel(viewModelStoreOwner = container, factory = folderFactory)
    val updateFactory = remember { viewModelFactory { initializer {
        com.example.aiuseagent.ui.update.UpdateViewModel(
            repository = container.updateRepository,
            dismissedTag = container.settingsRepository::dismissedUpdateTag,
            onDismissTag = container.settingsRepository::setDismissedUpdateTag,
        )
    } } }
    val updateViewModel: com.example.aiuseagent.ui.update.UpdateViewModel =
        viewModel(viewModelStoreOwner = container, factory = updateFactory)
    LaunchedEffect(Unit) { updateViewModel.autoCheck() }
    val navigation: AppNavigationViewModel = viewModel(viewModelStoreOwner = container, factory = remember { viewModelFactory { initializer { AppNavigationViewModel() } } })
    val stack by navigation.stack.collectAsStateWithLifecycle()
    val destination = stack.last()
    val chatState by chat.uiState.collectAsStateWithLifecycle()
    val folderState by folder.state.collectAsStateWithLifecycle()
    val folderBusy by folder.busy.collectAsStateWithLifecycle()
    val generation by container.generationCoordinator.state.collectAsStateWithLifecycle()
    val permissions by container.permissions.state.collectAsStateWithLifecycle()
    val approvals by container.approvals.pending.collectAsStateWithLifecycle()
    val permission = permissions[chatState.activeSessionId]?.level ?: com.example.aiuseagent.data.execution.PermissionLevel.VIEW_ONLY
    var permissionTarget by remember { mutableStateOf<Long?>(null) }
    var recoveryTarget by remember { mutableStateOf<Long?>(null) }
    var recoveryRows by remember { mutableStateOf<List<com.example.aiuseagent.data.execution.OperationRecoveryReview>?>(null) }
    var recoveryFailed by remember { mutableStateOf(false) }
    LaunchedEffect(recoveryTarget,chatState.activeSessionId,chatState.workspaceId,chatState.mode) {
        val target=recoveryTarget
        recoveryRows=null;recoveryFailed=false
        if(target==null)return@LaunchedEffect
        if(target!=chatState.activeSessionId || chatState.mode!=ChatMode.BUILD) {recoveryTarget=null;return@LaunchedEffect}
        try { recoveryRows=withContext(Dispatchers.IO) {container.operationRecoveryInspector.review(target)} }
        catch(cancelled:kotlinx.coroutines.CancellationException) {throw cancelled}
        catch(_:Exception) {recoveryFailed=true}
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var treeTarget by remember { mutableStateOf<WorkspacePickerTarget?>(null) }
    var importTarget by remember { mutableStateOf<WorkspacePickerTarget?>(null) }
    var exportTarget by remember { mutableStateOf<Pair<WorkspacePickerTarget, String>?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val target = treeTarget
            val uri = result.data?.data?.toString()
            if (uri != null && target != null) folder.register(uri, result.data?.flags ?: 0) { id ->
                if (target.matches(chat)) chat.selectWorkspace(id)
            }
        }
        treeTarget = null
    }
    val chooseFolder: () -> Unit = {
        scope.launch {
        if (!container.generationCoordinator.stopAndJoin(tr("更换工作区，已停止", "Workspace changed, stopped"))) return@launch
        treeTarget = WorkspacePickerTarget(chat.uiState.value.activeSessionId, chat.uiState.value.workspaceId, chat.selectionVersion())
        picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        })
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = importTarget
        importTarget = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && target != null && uri != null &&
            target.matches(chat)) {
            scope.launch {
                val name = withContext(Dispatchers.IO) { container.selectedDocumentName(uri) }
                if (name != null && target.matches(chat) &&
                    container.generationCoordinator.stopAndJoin(tr("导入文本，已停止", "Text imported, stopped")) && target.matches(chat)) {
                    folder.importText(target.workspaceId, uri.toString(), name)
                } else chat.reportWorkspaceIssue()
            }
        }
    }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = exportTarget
        exportTarget = null
        val uri = result.data?.data
        if (result.resultCode == Activity.RESULT_OK && target != null && uri != null &&
            target.first.matches(chat)) {
            scope.launch {
                if (container.generationCoordinator.stopAndJoin(tr("导出文本，已停止", "Text exported, stopped")) && target.first.matches(chat)) {
                    folder.exportText(target.first.workspaceId, target.second, uri.toString())
                } else chat.reportWorkspaceIssue()
            }
        }
    }
    val importText: () -> Unit = {
        scope.launch {
            if (!container.generationCoordinator.stopAndJoin(tr("导入文本，已停止", "Text imported, stopped"))) return@launch
            importTarget = WorkspacePickerTarget(chat.uiState.value.activeSessionId, chat.uiState.value.workspaceId, chat.selectionVersion())
            importer.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/plain", "text/markdown", "text/x-markdown"))
            })
        }
    }
    val exportText: (String, String) -> Unit = { path, name ->
        scope.launch {
            if (container.providerRepository.containsKnownCredential(path) || container.providerRepository.containsKnownCredential(name)) {
                chat.reportWorkspaceIssue(); return@launch
            }
            val mimeType = runCatching { textMimeType(name) }.getOrElse {
                chat.reportWorkspaceIssue(); return@launch
            }
            if (!container.generationCoordinator.stopAndJoin(tr("导出文本，已停止", "Text exported, stopped"))) return@launch
            exportTarget = WorkspacePickerTarget(chat.uiState.value.activeSessionId, chat.uiState.value.workspaceId, chat.selectionVersion()) to path
            exporter.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                type = mimeType; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_TITLE, name)
            })
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { folder.refresh(); chat.refreshModelLabel() }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { container.approvals.invalidateAll(); permissionTarget=null }
    LaunchedEffect(chatState.activeSessionId,chatState.mode) {
        if(permissionTarget!=chatState.activeSessionId || chatState.mode!=ChatMode.BUILD) permissionTarget=null
        if(chatState.mode==ChatMode.BUILD) chatState.activeSessionId?.let { container.permissions.snapshot(it) }
    }
    LaunchedEffect(destination) { chat.refreshModelLabel(); chat.refreshWorkspaceUi() }
    BackHandler(enabled = destination != AppDestination.CHAT) { navigation.back() }
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    key(english) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = destination == AppDestination.CHAT,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = LocalDisper.current.surface,
                drawerContentColor = LocalDisper.current.ink,
            ) {
                SessionDrawer(chatState,
                    onNewSession = { chat.newSession(); scope.launch { drawerState.close() } },
                    onSelect = { chat.selectSession(it); scope.launch { drawerState.close() } },
                    onRequestRename = { chat.requestRename(it); scope.launch { drawerState.close() } },
                    onRequestDelete = { chat.requestDelete(it); scope.launch { drawerState.close() } },
                )
            }
        },
    ) {
        Scaffold(
            containerColor = LocalDisper.current.canvas,
            topBar = {
                DisperHeader(
                    destination = destination,
                    mode = chatState.mode,
                    onMode = chat::setMode,
                    busy = generation.busy,
                    onMenu = { scope.launch { drawerState.open() } },
                    onBack = navigation::back,
                    onSettings = { navigation.open(AppDestination.SETTINGS) },
                    onStop = chat::stopGeneration,
                    onRefresh = diagnostics::refresh,
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                when (destination) {
                    AppDestination.CHAT -> ChatScreen(
                        state = chatState, onInputChange = chat::onInputChange, onSend = chat::send,
                        onDismissNotice = chat::dismissNotice, onCancelDelete = chat::cancelDelete,
                        onConfirmDelete = chat::confirmDelete, onRenameDraftChange = chat::updateRenameDraft,
                        onCancelRename = chat::cancelRename, onConfirmRename = chat::confirmRename,
                        onModel = { navigation.open(AppDestination.MODEL) },
                        onFolder = { navigation.open(AppDestination.FOLDER) },
                        onStop = chat::stopGeneration, onRetry = chat::retryGeneration,
                        onAddAttachment=addAttachment,onRemoveAttachment=chat::removeAttachment,onPreviewAttachment=chat::previewAttachment,
                        onDismissAttachmentPreview=chat::dismissAttachmentPreview,onConfirmAttachmentPreview=chat::confirmAttachmentPreview,
                        onPreviewHistoryAttachment=chat::previewHistoryAttachment,
                        onDismissHistoryAttachmentPreview=chat::dismissHistoryAttachmentPreview,
                        permission = permission,
                        onReviewOperations={recoveryTarget=chat.uiState.value.activeSessionId},
                        onPermission = { scope.launch {
                            try { permissionTarget=chat.permissionSession() } catch(_: Exception) { chat.reportWorkspaceIssue() }
                        } },
                    )
                    AppDestination.SETTINGS -> SettingsHome(onOpen = navigation::open)
                    AppDestination.MODEL -> SettingsScreen(settingsViewModel = settings)
                    AppDestination.GENERAL -> GeneralSettingsScreen(settings)
                    AppDestination.TOOLS -> ToolsScreen(chatState.workspaceName, navigation::open)
                    AppDestination.ABOUT -> AboutScreen(stringResource(R.string.app_name), container.appVersion(), updateViewModel, navigation::open)
                    AppDestination.FOLDER -> WorkspaceScreen(folderState, chatState.workspaceId,
                        chat::selectWorkspace, chooseFolder,
                        onRemove = { id, replacement -> scope.launch {
                            if (container.generationCoordinator.stopAndJoin(tr("移除工作区，已停止", "Workspace removed, stopped"))) {
                                folder.remove(id, replacement) {
                                    if (chat.uiState.value.workspaceId == id) chat.selectWorkspace(replacement ?: "default")
                                }
                            }
                        } },
                        onImport = importText, onExport = exportText, isBusy = folderBusy || generation.busy)
                    AppDestination.SKILLS -> SkillsScreen()
                    AppDestination.MCP -> McpScreen()
                    AppDestination.CAPABILITIES -> CapabilitiesScreen(inspector = container.statusInspector)
                    AppDestination.DIAGNOSTICS -> DiagnosticsScreen(diagnosticsViewModel = diagnostics)
                }
            }
        }
    }
    }
    com.example.aiuseagent.ui.update.UpdateReminderHost(updateViewModel)
    permissionTarget?.takeIf { it==chatState.activeSessionId && chatState.mode==ChatMode.BUILD }?.let { target ->
        PermissionDialog(permission,onSelect={ level,confirmed -> scope.launch {
            try {
                if(container.generationCoordinator.stopAndJoin(tr("权限变化，已停止", "Permission changed, stopped")) &&
                    chat.uiState.value.activeSessionId==target && chat.uiState.value.mode==ChatMode.BUILD) {
                    container.permissions.select(target,level,confirmed)
                }
            } catch(_: Exception) { chat.reportWorkspaceIssue() }
            permissionTarget=null
        } },onDismiss={permissionTarget=null})
    }
    if(recoveryTarget!=null && recoveryTarget==chatState.activeSessionId && chatState.mode==ChatMode.BUILD) {
        OperationRecoveryDialog(recoveryRows,recoveryFailed,onDismiss={recoveryTarget=null})
    }
    approvals.firstOrNull()?.let { pending ->
        OperationApprovalDialog(pending) { id,allowed -> container.approvals.resolveFromUser(id,allowed) }
    }
}

/**
 * 顶部栏：单行、发丝线分隔，浅色为白底、深色为近黑底。
 * 结构与原 TopAppBar 一致（左菜单/返回，中间模式切换或标题，右侧设置/刷新），
 * 仅把 Material 默认外观换成品牌外观，按钮位置不变。
 */
@Composable
private fun DisperHeader(
    destination: AppDestination,
    mode: ChatMode,
    onMode: (ChatMode) -> Unit,
    busy: Boolean,
    onMenu: () -> Unit,
    onBack: () -> Unit,
    onSettings: () -> Unit,
    onStop: () -> Unit,
    onRefresh: () -> Unit,
) {
    val palette = LocalDisper.current
    Column(Modifier.fillMaxWidth().background(palette.canvas)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().height(56.dp).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = if (destination == AppDestination.CHAT) onMenu else onBack) {
                Icon(
                    if (destination == AppDestination.CHAT) Icons.Default.Menu else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = if (destination == AppDestination.CHAT) tr("会话记录", "Session history") else tr("返回", "Back"),
                    tint = palette.ink,
                )
            }
            if (destination == AppDestination.CHAT) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    ModeSwitch(mode, onMode, Modifier.width(240.dp))
                }
            } else {
                Text(
                    destination.title(),
                    style = MaterialTheme.typography.titleMedium,
                    color = palette.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
            }
            if (busy && destination != AppDestination.CHAT) {
                TextButton(onClick = onStop) { Text(tr("停止", "Stop"), color = palette.accent) }
            }
            if (destination == AppDestination.CHAT) {
                IconButton(onClick = onSettings) {
                    Icon(Icons.Default.Settings, contentDescription = tr("设置", "Settings"), tint = palette.ink)
                }
            }
            if (destination == AppDestination.DIAGNOSTICS) {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = tr("重新检测", "Recheck"), tint = palette.ink)
                }
            }
        }
        HorizontalDivider(thickness = 1.dp, color = palette.hairline)
    }
}

/** 交流 / 构建切换：底槽 + 选中态浮起，替代原 FilterChip 组，占位与交互不变。 */
@Composable
private fun ModeSwitch(mode: ChatMode, onMode: (ChatMode) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalDisper.current
    Surface(modifier = modifier, color = palette.canvasAlt, shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(3.dp)) {
            ChatMode.entries.forEach { candidate ->
                val selected = candidate == mode
                Surface(
                    onClick = { onMode(candidate) },
                    modifier = Modifier.weight(1f),
                    color = if (selected) palette.raised else Color.Transparent,
                    contentColor = if (selected) palette.ink else palette.inkMuted,
                    shape = RoundedCornerShape(8.dp),
                    border = if (selected) BorderStroke(1.dp, palette.hairline) else null,
                ) {
                    Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                        Text(candidate.label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    }
                }
            }
        }
    }
}
