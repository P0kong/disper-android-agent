package com.example.aiuseagent.data

import com.example.aiuseagent.data.local.MessageEntity
import com.example.aiuseagent.data.local.MessageDao
import com.example.aiuseagent.data.local.SessionDao
import com.example.aiuseagent.data.local.SessionEntity
import com.example.aiuseagent.data.local.GenerationEntity
import com.example.aiuseagent.data.local.ToolRoundEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class GenerationTurn(val sessionId: Long, val userMessageId: Long, val context: List<MessageEntity>,
    val scope: SessionContextScope,val attachments:List<com.example.aiuseagent.data.local.AttachmentEntity> = emptyList()) {
    override fun toString(): String = "GenerationTurn(sessionId=$sessionId,userMessageId=$userMessageId,contextSize=${context.size})"
}

/** 重命名结果：调用方必须区分「空白标题」和「成功」，不允许静默吞掉。 */
sealed interface RenameResult {
    data object Success : RenameResult
    data object BlankTitle : RenameResult
    data object TooLong : RenameResult
    data object NotFound : RenameResult
}

/**
 * 会话/消息的唯一数据入口。所有读写都落到 Room（DAO），
 * 本阶段没有任何网络或模型调用参与。
 */
class SessionRepository(
    private val sessionDao: SessionDao,
    private val messageDao: MessageDao,
    private val generationPersistence: GenerationPersistence? = null,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val generationMutex = Mutex()
    internal var onAttachmentsUnused:(()->Unit)?=null
    fun observeSessions(): Flow<List<SessionEntity>> = sessionDao.observeSessions()

    fun observeMessages(sessionId: Long): Flow<List<MessageEntity>> = messageDao.observeMessages(sessionId)
    fun observeAttachments(sessionId: Long): Flow<List<com.example.aiuseagent.data.local.AttachmentEntity>> =
        generationPersistence?.attachments?.observeInSession(sessionId) ?: kotlinx.coroutines.flow.flowOf(emptyList())

    suspend fun findSession(id: Long): SessionEntity? = sessionDao.findById(id)

    /** 新建会话。标题为空时使用默认标题。 */
    suspend fun createSession(title: String? = null, mode: String = "CHAT", workspaceId: String? = null): Long {
        require(mode in setOf("CHAT", "BUILD"))
        val timestamp = now()
        val finalTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_TITLE
        return sessionDao.insert(
            SessionEntity(title = finalTitle, createdAt = timestamp, updatedAt = timestamp, mode = mode,
                workspaceId = if (mode == "BUILD") workspaceId ?: "default" else null),
        )
    }

    suspend fun renameSession(id: Long, newTitle: String): RenameResult {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return RenameResult.BlankTitle
        if (trimmed.length > MAX_TITLE_LENGTH) return RenameResult.TooLong
        if (sessionDao.findById(id) == null) return RenameResult.NotFound
        sessionDao.rename(id, trimmed, now())
        return RenameResult.Success
    }

    /** 删除会话。外键 CASCADE 会一并删除该会话的消息。 */
    suspend fun deleteSession(id: Long) = generationMutex.withLock {
        try {
            val persistence=generationPersistence
            if(persistence==null) sessionDao.delete(id) else persistence.transaction {
                persistence.attachmentObjects?.prepareSessionDeletion(id)
                sessionDao.delete(id)
            }
        } finally {onAttachmentsUnused?.invoke()}
    }

    suspend fun appendMessage(
        sessionId: Long,
        role: String,
        content: String,
        isDemo: Boolean = false,
    ): Long {
        val timestamp = now()
        val messageId = messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                role = role,
                content = content,
                createdAt = timestamp,
                isDemo = isDemo,
            ),
        )
        sessionDao.touch(sessionId, timestamp)
        return messageId
    }

    suspend fun messageCount(sessionId: Long): Int = messageDao.countInSession(sessionId)

    suspend fun attachmentHistory(scope:com.example.aiuseagent.domain.attachment.AttachmentScope,
        providerType:String,retryUserMessageId:Long?):List<com.example.aiuseagent.data.local.AttachmentEntity> = generationMutex.withLock {
        val id=scope.sessionId ?: return@withLock emptyList()
        val session=requireNotNull(sessionDao.findById(id))
        val bound=SessionContextScope(id,scope.mode,scope.workspaceId,scope.workspaceVersion,scope.contextEpoch,
            requireNotNull(scope.profileId),scope.profileRevision)
        if(!SessionContextPolicy.matches(session,bound)) return@withLock emptyList()
        val rows=messageDao.listInSession(id)
        val before=if(retryUserMessageId==null) rows else rows.takeWhile {it.id!=retryUserMessageId}
        val selected=before.filter {SessionContextPolicy.accepts(it,bound) && it.providerType==providerType}.takeLast(MAX_CONTEXT_MESSAGES-1)
        val ids=selected.map {it.id}.toSet()+listOfNotNull(retryUserMessageId)
        generationPersistence?.attachments?.inSession(id).orEmpty().filter {it.messageId in ids}
    }

    /** Validation runs before any mutation; production also wraps all writes in one transaction. */
    suspend fun prepareGeneration(
        sessionId: Long?, text: String, providerType: String, workspaceId: String?, generationId: String,
        retryUserMessageId: Long? = null, mode: String = "CHAT", profileId: String = "builtin:$providerType",
        profileRevision: Long = 0, workspaceVersion: Long = 0,
        attachments:com.example.aiuseagent.data.attachment.AttachmentCommit? = null,
        expectedAttachments:List<com.example.aiuseagent.data.local.AttachmentEntity>? = null,
        validateContext: (List<MessageEntity>) -> Unit,
    ): GenerationTurn = generationMutex.withLock {
        val persistence = requireNotNull(generationPersistence) { "Generation persistence unavailable" }
        var result: GenerationTurn? = null
        try {
        persistence.transaction {
            require(generationId.isNotBlank() && persistence.generations.findById(generationId) == null)
            require(mode in setOf("CHAT", "BUILD"))
            val root = if (mode == "BUILD") workspaceId ?: "default" else null
            val selected = sessionId?.let { requireNotNull(sessionDao.findById(it)) { "Session removed" } }
            val session = selected?.takeUnless { SessionContextPolicy.requiresNewSession(it, mode, root) }
            val changedConnection = session != null && (session.profileId != profileId || session.profileRevision != profileRevision)
            val epoch = if (changedConnection) Math.addExact(session!!.contextEpoch, 1L) else session?.contextEpoch ?: 0L
            val scope = SessionContextScope(session?.id ?: 0L,mode,root,workspaceVersion,epoch,profileId,profileRevision)
            attachments?.let { commit ->
                val bound=commit.scope
                require(commit.isCurrent() && retryUserMessageId==null)
                require(bound.sessionId==session?.id && bound.mode==mode && bound.workspaceId==root &&
                    bound.workspaceVersion==workspaceVersion && bound.contextEpoch==epoch &&
                    bound.profileId==profileId && bound.profileRevision==profileRevision)
                require(commit.records.size in 1..10 && commit.records.map {it.handle.id}.distinct().size==commit.records.size)
                require(commit.records.all { record ->
                    val handle=record.handle
                    handle.id.matches(ATTACHMENT_TOKEN) && handle.localToken.matches(ATTACHMENT_TOKEN) &&
                        handle.sha256.matches(Regex("[0-9a-f]{64}")) && handle.sizeBytes in 1..20L*1024*1024 &&
                        handle.mimeType.length in 1..128 && record.name.length<=512
                })
                require(commit.records.sumOf {it.handle.sizeBytes}<=50L*1024*1024)
                requireNotNull(persistence.attachments)
            }
            val previous = session?.let { messageDao.listInSession(it.id) }.orEmpty()
            val retry = retryUserMessageId?.let { id ->
                requireNotNull(previous.firstOrNull { it.id == id && it.role == ROLE_USER &&
                    SessionContextPolicy.accepts(it,scope) && it.providerType == providerType })
            }
            require(retry != null || text.isNotBlank() || attachments!=null)
            val timestamp = now()
            val user = retry ?: MessageEntity(sessionId = session?.id ?: 0L, role = ROLE_USER,
                content = text, createdAt = timestamp, providerType = providerType, generationId = generationId,
                mode = mode,contextEpoch = epoch,profileId = profileId,profileRevision = profileRevision)
            val history = if (retry == null) previous else previous.takeWhile { it.id != retry.id }
            val context = (history.filter { row ->
                SessionContextPolicy.accepts(row,scope) && row.providerType == providerType
            } + user).takeLast(MAX_CONTEXT_MESSAGES)
            expectedAttachments?.let { expected ->
                val ids=context.map {it.id}.filter {it>0}.toSet()
                val actual=session?.let { persistence.attachments?.inSession(it.id) }.orEmpty().filter {it.messageId in ids}
                require(actual.sortedBy {it.id}==expected.sortedBy {it.id}) { "Attachment history changed" }
            }
            validateContext(context)
            require(attachments?.isCurrent?.invoke()!=false)
            val actualSessionId = session?.id ?: sessionDao.insert(SessionEntity(
                title = text.trim().take(MAX_TITLE_LENGTH).ifBlank { DEFAULT_TITLE },
                createdAt = timestamp, updatedAt = timestamp, workspaceId = root,mode = mode,
                contextEpoch = epoch,profileId = profileId,profileRevision = profileRevision))
            if (changedConnection) {
                check(sessionDao.updateContext(actualSessionId,session!!.contextEpoch,epoch,profileId,profileRevision)==1)
                messageDao.interruptSession(actualSessionId)
                persistence.generations.interruptSession(actualSessionId,timestamp)
            }
            val userId = retry?.id ?: messageDao.insert(user.copy(sessionId = actualSessionId))
            attachments?.records?.forEach { record ->
                val handle=record.handle
                requireNotNull(persistence.attachments).insert(com.example.aiuseagent.data.local.AttachmentEntity(
                    handle.id,actualSessionId,userId,epoch,profileId,profileRevision,record.name,handle.mimeType,
                    handle.sizeBytes,handle.localToken,handle.sha256,"PREPARED",record.selectionJson,
                    record.snapshot?.token,record.snapshot?.sha256,record.snapshot?.sizeBytes))
                if(record.snapshot!=null) requireNotNull(persistence.attachmentObjects).bind(handle.id,record.objects)
                else require(record.objects.isEmpty())
            }
            persistence.generations.insert(GenerationEntity(id = generationId, sessionId = actualSessionId,
                userMessageId = userId, providerType = providerType, workspaceId = root ?: "",
                createdAt = timestamp, updatedAt = timestamp,mode = mode,contextEpoch = epoch,
                profileId = profileId,profileRevision = profileRevision,workspaceVersion = workspaceVersion))
            sessionDao.touch(actualSessionId, timestamp)
            require(attachments?.isCurrent?.invoke()!=false)
            val contextIds=context.dropLast(1).map {it.id}.toSet()+userId
            val references=persistence.attachments?.inSession(actualSessionId).orEmpty().filter {
                it.messageId in contextIds && it.contextEpoch==epoch && it.profileId==profileId && it.profileRevision==profileRevision
            }
            result = GenerationTurn(actualSessionId, userId,
                context.dropLast(1) + user.copy(id = userId, sessionId = actualSessionId),scope.copy(sessionId=actualSessionId),references)
        }
        requireNotNull(result)
        } finally {
            attachments?.let { commit ->
                // Cancellation can arrive after Room commits but before it returns to us.
                // Settle ownership from durable references, never from a local success flag.
                withContext(NonCancellable) {
                    val generation = persistence.generations.findById(generationId)
                    val references = generation?.let {
                        requireNotNull(persistence.attachments).inSession(it.sessionId)
                    }.orEmpty()
                    val retained = generation != null && commit.records.all { record ->
                        references.any { reference ->
                            reference.messageId == generation.userMessageId &&
                                reference.id == record.handle.id &&
                                reference.localToken == record.handle.localToken &&
                                reference.sha256 == record.handle.sha256 &&
                                reference.selectionJson == record.selectionJson &&
                                reference.snapshotToken == record.snapshot?.token &&
                                reference.snapshotSha256 == record.snapshot?.sha256 &&
                                reference.snapshotSizeBytes == record.snapshot?.sizeBytes
                        }
                    } && commit.records.all {record -> record.snapshot==null ||
                        requireNotNull(persistence.attachmentObjects).referencedObjects(record.handle.id).toSet()==record.objects.toSet()}
                    commit.finish(generation?.sessionId?.takeIf { retained })
                }
            }
        }
    }

    /** Terminal states are sticky, including zero-text failures. Deleted sessions cannot reappear. */
    suspend fun persistGeneration(generationId: String, content: String, status: String,
        errorKind: String? = null): Long? = generationMutex.withLock {
        require(status in GENERATION_STATUSES)
        val persistence = requireNotNull(generationPersistence)
        var assistantId: Long? = null
        persistence.transaction {
            val generation = persistence.generations.findById(generationId)
            if (generation == null || generation.status != "STREAMING" || generation.profileId == null) return@transaction
            val current = sessionDao.findById(generation.sessionId) ?: return@transaction
            val scope = SessionContextScope(generation.sessionId,generation.mode,
                generation.workspaceId.takeIf { generation.mode=="BUILD" },generation.workspaceVersion,
                generation.contextEpoch,generation.profileId,generation.profileRevision)
            if (!SessionContextPolicy.matches(current,scope)) return@transaction
            assistantId = generation.assistantMessageId
            if (assistantId == null && content.isNotBlank()) {
                assistantId = messageDao.insert(MessageEntity(sessionId = generation.sessionId,
                    role = ROLE_ASSISTANT, content = content, createdAt = now(), status = status,
                    providerType = generation.providerType, generationId = generationId,
                    replyToMessageId = generation.userMessageId,mode=generation.mode,contextEpoch=generation.contextEpoch,
                    profileId=generation.profileId,profileRevision=generation.profileRevision))
            } else if (assistantId != null) {
                messageDao.updateGeneration(assistantId!!, generation.sessionId, generationId, content, status)
            }
            val safeKind = errorKind?.takeIf { it.matches(Regex("[A-Z_]{1,80}")) }
                ?: if (errorKind == null) null else "UNKNOWN"
            persistence.generations.updateActive(generationId, assistantId, status, now(), safeKind)
        }
        assistantId
    }

    suspend fun recordTool(generationId: String, callId: String, name: String, status: String,
        bytes: Int, durationMs: Long) = generationMutex.withLock {
        val persistence = requireNotNull(generationPersistence)
        persistence.transaction {
            val generation = persistence.generations.findById(generationId)
            if (generation?.status != "STREAMING") return@transaction
            require(name in setOf("list_directory", "read_text_file", "inspect_text_file",
                "write_text_file", "patch_text_file", "delete_file", "restore_file"))
            require(callId.length in 1..160)
            if (persistence.generations.findTool(generationId,callId) != null) return@transaction
            val sequence = Math.addExact(persistence.generations.lastToolSequence(generationId),1L)
            persistence.generations.recordTool(ToolRoundEntity(generationId, callId.take(160), name,
                status.take(40), bytes.coerceAtLeast(0), durationMs.coerceAtLeast(0),sequence))
        }
    }

    suspend fun recoverInterruptedGenerations() = generationMutex.withLock {
        val persistence = generationPersistence ?: return@withLock
        persistence.transaction {
            messageDao.recoverInterrupted()
            persistence.generations.recoverInterrupted(now())
        }
    }

    fun observeGenerations(sessionId: Long): Flow<List<GenerationEntity>> =
        requireNotNull(generationPersistence).generations.observe(sessionId)

    fun observeToolRounds(generationId: String): Flow<List<ToolRoundEntity>> =
        requireNotNull(generationPersistence).generations.observeTools(generationId)

    /**
     * 仅在会话为空时写入一条**明确标记为演示**的本地消息。
     * 它没有任何模型参与，界面上必须显示 isDemo 标记。
     */
    suspend fun seedDemoMessageIfEmpty(sessionId: Long) {
        if (messageDao.countInSession(sessionId) > 0) return
        messageDao.insert(
            MessageEntity(
                sessionId = sessionId,
                role = ROLE_ASSISTANT,
                content = DEMO_MESSAGE,
                createdAt = now(),
                isDemo = true,
            ),
        )
    }

    companion object {
        const val DEFAULT_TITLE = "新会话"
        const val MAX_TITLE_LENGTH = 60
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val MAX_CONTEXT_MESSAGES = 20
        private val ATTACHMENT_TOKEN=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val GENERATION_STATUSES = setOf("STREAMING", "COMPLETED", "CANCELLED", "FAILED", "INTERRUPTED")
        const val DEMO_MESSAGE =
            "这是本地演示消息（非模型回复）。本阶段只实现应用骨架与本地数据，" +
                "尚未接入任何模型接口。"
    }
}
