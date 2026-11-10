package com.example.aiuseagent.data

import com.example.aiuseagent.data.local.MessageDao
import com.example.aiuseagent.data.local.MessageEntity
import com.example.aiuseagent.data.local.SessionDao
import com.example.aiuseagent.data.local.SessionEntity
import com.example.aiuseagent.data.local.GenerationEntity
import com.example.aiuseagent.data.local.GenerationDao
import com.example.aiuseagent.data.local.ToolRoundEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * 内存版 DAO 实现，行为对齐 Room 生成的实现：
 * 自增主键、observeSessions 按 updatedAt DESC、
 * observeMessages 按 createdAt ASC / id ASC、
 * 删除会话时按外键 ON DELETE CASCADE 一并删除消息。
 *
 * 它实现的是**应用真实使用的 DAO 接口**（SessionDao / MessageDao），
 * 因此 Repository 与 ViewModel 走的调用路径与真机一致。
 * 真实 SQLite 引擎的执行仍必须在设备/模拟器上验证，见 docs/MANUAL_TEST_PLAN.md。
 */
class FakeDaoStore {

    val sessions = MutableStateFlow<List<SessionEntity>>(emptyList())
    val messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val generations = MutableStateFlow<List<GenerationEntity>>(emptyList())
    val tools = MutableStateFlow<List<ToolRoundEntity>>(emptyList())
    val attachments = MutableStateFlow<List<com.example.aiuseagent.data.local.AttachmentEntity>>(emptyList())
    var failAttachmentInsert=false
    val attachmentDao=object:com.example.aiuseagent.data.local.AttachmentDao {
        override fun observeInSession(sessionId:Long)=attachments.map { rows -> rows.filter {it.sessionId==sessionId}.sortedWith(compareBy({it.messageId},{it.id})) }
        override suspend fun inSession(sessionId:Long)=attachments.value.filter {it.sessionId==sessionId}
        override suspend fun insert(attachment:com.example.aiuseagent.data.local.AttachmentEntity) {
            check(!failAttachmentInsert) {"synthetic attachment persistence failure"}
            check(sessions.value.any {it.id==attachment.sessionId})
            check(messages.value.any {it.id==attachment.messageId && it.sessionId==attachment.sessionId})
            check(attachments.value.none {it.id==attachment.id})
            attachments.value+=attachment
        }
        override suspend fun delete(id:String,sessionId:Long):Int {
            val count=attachments.value.count {it.id==id && it.sessionId==sessionId}
            attachments.value=attachments.value.filterNot {it.id==id && it.sessionId==sessionId};return count
        }
    }

    private var nextSessionId = 1L
    private var nextMessageId = 1L

    val sessionDao: SessionDao = object : SessionDao {
        override suspend fun updatePermission(id: Long, level: String): Int {
            if(level !in setOf("VIEW_ONLY","WORKSPACE_EDIT") || sessions.value.none { it.id==id && it.mode=="BUILD" }) return 0
            sessions.value=sessions.value.map { if(it.id==id) it.copy(permissionMode=level) else it }
            return 1
        }
        override fun observeSessions(): Flow<List<SessionEntity>> =
            sessions.map { rows -> rows.sortedByDescending { it.updatedAt } }

        override suspend fun findById(id: Long): SessionEntity? =
            sessions.value.firstOrNull { it.id == id }

        override suspend fun insert(session: SessionEntity): Long {
            val id = nextSessionId++
            sessions.value = sessions.value + session.copy(id = id)
            return id
        }

        override suspend fun rename(id: Long, title: String, updatedAt: Long) {
            sessions.value = sessions.value.map {
                if (it.id == id) it.copy(title = title, updatedAt = updatedAt) else it
            }
        }

        override suspend fun touch(id: Long, updatedAt: Long) {
            sessions.value = sessions.value.map {
                if (it.id == id) it.copy(updatedAt = updatedAt) else it
            }
        }

        override suspend fun delete(id: Long) {
            sessions.value = sessions.value.filterNot { it.id == id }
            // 对应 Room 外键 ON DELETE CASCADE
            messages.value = messages.value.filterNot { it.sessionId == id }
            val removed = generations.value.filter { it.sessionId == id }.map { it.id }.toSet()
            generations.value = generations.value.filterNot { it.sessionId == id }
            tools.value = tools.value.filterNot { it.generationId in removed }
        }

        override suspend fun bindWorkspace(id: Long, workspaceId: String) {
            sessions.value = sessions.value.map { if (it.id == id) it.copy(workspaceId = workspaceId) else it }
        }
        override suspend fun workspaceReferences(workspaceId: String): Int = sessions.value.count { it.workspaceId == workspaceId }
        override suspend fun replaceWorkspace(workspaceId: String, replacementId: String) {
            sessions.value = sessions.value.map { if (it.workspaceId == workspaceId) it.copy(workspaceId = replacementId) else it }
        }
        override suspend fun updateContext(id: Long, expectedEpoch: Long, epoch: Long, profileId: String, revision: Long): Int {
            var changed=0
            sessions.value=sessions.value.map { if(it.id==id && it.contextEpoch==expectedEpoch) {
                changed++;it.copy(contextEpoch=epoch,profileId=profileId,profileRevision=revision)
            } else it }
            return changed
        }
    }

    val messageDao: MessageDao = object : MessageDao {
        override fun observeMessages(sessionId: Long): Flow<List<MessageEntity>> =
            messages.map { rows ->
                rows.filter { it.sessionId == sessionId }
                    .sortedWith(compareBy({ it.createdAt }, { it.id }))
            }

        override suspend fun countInSession(sessionId: Long): Int =
            messages.value.count { it.sessionId == sessionId }

        override suspend fun insert(message: MessageEntity): Long {
            check(sessions.value.any { it.id == message.sessionId })
            val id = nextMessageId++
            messages.value = messages.value + message.copy(id = id)
            return id
        }
        override suspend fun findById(id: Long) = messages.value.firstOrNull { it.id == id }
        override suspend fun listInSession(sessionId: Long) = messages.value.filter { it.sessionId == sessionId }
            .sortedWith(compareBy({ it.createdAt }, { it.id }))
        override suspend fun updateGeneration(id: Long, sessionId: Long, generationId: String, content: String, status: String): Int {
            var changed = 0
            messages.value = messages.value.map { if (it.id == id && it.sessionId == sessionId &&
                it.generationId == generationId && it.status == "STREAMING") {
                changed++; it.copy(content = content, status = status)
            } else it }
            return changed
        }
        override suspend fun recoverInterrupted(): Int {
            val changed = messages.value.count { it.status == "STREAMING" }
            messages.value = messages.value.map { if (it.status == "STREAMING") it.copy(status = "INTERRUPTED") else it }
            return changed
        }
        override suspend fun interruptSession(sessionId: Long): Int {
            val changed=messages.value.count { it.sessionId==sessionId && it.status=="STREAMING" }
            messages.value=messages.value.map { if(it.sessionId==sessionId && it.status=="STREAMING") it.copy(status="INTERRUPTED") else it }
            return changed
        }
    }

    val generationDao = object : GenerationDao {
        override suspend fun insert(generation: GenerationEntity) {
            check(generations.value.none { it.id == generation.id })
            check(sessions.value.any { it.id == generation.sessionId })
            generations.value += generation
        }
        override suspend fun findById(id: String) = generations.value.firstOrNull { it.id == id }
        override fun observe(sessionId: Long) = generations.map { it.filter { row -> row.sessionId == sessionId }.sortedBy { row -> row.createdAt } }
        override suspend fun updateActive(id: String, assistantId: Long?, status: String, updatedAt: Long, errorKind: String?): Int {
            var changed = 0
            generations.value = generations.value.map { if (it.id == id && it.status == "STREAMING") {
                changed++; it.copy(assistantMessageId = assistantId, status = status, updatedAt = updatedAt, errorKind = errorKind)
            } else it }
            return changed
        }
        override suspend fun recoverInterrupted(updatedAt: Long): Int {
            val changed = generations.value.count { it.status == "STREAMING" }
            generations.value = generations.value.map { if (it.status == "STREAMING") it.copy(status = "INTERRUPTED", updatedAt = updatedAt, errorKind = "PROCESS_INTERRUPTED") else it }
            return changed
        }
        override suspend fun activeWorkspaceReferences(workspaceId: String) = generations.value.count { it.workspaceId == workspaceId && it.status == "STREAMING" }
        override suspend fun replaceWorkspace(workspaceId: String, replacementId: String) {
            generations.value = generations.value.map { if (it.workspaceId == workspaceId) it.copy(workspaceId = replacementId) else it }
        }
        override suspend fun recordTool(tool: ToolRoundEntity) {
            if(tools.value.none { it.generationId==tool.generationId && it.callId==tool.callId }) tools.value += tool
        }
        override fun observeTools(generationId: String) = tools.map { it.filter { row -> row.generationId == generationId }.sortedBy { row -> row.sequence ?: Long.MAX_VALUE } }
        override suspend fun findTool(generationId: String,callId: String)=tools.value.firstOrNull { it.generationId==generationId && it.callId==callId }
        override suspend fun lastToolSequence(generationId: String)=tools.value.filter { it.generationId==generationId }.mapNotNull { it.sequence }.maxOrNull() ?: 0L
        override suspend fun interruptSession(sessionId: Long,updatedAt: Long): Int {
            val changed=generations.value.count { it.sessionId==sessionId && it.status=="STREAMING" }
            generations.value=generations.value.map { if(it.sessionId==sessionId && it.status=="STREAMING")
                it.copy(status="INTERRUPTED",errorKind="CONTEXT_CHANGED",updatedAt=updatedAt) else it }
            return changed
        }
    }

    val generationPersistence = object : GenerationPersistence {
        override val generations: GenerationDao get() = generationDao
        override val attachments:com.example.aiuseagent.data.local.AttachmentDao get()=attachmentDao
        override suspend fun transaction(block: suspend () -> Unit) {
            val oldAttachments=this@FakeDaoStore.attachments.value
            val oldSessions = sessions.value; val oldMessages = messages.value
            val oldGenerations = this@FakeDaoStore.generations.value; val oldTools = tools.value
            val oldSessionId = nextSessionId; val oldMessageId = nextMessageId
            try { block() } catch (failure: Throwable) {
                this@FakeDaoStore.attachments.value=oldAttachments
                sessions.value = oldSessions; messages.value = oldMessages
                this@FakeDaoStore.generations.value = oldGenerations; tools.value = oldTools
                nextSessionId = oldSessionId; nextMessageId = oldMessageId
                throw failure
            }
        }
    }

    fun sessionCount(): Int = sessions.value.size

    fun messageCount(): Int = messages.value.size
}
