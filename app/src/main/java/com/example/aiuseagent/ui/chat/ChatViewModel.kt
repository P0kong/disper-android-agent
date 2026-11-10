package com.example.aiuseagent.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiuseagent.domain.attachment.AttachmentScope
import com.example.aiuseagent.domain.attachment.AttachmentPickerKind
import com.example.aiuseagent.domain.attachment.PickerTicket
import com.example.aiuseagent.domain.attachment.*
import com.example.aiuseagent.data.attachment.AttachmentRepository
import com.example.aiuseagent.data.attachment.AttachmentRequestAssembler
import com.example.aiuseagent.data.attachment.AttachmentGenerationInput
import com.example.aiuseagent.data.attachment.AttachmentCommit
import com.example.aiuseagent.data.attachment.HistoryPreviewResult
import com.example.aiuseagent.data.attachment.parser.TextAttachmentParser
import com.example.aiuseagent.data.attachment.parser.ImageAttachmentProcessor
import com.example.aiuseagent.data.RenameResult
import com.example.aiuseagent.data.SessionRepository
import com.example.aiuseagent.data.ChatPreferences
import com.example.aiuseagent.data.MemoryChatPreferences
import com.example.aiuseagent.data.local.SessionEntity
import com.example.aiuseagent.data.local.MessageEntity
import com.example.aiuseagent.data.local.GenerationEntity
import com.example.aiuseagent.data.generation.GenerationCoordinator
import com.example.aiuseagent.data.generation.GenerationState
import com.example.aiuseagent.data.generation.GenerationSubmission
import com.example.aiuseagent.data.provider.ProviderRepository
import com.example.aiuseagent.data.workspace.WorkspaceService
import com.example.aiuseagent.data.workspace.WorkspaceStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import com.example.aiuseagent.ui.i18n.tr

/**
 * 会话与消息的状态持有者。所有数据都来自 [SessionRepository]（Room），
 * 没有任何硬编码的会话数组，也没有任何模型调用。
 */
class ChatViewModel(
    private val repository: SessionRepository,
    private val preferences: ChatPreferences = MemoryChatPreferences(),
    private val coordinator: GenerationCoordinator? = null,
    private val workspaces: WorkspaceService? = null,
    private val providers: ProviderRepository? = null,
    private val attachmentRepositoryFactory: (((AttachmentScope) -> Boolean) -> AttachmentRepository)? = null,
    private val historyReader:(suspend (Long,Long,String)->com.example.aiuseagent.data.attachment.HistoryPreviewResult)?=null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    @Volatile private var attachmentDraftId = java.util.UUID.randomUUID().toString()
    @Volatile private var attachmentSelectionRevision = 0L
    private val attachmentTickets = mutableMapOf<String, PickerTicket>()
    @Volatile private var attachmentWorkspaceVersion: Pair<String, Long>? = null
    private val attachmentRepository by lazy { attachmentRepositoryFactory?.invoke { it==attachmentScope() } }
    private val attachmentJobs = mutableMapOf<String,Job>()
    private val attachmentResults = mutableMapOf<String,AttachmentParseResult>()
    private var attachmentSendJob:Job?=null
    private var historyPreviewJob:Job?=null
    private var openedHistory:HistoryPreviewResult.Readable?=null
    private var historyPreviewAttempt=0L
    fun previewHistoryAttachment(messageId:Long,attachmentId:String) {
        val state=_uiState.value;val sessionId=state.activeSessionId ?: return
        val attachment=state.messages.firstOrNull {it.id==messageId && it.role==MessageRole.USER}
            ?.attachments?.firstOrNull {it.id==attachmentId} ?: return
        val reader=historyReader ?: return
        dismissHistoryAttachmentPreview()
        val attempt=historyPreviewAttempt;val bound=attachmentScope()
        fun current()=attempt==historyPreviewAttempt && bound==attachmentScope() &&
            _uiState.value.messages.any {it.id==messageId && it.attachments.any {item->item.id==attachmentId}}
        historyPreviewJob=viewModelScope.launch {
            var pending:HistoryPreviewResult.Readable?=null
            try {
                when(val result=reader(sessionId,messageId,attachmentId)) {
                    is HistoryPreviewResult.Readable->{
                        pending=result
                        if(!isActive || !current()) return@launch
                        openedHistory=result
                        _uiState.update {it.copy(historyAttachmentPreview=HistoryAttachmentPreview(attachmentId,attachment.name,result.snapshot,result::imageBytes))}
                        pending=null
                    }
                    else->if(current()) reportAttachmentIssue(tr("该附件的已发送内容不可用，请重新选择文件。", "The sent content for this attachment is unavailable. Please select the file again."))
                }
            } catch(cancelled:CancellationException) {throw cancelled}
            catch(_:Exception) {if(current()) reportAttachmentIssue(tr("该附件的已发送内容不可用，请重新选择文件。", "The sent content for this attachment is unavailable. Please select the file again."))}
            finally {pending?.close();if(attempt==historyPreviewAttempt) historyPreviewJob=null}
        }
    }
    fun dismissHistoryAttachmentPreview() {
        historyPreviewAttempt++
        historyPreviewJob?.cancel();historyPreviewJob=null
        openedHistory?.close();openedHistory=null
        _uiState.update {it.copy(historyAttachmentPreview=null)}
    }
    override fun onCleared() {dismissHistoryAttachmentPreview();super.onCleared()}

    fun attachmentScope(): AttachmentScope {
        val state = _uiState.value
        val session = state.sessions.firstOrNull { it.id == state.activeSessionId }
        val provider = providers?.prepare()
        val changed = session != null && provider != null &&
            (session.profileId != provider.profileId || session.profileRevision != provider.profileRevision)
        val workspace = state.workspaceId.takeIf { state.mode == ChatMode.BUILD }
        return AttachmentScope(attachmentDraftId, state.activeSessionId, modeKey(state.mode),
            if (changed) Math.addExact(session!!.contextEpoch, 1L) else session?.contextEpoch ?: 0L,
            workspace, attachmentWorkspaceVersion?.takeIf { it.first == workspace }?.second ?: 0L,
            provider?.profileId, provider?.profileRevision ?: 0L, attachmentSelectionRevision)
    }

    /** Production callers refresh the workspace version before opening a system picker. */
    suspend fun prepareAttachmentPick(kind: AttachmentPickerKind, outputUri:String?=null): PickerTicket? {
        val before = attachmentScope()
        val version = before.workspaceId?.let { workspaces?.version(it) } ?: 0L
        if (attachmentScope() != before) return null
        attachmentWorkspaceVersion = before.workspaceId?.let { it to version }
        return beginAttachmentPick(kind,outputUri)
    }

    fun beginAttachmentPick(kind: AttachmentPickerKind, outputUri:String?=null): PickerTicket {
        check(_uiState.value.isReady && !_uiState.value.viewingLegacy && !_uiState.value.isGenerating && !_uiState.value.preparingAttachments)
        // Only one platform picker may own a callback at a time.
        attachmentTickets.clear()
        return PickerTicket(java.util.UUID.randomUUID().toString(), attachmentScope(), kind,outputUri).also {
            attachmentTickets[it.requestId] = it
        }
    }

    fun acceptsAttachmentTicket(ticket: PickerTicket): Boolean =
        _uiState.value.isReady && !_uiState.value.viewingLegacy && !_uiState.value.isGenerating && !_uiState.value.preparingAttachments &&
            attachmentTickets[ticket.requestId] == ticket && ticket.scope == attachmentScope()

    private fun replaceAttachmentDraft() {
        dismissHistoryAttachmentPreview()
        attachmentSendJob?.cancel()
        val oldCards=attachmentRepository?.state?.value.orEmpty()
        attachmentJobs.values.forEach { it.cancel() }
        attachmentJobs.clear()
        attachmentResults.clear()
        attachmentDraftId = java.util.UUID.randomUUID().toString()
        attachmentSelectionRevision++
        attachmentTickets.clear()
        attachmentWorkspaceVersion = null
        _uiState.update { it.copy(attachments = emptyList(), attachmentSelections = emptyMap(), preparingAttachments = false,attachmentPreview=null) }
        viewModelScope.launch { oldCards.forEach { attachmentRepository?.remove(it.scope,it.id) } }
    }

    fun reportAttachmentIssue(message:String) { _uiState.update { it.copy(notice=message) } }

    fun acceptAttachmentResult(ticket:PickerTicket, uris:List<String>, onFinished:()->Unit={}) {
        val repo=attachmentRepository
        if (repo==null || !acceptsAttachmentTicket(ticket)) { onFinished();return }
        attachmentTickets.remove(ticket.requestId)
        if (uris.isEmpty()) { onFinished();return }
        if (uris.size+_uiState.value.attachments.size>10) {
            reportAttachmentIssue(tr("最多选择 10 个附件，请减少数量后重选。", "You can select up to 10 attachments. Reduce the number and try again."));onFinished();return
        }
        viewModelScope.launch {
            try {
                for(uri in uris) {
                    if(ticket.scope!=attachmentScope()) break
                    val id=java.util.UUID.randomUUID().toString()
                    val job=launch {
                        try {
                            val handle=repo.stage(ticket.scope,uri,id)
                            val result=kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { kotlinx.coroutines.withTimeout(AttachmentLimits().parseTimeoutMillis) {
                                val read:suspend (AttachmentHandle)->ByteArray={ repo.read(ticket.scope,it) }
                                val bytes=read(handle)
                                if(ticket.kind!=AttachmentPickerKind.FILE && !ImageAttachmentProcessor.isSupportedImage(bytes)) throw AttachmentFailure("IMAGE_FORMAT")
                                if (ImageAttachmentProcessor.isSupportedImage(bytes)) ImageAttachmentProcessor(read).parse(handle,AttachmentLimits())
                                else TextAttachmentParser(read).parse(handle,AttachmentLimits())
                            } }
                            repo.parsed(ticket.scope,handle,result)
                            if(ticket.scope==attachmentScope()) {
                                attachmentResults[id]=result
                                _uiState.update { it.copy(attachmentSelections=it.attachmentSelections+(id to
                                    AttachmentSelection(id,handle.sha256,result.parserRevision,result.parts.map { part->part.id },result.complete))) }
                            }
                        } catch(error:kotlinx.coroutines.TimeoutCancellationException) { repo.markFailed(ticket.scope,id,"PARSE_TIMEOUT") }
                        catch(error:CancellationException) { throw error }
                        catch(error:Exception) {
                            repo.markFailed(ticket.scope,id,(error as? AttachmentFailure)?.code ?: "PARSE_FAILED")
                            if(ticket.scope==attachmentScope()) reportAttachmentIssue(tr("附件无法读取或处理，请检查格式、大小和访问授权。", "The attachment can't be read or processed. Check its format, size, and access permission."))
                        }
                    }
                    attachmentJobs[id]=job
                    job.join()
                    attachmentJobs.remove(id)
                }
            } finally { onFinished() }
        }
    }

    fun removeAttachment(id:String) {
        if(_uiState.value.preparingAttachments) return
        val card=_uiState.value.attachments.firstOrNull { it.id==id } ?: return
        attachmentJobs.remove(id)?.cancel()
        attachmentResults.remove(id)
        _uiState.update { it.copy(attachmentSelections=it.attachmentSelections-id,attachmentPreview=it.attachmentPreview?.takeUnless { preview->preview.id==id }) }
        viewModelScope.launch { attachmentRepository?.remove(card.scope,id) }
    }

    fun previewAttachment(id:String) {
        val result=attachmentResults[id] ?: return
        val card=_uiState.value.attachments.firstOrNull { it.id==id } ?: return
        viewModelScope.launch {
            try {
                val image=result.parts.firstOrNull()?.image?.let { handle ->
                    ImageAttachmentProcessor { requireNotNull(attachmentRepository).read(card.scope,it) }.normalize(handle,AttachmentLimits(),1024).bytes
                }
                if(card.scope==attachmentScope() && _uiState.value.attachments.any { it.id==id }) _uiState.update { it.copy(attachmentPreview=
                    AttachmentPreview(id,card.name,result.parts.mapNotNull { part->part.text?.let { part.source+"\n"+it } }.joinToString("\n").take(8000).ifEmpty { null },image,!result.complete)) }
            } catch(error:CancellationException) { throw error }
            catch(_:Exception) { reportAttachmentIssue(tr("本地副本不可用，无法预览。", "Local copy unavailable; can't preview.")) }
        }
    }
    fun dismissAttachmentPreview() { _uiState.update { it.copy(attachmentPreview=null) } }
    fun confirmAttachmentPreview() {
        val preview=_uiState.value.attachmentPreview ?: return
        // Only the parser's bounded part IDs are confirmed; the original file is not implicitly selected.
        val selection=_uiState.value.attachmentSelections[preview.id] ?: return
        _uiState.update { it.copy(attachmentSelections=it.attachmentSelections+(preview.id to selection.copy(selectionConfirmed=true)),attachmentPreview=null) }
    }

    private var messagesJob: Job? = null
    private var messagesGeneration = 0L
    private var selectionGeneration = 0L
    private var pendingSession: Deferred<Long>? = null
    private var inputRevision = 0L
    private var initialized = false
    private var blankSelection = true
    private var blankTitle: String? = null
    private val inFlightSends = mutableSetOf<Pair<Long, Long>>()
    private var navigationCommand = 0L
    private var enginePendingSessionId: Long? = null
    private var currentRows = emptyList<MessageEntity>()
    private var currentAttachmentRows = emptyList<com.example.aiuseagent.data.local.AttachmentEntity>()
    private var generationHistoryJob: Job? = null
    private var toolHistoryJob: Job? = null
    private var recentGeneration: GenerationEntity? = null
    private var storedActivities = emptyList<String>()
    private val selections = mutableMapOf<ChatMode,Long?>()
    private val drafts = mutableMapOf<Pair<ChatMode,Long?>,String>()
    private val blankRoots = mutableMapOf<ChatMode,String>()
    private fun modeKey(mode: ChatMode) = if(mode==ChatMode.BUILD) "BUILD" else "CHAT"

    init {
        attachmentRepository?.let { repo -> viewModelScope.launch {
            repo.state.collect { cards -> _uiState.update { it.copy(attachments=cards.filter { card->card.scope==attachmentScope() }) } }
        } }
        coordinator?.let { engine -> viewModelScope.launch { engine.state.collect { refreshGenerationUi(it) } } }
        workspaces?.let { service -> viewModelScope.launch { service.state.collect { refreshWorkspaceUi() } } }
        viewModelScope.launch {
            val launch = preferences.chatLaunch.filterNotNull().first()
            if(!launch.startNewChat) {
                selections[ChatMode.CONVERSATION]=launch.lastSessionId
                selections[ChatMode.BUILD]=launch.lastBuildSessionId
            }
            repository.observeSessions().collect { sessions ->
                val available=sessions.filter { it.mode==modeKey(_uiState.value.mode) }
                val previousActive = _uiState.value.activeSessionId
                val nextActive = when {
                    !initialized -> {
                        initialized = true
                        blankSelection = launch.startNewChat || available.isEmpty()
                        if (blankSelection) null
                        else available.firstOrNull { it.id == launch.lastSessionId }?.id ?: available.firstOrNull()?.id
                    }
                    pendingSession != null -> previousActive
                    enginePendingSessionId != null -> enginePendingSessionId.also { pending ->
                        if (sessions.any { it.id == pending }) enginePendingSessionId = null
                    }
                    blankSelection -> null
                    previousActive != null && sessions.any { it.id == previousActive } -> previousActive
                    else -> {
                        selectionGeneration++
                        available.firstOrNull()?.id.also {
                            blankSelection = it == null
                            if (it == null) preferences.rememberSession(null,modeKey(_uiState.value.mode))
                        }
                    }
                }
                // 发布新列表会同步恢复等待创建的协程；旧选择必须在发布之前处理。
                activateSession(nextActive)
                _uiState.update {
                    it.copy(isReady = true, sessions = sessions,viewingLegacy=sessions.any { row -> row.id==nextActive && row.mode=="LEGACY" })
                }
                if (_uiState.value.mode==ChatMode.BUILD && nextActive != null && sessions.any { it.id == nextActive }) {
                    val binding = sessions.firstOrNull { it.id == nextActive }?.workspaceId ?: "default"
                    _uiState.update { it.copy(workspaceId = binding) }
                    refreshWorkspaceUi()
                }
            }
        }
    }

    /** 标题与消息一起切换；同一会话的列表更新时间不重启消息订阅。 */
    private fun activateSession(sessionId: Long?, clearNotice: Boolean = false,
        nextMode: ChatMode = _uiState.value.mode,preserveInput: Boolean = false) {
        val previous=_uiState.value
        val changed = previous.activeSessionId != sessionId || previous.mode!=nextMode
        if (changed && !preserveInput) replaceAttachmentDraft()
        val legacy=previous.sessions.any { it.id==sessionId && it.mode=="LEGACY" }
        if(changed) drafts[previous.mode to previous.activeSessionId]=previous.input
        _uiState.update {
            it.copy(
                activeSessionId = sessionId,
                mode=nextMode,viewingLegacy=legacy,
                input=if(!changed || preserveInput) it.input else drafts[nextMode to sessionId].orEmpty(),
                messages = if (changed) emptyList() else it.messages,
                notice = if (clearNotice) null else it.notice,
            )
        }
        if (!changed) return
        currentRows = emptyList()
        currentAttachmentRows = emptyList()
        recentGeneration = null
        storedActivities = emptyList()
        generationHistoryJob?.cancel()
        toolHistoryJob?.cancel()
        if(!legacy) {
            selections[nextMode]=sessionId
            preferences.rememberSession(sessionId,modeKey(nextMode))
        }
        val generation = ++messagesGeneration
        messagesJob?.cancel()
        if (sessionId == null) {
            messagesJob = null
            refreshGenerationUi()
            return
        }
        messagesJob = viewModelScope.launch {
            kotlinx.coroutines.flow.combine(repository.observeMessages(sessionId), repository.observeAttachments(sessionId)) { rows, attachments ->
                rows to attachments
            }.collect { (rows, attachments) ->
                if (generation == messagesGeneration && _uiState.value.activeSessionId == sessionId) {
                    currentRows = rows
                    currentAttachmentRows = attachments
                    if(openedHistory?.snapshot?.input?.handle?.id?.let {id->attachments.none {it.id==id}}==true)
                        dismissHistoryAttachmentPreview()
                }
                _uiState.update { state ->
                    // 取消边界上的旧结果也不得覆盖较新的选择（包括 A → B → A）。
                    if (generation != messagesGeneration || state.activeSessionId != sessionId) state
                    else state.copy(
                        messages = rows.filterNot { row ->
                            coordinator?.state?.value?.let { it.busy && row.role == "assistant" && row.generationId == it.generationId } == true
                        }.map { row ->
                            ChatMessage(
                                id = row.id,
                                role = MessageRole.fromRaw(row.role),
                                content = row.content,
                                createdAt = row.createdAt,
                                isDemo = row.isDemo,
                                status = row.status,
                                providerType = row.providerType,
                                generationId = row.generationId,
                                attachments = messageAttachments(row.id, attachments),
                            )
                        },
                    )
                }
            }
        }
        if (coordinator != null) {
            _uiState.update { state -> state.copy(workspaceId = if(nextMode==ChatMode.BUILD) state.sessions.firstOrNull { it.id == sessionId }?.workspaceId ?: "default" else "default") }
            refreshWorkspaceUi()
            generationHistoryJob = viewModelScope.launch {
                repository.observeGenerations(sessionId).collect { records ->
                    if (generation != messagesGeneration || _uiState.value.activeSessionId != sessionId) return@collect
                    val latest = records.lastOrNull()
                    if (recentGeneration?.id != latest?.id) {
                        toolHistoryJob?.cancel(); storedActivities = emptyList()
                        if (latest != null) toolHistoryJob = viewModelScope.launch {
                            repository.observeToolRounds(latest.id).collect { rounds ->
                                if (generation == messagesGeneration) {
                                    storedActivities = rounds.map { (if (it.name == "list_directory") tr("列出工作区", "List workspace") else tr("读取文本", "Read text")) + " · " + it.status + if(it.sequence==null) tr("（历史顺序未知）", "(Order unknown)") else "" }
                                    refreshGenerationUi()
                                }
                            }
                        }
                    }
                    recentGeneration = latest
                    refreshGenerationUi()
                }
            }
        }
    }

    fun onInputChange(text: String) {
        inputRevision++
        _uiState.update { it.copy(input = text) }
    }

    fun dismissNotice() {
        _uiState.update { it.copy(notice = null) }
    }

    fun setMode(mode: ChatMode) {
        if(mode==_uiState.value.mode && !_uiState.value.viewingLegacy) return
        val action = {
            val previous=_uiState.value
            blankRoots[previous.mode]=previous.workspaceId
            selectionGeneration++;inputRevision++;pendingSession=null;enginePendingSessionId=null
            val target=if(previous.viewingLegacy) null else selections[mode]?.takeIf { id -> previous.sessions.any { it.id==id && it.mode==modeKey(mode) } }
            blankSelection=target==null
            activateSession(target,true,mode)
            _uiState.update { it.copy(workspaceId=if(mode==ChatMode.BUILD) previous.sessions.firstOrNull { row -> row.id==target }?.workspaceId ?: blankRoots[mode] ?: "default" else "default") }
            refreshWorkspaceUi()
        }
        if(coordinator!=null) afterStop(action) else action()
    }

    /** 新聊天仅进入空白界面；首次有效发送才创建会话，不再插入演示消息。 */
    fun newSession(title: String? = null) {
        if (coordinator != null) { afterStop { newSessionNow(title) }; return }
        newSessionNow(title)
    }

    private fun newSessionNow(title: String?) {
        if (!_uiState.value.isReady) return
        replaceAttachmentDraft()
        selectionGeneration++
        inputRevision++
        pendingSession = null
        enginePendingSessionId = null
        blankSelection = true
        blankTitle = title
        drafts[_uiState.value.mode to null]=""
        activateSession(null, clearNotice = true)
        selections[_uiState.value.mode]=null
        _uiState.update { it.copy(input = "", workspaceId = "default") }
        refreshWorkspaceUi()
    }

    suspend fun permissionSession(): Long? {
        if(!_uiState.value.isReady || _uiState.value.mode!=ChatMode.BUILD || _uiState.value.viewingLegacy) return null
        if(coordinator?.stopAndJoin(tr("调整权限，已停止", "Permission adjusted, stopped")) == false) return null
        val id=_uiState.value.activeSessionId ?: (pendingSession ?: createSessionTarget()).await()
        return id.takeIf { _uiState.value.activeSessionId==id && _uiState.value.mode==ChatMode.BUILD }
    }

    private fun createSessionTarget(): Deferred<Long> {
        val selection = ++selectionGeneration
        val title = blankTitle
        val creationState = _uiState.value
        val target = CompletableDeferred<Long>()
        pendingSession = target
        viewModelScope.launch {
            try {
                val id = repository.createSession(title,modeKey(creationState.mode),creationState.workspaceId)
                // 等待 DAO 列表包含新行，避免异步的旧列表把刚选中的新会话退回旧会话。
                uiState.first { state -> state.sessions.any { it.id == id } }
                if (selection == selectionGeneration) {
                    pendingSession = null
                    blankSelection = false
                    activateSession(id, clearNotice = true,preserveInput=true)
                }
                target.complete(id)
            } catch (error: Exception) {
                if (selection == selectionGeneration) pendingSession = null
                target.completeExceptionally(error)
                if (error is CancellationException) throw error
            }
        }
        return target
    }

    fun selectSession(sessionId: Long) {
        if (!_uiState.value.isReady || _uiState.value.sessions.none { it.id == sessionId }) return
        if (coordinator != null) { afterStop { selectSessionNow(sessionId) }; return }
        selectSessionNow(sessionId)
    }

    private fun selectSessionNow(sessionId: Long) {
        selectionGeneration++
        pendingSession = null
        enginePendingSessionId = null
        blankSelection = false
        val row=_uiState.value.sessions.firstOrNull { it.id==sessionId } ?: return
        val mode=when(row.mode) { "BUILD" -> ChatMode.BUILD;"CHAT" -> ChatMode.CONVERSATION;else -> _uiState.value.mode }
        activateSession(sessionId,nextMode=mode)
    }

    /**
     * 发送：把用户的输入**真实写入本地数据库**。
     * 本阶段没有模型接入，因此不会产生任何回复，只显示明确的提示。
     */
    fun send() {
        if (!_uiState.value.isReady || _uiState.value.preparingAttachments) return
        if(_uiState.value.attachments.isNotEmpty()) {
            if(!_uiState.value.canSendMessage) return
            if(coordinator==null) {reportAttachmentIssue(tr("模型尚未就绪，附件已保留在草稿。", "The model isn't ready yet; attachments were kept in the draft."));return}
            sendWithModel(_uiState.value.input.trim());return
        }
        if(_uiState.value.viewingLegacy) {
            _uiState.update { it.copy(notice=tr("历史记录仅供查看；点击交流或构建创建新会话，旧记录不会自动发送。", "History is view-only; tap Chat or Build to create a new session. Old records are not sent automatically.")) };return
        }
        val text = _uiState.value.input.trim()
        if (text.isEmpty()) return
        if (coordinator != null) { sendWithModel(text); return }
        if ((selectionGeneration to inputRevision) in inFlightSends) return
        // 在点击发送时绑定目标；新建尚未返回 id 时，仍属于这次新建，而不是旧会话。
        val target = pendingSession
            ?: _uiState.value.activeSessionId?.let { CompletableDeferred(it) }
            ?: createSessionTarget()
        val selection = selectionGeneration
        val revision = inputRevision
        val sendKey = selection to revision
        inFlightSends.add(sendKey)
        viewModelScope.launch {
            try {
                val sessionId = target.await()
                repository.appendMessage(sessionId, SessionRepository.ROLE_USER, text)
                _uiState.update {
                    if (selection != selectionGeneration || it.activeSessionId != sessionId) it
                    else it.copy(
                        input = if (revision == inputRevision) "" else it.input,
                        notice = MODEL_NOT_CONNECTED_NOTICE,
                    )
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                if (selection == selectionGeneration) {
                    _uiState.update { it.copy(notice = tr("消息未能保存，请稍后重试。", "The message couldn't be saved. Try again later.")) }
                }
            } finally {
                inFlightSends.remove(sendKey)
            }
        }
    }

    // --- 删除：必须经过确认 ---

    fun requestDelete(session: SessionEntity) {
        _uiState.update { it.copy(pendingDeleteSession = session) }
    }

    fun cancelDelete() {
        _uiState.update { it.copy(pendingDeleteSession = null) }
    }

    fun confirmDelete() {
        val target = _uiState.value.pendingDeleteSession ?: return
        if (coordinator != null) {
            viewModelScope.launch {
                if ((coordinator.state.value.sessionId == target.id || _uiState.value.activeSessionId == target.id) &&
                    !coordinator.stopAndJoin(tr("删除会话，已停止", "Session deleted, stopped"))) return@launch
                repository.deleteSession(target.id)
                _uiState.update { it.copy(pendingDeleteSession = null) }
            }
            return
        }
        viewModelScope.launch {
            repository.deleteSession(target.id)
            _uiState.update { it.copy(pendingDeleteSession = null) }
        }
    }

    // --- 重命名 ---

    fun requestRename(session: SessionEntity) {
        _uiState.update {
            it.copy(pendingRenameSession = session, renameDraft = session.title, renameError = null)
        }
    }

    fun updateRenameDraft(text: String) {
        _uiState.update { it.copy(renameDraft = text, renameError = null) }
    }

    fun cancelRename() {
        _uiState.update { it.copy(pendingRenameSession = null, renameDraft = "", renameError = null) }
    }

    fun confirmRename() {
        val target = _uiState.value.pendingRenameSession ?: return
        val draft = _uiState.value.renameDraft
        viewModelScope.launch {
            when (repository.renameSession(target.id, draft)) {
                RenameResult.Success -> _uiState.update {
                    it.copy(pendingRenameSession = null, renameDraft = "", renameError = null)
                }
                RenameResult.BlankTitle -> _uiState.update {
                    it.copy(renameError = tr("标题不能为空", "Title can't be empty"))
                }
                RenameResult.TooLong -> _uiState.update {
                    it.copy(renameError = tr("标题最长 ", "Title can be at most ") + SessionRepository.MAX_TITLE_LENGTH + tr(" 个字符", " characters"))
                }
                RenameResult.NotFound -> _uiState.update {
                    it.copy(pendingRenameSession = null, renameError = null)
                }
            }
        }
    }

    private fun afterStop(action: () -> Unit) {
        val command = ++navigationCommand
        coordinator?.stop(tr("切换操作，已停止", "Action switched, stopped"))
        viewModelScope.launch {
            if (coordinator?.stopAndJoin(tr("切换操作，已停止", "Action switched, stopped")) != false && command == navigationCommand) action()
        }
    }

    private fun sendWithModel(text: String, retryUserId: Long? = null) {
        val engine = coordinator ?: return
        if(_uiState.value.preparingAttachments || engine.state.value.busy) return
        val selection = selectionGeneration
        val revision = inputRevision
        val state = _uiState.value
        var allowedSession=state.activeSessionId
        fun submit(input:AttachmentGenerationInput?):Boolean = engine.submit(GenerationSubmission(state.activeSessionId, text, state.workspaceId,
            state.mode == ChatMode.BUILD, retryUserId,input)) { turn ->
            if (selection == selectionGeneration) {
                allowedSession=turn.sessionId
                blankSelection = false
                pendingSession = null
                enginePendingSessionId = turn.sessionId.takeUnless { target -> _uiState.value.sessions.any { it.id == target } }
                activateSession(turn.sessionId, clearNotice = true,preserveInput=true)
                if(retryUserId==null) {
                    val committedIds=input?.commit?.records?.map {it.handle.id}.orEmpty().toSet()
                    committedIds.forEach {attachmentResults.remove(it)}
                    _uiState.update {it.copy(attachmentSelections=it.attachmentSelections-committedIds,attachmentPreview=null)}
                }
                _uiState.update { it.copy(input = if (revision == inputRevision && retryUserId == null) "" else it.input) }
            }
        }
        val repo=attachmentRepository
        if(repo==null) {submit(null);return}
        val bound=attachmentScope()
        val budget=java.security.MessageDigest.getInstance("SHA-256").digest(
            "attachment-budget:1\n${bound.profileId}\n${bound.profileRevision}".toByteArray()).joinToString("") {"%02x".format(it)}
        val current={attachmentScope()==bound.copy(sessionId=allowedSession) && selection==selectionGeneration}
        _uiState.update {it.copy(preparingAttachments=true)}
        attachmentTickets.clear()
        attachmentSendJob=viewModelScope.launch {
            var commit:AttachmentCommit?=null
            var accepted=false
            try {
                val assembler=AttachmentRequestAssembler(repo,budget) { scope,handle ->
                    ImageAttachmentProcessor {repo.read(scope,it)}.normalize(handle,AttachmentLimits())
                }
                val prepared=if(retryUserId==null && state.attachments.isNotEmpty())
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        kotlinx.coroutines.withTimeout(AttachmentLimits().parseTimeoutMillis) {
                            val frozen=repo.freeze(bound,state.attachmentSelections.values.toList(),budget)
                            assembler.assemble(bound,frozen,text).also {commit=repo.prepareCommit(bound,frozen,it)}
                        }
                    } else null
                if(!current()) throw AttachmentFailure("SCOPE_CHANGED")
                lateinit var input:AttachmentGenerationInput
                input=AttachmentGenerationInput(bound,prepared,commit,current) { references ->
                    input.hold(repo.acquireHistoryLease(references))
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                        kotlinx.coroutines.withTimeout(AttachmentLimits().parseTimeoutMillis) {
                            references.groupBy {it.messageId}.mapValues { (_,rows) ->
                                val frozen=rows.map { row ->
                                    val restored=repo.restoreReference(bound,row)
                                    val read:suspend (AttachmentHandle)->ByteArray={repo.read(bound,it)}
                                    val result=if(ImageAttachmentProcessor.isSupportedImage(read(restored.handle)))
                                        ImageAttachmentProcessor(read).parse(restored.handle,AttachmentLimits())
                                    else TextAttachmentParser(read).parse(restored.handle,AttachmentLimits())
                                    repo.parsed(bound,restored.handle,result)
                                    restored
                                }
                                assembler.assemble(bound,frozen,"")
                            }
                        }
                    }
                }
                accepted=submit(input)
            } catch(error:CancellationException) {throw error}
            catch(_:Exception) {reportAttachmentIssue(tr("附件或已保存的选择范围已失效，请重新选择并确认后发送。", "The attachment or saved range is no longer valid. Reselect and confirm before sending."))} finally {
                if(!accepted) kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    try {commit?.finish(null)} finally {commit?.releaseSending()}
                }
                if(selection==selectionGeneration) _uiState.update {it.copy(preparingAttachments=false)}
            }
        }
    }

    fun stopGeneration() { attachmentSendJob?.cancel();coordinator?.stop(tr("用户停止", "Stopped by user")) }
    fun selectionVersion(): Long = selectionGeneration
    fun reportWorkspaceIssue() { _uiState.update { it.copy(notice = tr("文件选择已失效或包含敏感信息，请重新操作。", "The file selection is invalid or contains sensitive info. Please try again.")) } }

    fun retryGeneration() {
        val engine = coordinator ?: return
        if (engine.state.value.savingFailed) { engine.retrySave(); return }
        if (!_uiState.value.canRetry || engine.state.value.busy) return
        val target = _uiState.value.activeSessionId ?: return
        val userId = if (engine.state.value.sessionId == target) engine.state.value.userMessageId else recentGeneration?.userMessageId
        val row = currentRows.firstOrNull { it.id == userId && it.role == "user" } ?: return
        sendWithModel(row.content, row.id)
    }

    fun selectWorkspace(id: String) {
        val service = workspaces ?: return
        if(_uiState.value.mode!=ChatMode.BUILD || _uiState.value.workspaceId==id) return
        afterStop {
            val selectedGeneration = selectionGeneration
            viewModelScope.launch {
                val target = _uiState.value.activeSessionId
                try {
                    if(target!=null && repository.messageCount(target)>0) {
                        if(selectionGeneration!=selectedGeneration) return@launch
                        newSessionNow(null)
                        _uiState.update { it.copy(workspaceId=id) };blankRoots[ChatMode.BUILD]=id
                        refreshWorkspaceUi();return@launch
                    }
                    if (target != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { service.bind(target, id) }
                    if (selectionGeneration == selectedGeneration && _uiState.value.activeSessionId == target) {
                        _uiState.update { it.copy(workspaceId = id) }
                        refreshWorkspaceUi()
                    }
                } catch (_: Exception) { _uiState.update { it.copy(notice = tr("工作区不可用，请重新选择。", "Workspace unavailable. Please select again.")) } }
            }
        }
    }

    fun refreshWorkspaceUi() {
        val service = workspaces ?: return
        val id = _uiState.value.workspaceId
        val summary = service.state.value.workspaces.firstOrNull { it.id == id }
        _uiState.update { it.copy(workspaceName = summary?.name ?: tr("工作区不可用", "Workspace unavailable"),
            workspaceAvailable = summary?.status == WorkspaceStatus.AVAILABLE) }
    }

    fun refreshModelLabel() {
        val repository = providers ?: return
        val type = repository.activeType()
        val ready = repository.availability(type).isReady
        _uiState.update { it.copy(modelLabel = when {
            !ready -> tr("模型缺少配置或 Key", "Model is missing config or Key")
            type.name == "MOCK" -> tr("本地 Mock · 不联网", "Local Mock · Offline")
            else -> {
                val prepared=repository.prepare(type)
                val brand=when(prepared.config.connectionProfile?.brand) {
                    com.example.aiuseagent.domain.provider.ProviderBrand.DEEPSEEK->"DeepSeek"
                    com.example.aiuseagent.domain.provider.ProviderBrand.OPENAI->"OpenAI"
                    com.example.aiuseagent.domain.provider.ProviderBrand.CLAUDE->"Claude"
                    com.example.aiuseagent.domain.provider.ProviderBrand.QWEN->tr("通义千问", "Qwen")
                    com.example.aiuseagent.domain.provider.ProviderBrand.ZHIPU->tr("智谱", "Zhipu")
                    com.example.aiuseagent.domain.provider.ProviderBrand.KIMI->"Kimi"
                    com.example.aiuseagent.domain.provider.ProviderBrand.MINIMAX->"MiniMax"
                    com.example.aiuseagent.domain.provider.ProviderBrand.GEMINI->"Gemini"
                    com.example.aiuseagent.domain.provider.ProviderBrand.CUSTOM->tr("自定义服务", "Custom service")
                    null->type.label
                }
                "$brand · ${prepared.model}"
            }
        }) }
    }

    private fun refreshGenerationUi(generation: GenerationState? = coordinator?.state?.value) {
        val state = generation ?: return
        val id = _uiState.value.activeSessionId
        val visible = state.sessionId == id && (id != null || state.generationId != null)
        val current = visible && (state.busy || state.generationId == null || recentGeneration == null || state.generationId == recentGeneration?.id)
        val rows = if (state.busy && visible) currentRows.filterNot { it.role == "assistant" && it.generationId == state.generationId } else currentRows
        _uiState.update { it.copy(
            isGenerating = state.busy,
            generationStatus = if (current) state.status.takeIf(String::isNotBlank) else recentGeneration?.status,
            generationText = state.text.takeIf { state.busy && visible && it.isNotBlank() },
            generationError = if (current || state.sessionId == null && id == null) state.error else recentGeneration?.errorKind?.let { tr("上次请求未完成：", "Last request unfinished: ") + it },
            toolActivities = if (current && state.busy) state.activities else storedActivities,
            canRetry = if (state.savingFailed) true else !state.busy &&
                (if (current) state.canRetry else recentGeneration?.status in setOf("FAILED", "CANCELLED", "INTERRUPTED")),
            messages = rows.map { row -> ChatMessage(row.id, MessageRole.fromRaw(row.role), row.content, row.createdAt,
                row.isDemo, row.status, row.providerType, row.generationId, messageAttachments(row.id, currentAttachmentRows)) },
        ) }
        refreshModelLabel()
    }

    private fun messageAttachments(messageId: Long, rows: List<com.example.aiuseagent.data.local.AttachmentEntity>) =
        rows.filter { it.messageId == messageId }.map { ChatAttachment(it.id, it.name, it.mimeType, it.sizeBytes) }

    companion object {
        val MODEL_NOT_CONNECTED_NOTICE =
            tr("消息已保存到本机。模型未连接，暂时不会生成回复。", "Message saved on this device. The model isn't connected, so no reply will be generated yet.")
    }
}
