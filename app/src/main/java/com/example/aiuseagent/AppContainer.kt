package com.example.aiuseagent

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import com.example.aiuseagent.data.SessionRepository
import com.example.aiuseagent.data.RoomGenerationPersistence
import com.example.aiuseagent.data.generation.GenerationCoordinator
import com.example.aiuseagent.data.generation.GenerationLimits
import com.example.aiuseagent.data.provider.HttpProviders
import com.example.aiuseagent.data.provider.http.ProviderDiagnostics
import com.example.aiuseagent.data.provider.http.ProviderLogRecord
import com.example.aiuseagent.data.workspace.WorkspaceService
import com.example.aiuseagent.data.workspace.WorkspaceStatus
import com.example.aiuseagent.data.execution.*
import com.example.aiuseagent.data.SessionContextPolicy
import com.example.aiuseagent.data.workspace.WorkspaceToolResult
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderType
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import com.example.aiuseagent.data.SettingsRepository
import com.example.aiuseagent.data.WorkFolderManager
import com.example.aiuseagent.platform.AndroidDirectoryAccess
import com.example.aiuseagent.platform.PreferenceWorkFolderStore
import com.example.aiuseagent.data.local.AppDatabase
import com.example.aiuseagent.platform.AndroidStatusInspector

/**
 * 极简手工依赖容器（刻意不引入 Hilt 等 DI 框架）。
 * 唯一进程级装配点；UI只读状态与发送意图，不持有HTTP或DAO。
 */
class AppContainer(context: Context,
    databaseFactory:(Context)->AppDatabase={AppDatabase.get(it)},
    externalScope:CoroutineScope?=null,
    transport:com.example.aiuseagent.data.provider.http.HttpTransport=com.example.aiuseagent.data.provider.http.OkHttpTransport(),
) : ViewModelStoreOwner {

    // 仅持有应用上下文的状态模型：进程内重开 Activity 也不重复执行启动选择。
    // 模型表单、草稿、当前模式只随进程生存，不进入 SavedState 或磁盘。
    override val viewModelStore = ViewModelStore()

    private val appContext: Context = context.applicationContext
    private val applicationScope = externalScope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val database: AppDatabase = databaseFactory(appContext)
    private val attachmentRoot=java.io.File(appContext.filesDir.canonicalFile,"attachments").toPath()
    private val attachmentLeases=com.example.aiuseagent.data.attachment.AttachmentLeaseRegistry()
    private val attachmentStore=com.example.aiuseagent.data.attachment.OwnedAttachmentStore(attachmentRoot,attachmentLeases)
    private val attachmentCatalog=com.example.aiuseagent.data.attachment.RoomAttachmentCatalog(database)
    private val legacyHistoryMigrator=com.example.aiuseagent.data.attachment.LegacyHistorySnapshotMigrator(
        attachmentStore,attachmentCatalog,attachmentCatalog::bindLegacy,onUnused=::requestAttachmentCleanup)
    val historyAttachmentReader=com.example.aiuseagent.data.attachment.HistoryAttachmentReader(attachmentCatalog,attachmentStore,
        onUnused=::requestAttachmentCleanup,legacyUpgrade=legacyHistoryMigrator::upgrade)
    private val attachmentCleanup=com.example.aiuseagent.data.attachment.AttachmentGarbageCollector(attachmentCatalog,attachmentStore)
    private val attachmentCleanupMutex=kotlinx.coroutines.sync.Mutex()
    private suspend fun collectUnusedAttachments() {
        try {attachmentCleanupMutex.withLock {
            val result=attachmentCleanup.collectPending()
            if(result.deleted>0 || result.review>0) Log.i("AttachmentCleanup","deleted=${result.deleted} retained=${result.retained} review=${result.review}")
        }} catch(cancelled:CancellationException) {throw cancelled}
        catch(_:Exception) {Log.w("AttachmentCleanup","local_cleanup_unavailable")}
    }
    private fun requestAttachmentCleanup() {applicationScope.launch(Dispatchers.IO) {collectUnusedAttachments()}}

    val sessionRepository: SessionRepository = SessionRepository(
        sessionDao = database.sessionDao(),
        messageDao = database.messageDao(),
        generationPersistence = RoomGenerationPersistence(database),
    ).apply {onAttachmentsUnused=::requestAttachmentCleanup}

    val settingsRepository: SettingsRepository = SettingsRepository(appContext)

    val providerRepository = HttpProviders.repository(
        transport = transport,
        credentialStore = com.example.aiuseagent.data.provider.KeystoreCredentialStore(appContext, "builtin"),
        credentialStoreFactory = { scope -> com.example.aiuseagent.data.provider.KeystoreCredentialStore(appContext, scope) },
        diagnostics = object : ProviderDiagnostics {
        override fun record(record: ProviderLogRecord) {
            // No headers, arbitrary details, request IDs, prompts or response bodies.
            if (record.event in setOf("request_attempt_started", "stream_completed", "stream_failed", "retry_scheduled")) {
                Log.i("ProviderAudit", "event=${record.event} provider=${record.providerType.name} attempt=${record.attempt} httpStatus=${record.httpStatus} error=${record.errorKind}")
            }
        }
    }).apply {
        settingsRepository.restoreProviderConfiguration(this)
    }

    val workspaceService = WorkspaceService(appContext, database, providerRepository::containsKnownCredential)

    /** 只读 GitHub Releases 检查；匿名请求，不携带任何凭据或用户数据。 */
    val updateRepository = com.example.aiuseagent.data.update.UpdateRepository(
        versionName = {
            runCatching {
                appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
            }.getOrNull() ?: "0.0.0"
        },
        lastAutoCheckAt = settingsRepository::updateLastAutoCheckAt,
        markAutoChecked = settingsRepository::setUpdateLastAutoCheckAt,
    )
    val attachmentPickerController = com.example.aiuseagent.platform.AttachmentPickerController(appContext)
    fun createAttachmentRepository(isCurrent:(com.example.aiuseagent.domain.attachment.AttachmentScope)->Boolean) =
        com.example.aiuseagent.data.attachment.AttachmentRepository(
            attachmentRoot,com.example.aiuseagent.platform.AndroidAttachmentSource(appContext.contentResolver),isCurrent,
            ownedStore=attachmentStore,ownership=attachmentCatalog,onUnused=::requestAttachmentCleanup)
    val connectionProfiles = com.example.aiuseagent.data.ConnectionProfileRepository(database.connectionProfileDao())
    val approvals = ApprovalRepository()
    val permissions = PermissionController(
        load = { database.sessionDao().findById(it)?.permissionMode },
        save = { id,level -> check(database.sessionDao().updatePermission(id,level)==1) },
        invalidateApprovals = approvals::invalidateAll,
    )
    private val operationJournal=RoomOperationJournal(database.operationJournalDao())
    val executionBroker = ExecutionBroker(
        operationJournal,approvals,permissions::snapshot,
        scopeIsCurrent = { binding ->
            val s=binding.scope
            val row=database.sessionDao().findById(s.sessionId)
            val generation=database.generationDao().findById(binding.generationId)
            val profile=providerRepository.prepare()
            row!=null && SessionContextPolicy.matches(row,s) &&
                generation?.status=="STREAMING" && generation.sessionId==s.sessionId &&
                generation.mode==s.mode && generation.contextEpoch==s.contextEpoch &&
                generation.workspaceId==s.workspaceId && generation.workspaceVersion==s.workspaceVersion &&
                generation.profileId==s.profileId && generation.profileRevision==s.profileRevision &&
                profile.profileId==s.profileId && profile.profileRevision==s.profileRevision &&
                workspaceService.version(requireNotNull(s.workspaceId))==s.workspaceVersion
        },
        resolveFacts = { operation ->
            workspaceService.refresh()
            val workspace=workspaceService.state.value.workspaces.firstOrNull { it.id==operation.binding.scope.workspaceId }
            operation.facts.copy(androidGranted=workspace?.status==WorkspaceStatus.AVAILABLE,writable=workspace?.writable==true)
        },
    )

    private val privateFileStores=java.util.concurrent.ConcurrentHashMap<Long,TaskFileStore>()
    private fun privateFileStore(version:Long)=privateFileStores.computeIfAbsent(version) {
        val files=appContext.filesDir.canonicalFile.toPath()
        TaskFileStore(files.resolve("workspaces/default"),files.resolve("workspace-recovery/default"),version)
    }
    val operationRecoveryInspector=OperationRecoveryInspector(operationJournal::uncertain,
        currentScope={ id ->
            val row=database.sessionDao().findById(id)
            val workspace=row?.workspaceId
            val profile=row?.profileId
            if(row==null || row.mode!="BUILD" || workspace==null || profile.isNullOrBlank()) null
            else com.example.aiuseagent.data.SessionContextScope(id,row.mode,workspace,workspaceService.version(workspace),row.contextEpoch,profile,row.profileRevision)
        },
        inspect={ scope,token -> privateFileStore(scope.workspaceVersion).inspectRecovery(token) },
        containsSecret=providerRepository::containsKnownCredential,
        inspectDeletion={ scope,token -> privateFileStore(scope.workspaceVersion).inspectDeletion(token) },
    )
    private val mutationServices=java.util.concurrent.ConcurrentHashMap<Long,com.example.aiuseagent.data.workspace.WorkspaceMutationService>()
    val workspaceTools=WorkspaceToolDispatcher(executionBroker,workspaceService::execute,
        mutations = { scope ->
            check(scope.workspaceId==WorkspaceService.DEFAULT_ID)
            mutationServices.computeIfAbsent(scope.workspaceVersion) { version ->
                com.example.aiuseagent.data.workspace.WorkspaceMutationService(
                    privateFileStore(version),
                    providerRepository::containsKnownCredential)
            }
        },
        facts = { scope ->
            workspaceService.refresh()
            val workspace=workspaceService.state.value.workspaces.firstOrNull { it.id==scope.workspaceId }
            OperationFacts(Effect.READ,androidGranted=workspace?.status==WorkspaceStatus.AVAILABLE,writable=workspace?.writable==true)
        },
        // T05 Android recovery/fault gates are still pending. Do not advertise unverified mutations.
        mutationsVerified = false,
        containsSecret = providerRepository::containsKnownCredential,
    )

    val generationCoordinator = GenerationCoordinator(
        sessions = sessionRepository,
        providers = providerRepository,
        scope = applicationScope,
        toolExecutor = workspaceService::execute,
        workspaceAvailable = { id -> workspaceService.state.value.workspaces.any { it.id == id && it.status == WorkspaceStatus.AVAILABLE } },
        limits = if (appContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            GenerationLimits.acceptance() else GenerationLimits(),
        audit = { Log.i("GenerationAudit", it) },
        workspaceVersion = workspaceService::version,
        initialize = {
            executionBroker.recover()
            collectUnusedAttachments()
            connectionProfiles.restoreInto(providerRepository)
            connectionProfiles.save(providerRepository.prepare())
        },
        persistProfile = connectionProfiles::save,
        invalidateApprovals = approvals::invalidateAll,
        workspaceTools = workspaceTools::tools,
        scopedToolExecutor = { generationId,turn,call,budget ->
            workspaceTools.execute(generationId,turn.scope,call,budget)
        },
    )

    init { applicationScope.launch(Dispatchers.IO) { workspaceService.refresh() } }

    val workFolderManager = WorkFolderManager(
        PreferenceWorkFolderStore(appContext.getSharedPreferences(SettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)),
        AndroidDirectoryAccess(appContext.contentResolver),
    )

    val statusInspector: AndroidStatusInspector = AndroidStatusInspector(appContext)

    /** 数据库文件路径，用于诊断页展示（证明 Room 真的落地在磁盘上）。 */
    fun databasePath(): String = appContext.getDatabasePath(AppDatabase.NAME).absolutePath

    /** Only metadata for a user-selected single document; content access stays in WorkspaceService. */
    fun selectedDocumentName(uri: android.net.Uri): String? = runCatching {
        appContext.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    fun appVersion(): String {
        val info = appContext.packageManager.getPackageInfo(
            appContext.packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0),
        )
        return "${info.versionName} (${info.longVersionCode})"
    }
}
