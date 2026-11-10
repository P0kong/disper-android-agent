package com.example.aiuseagent.data.workspace

import androidx.room.withTransaction
import com.example.aiuseagent.data.local.AppDatabase
import com.example.aiuseagent.data.local.WorkspaceEntity

interface WorkspaceRegistry {
    suspend fun list(): List<WorkspaceEntity>
    suspend fun add(workspace: WorkspaceEntity)
    suspend fun sessionExists(id: Long): Boolean
    suspend fun bind(sessionId: Long, workspaceId: String)
    suspend fun references(id: String): Int
    suspend fun activeReferences(id: String): Int
    suspend fun removeReplacing(id: String, replacementId: String)
}

class RoomWorkspaceRegistry(private val database: AppDatabase) : WorkspaceRegistry {
    override suspend fun list() = database.workspaceDao().list()
    override suspend fun add(workspace: WorkspaceEntity) = database.workspaceDao().insert(workspace)
    override suspend fun sessionExists(id: Long) = database.sessionDao().findById(id) != null
    override suspend fun bind(sessionId: Long, workspaceId: String) = database.withTransaction {
        val session=requireNotNull(database.sessionDao().findById(sessionId))
        check(session.mode=="BUILD")
        check(session.workspaceId==workspaceId || database.messageDao().countInSession(sessionId)==0)
        check(workspaceId == "default" || database.workspaceDao().findById(workspaceId) != null)
        database.sessionDao().bindWorkspace(sessionId, workspaceId)
    }
    override suspend fun references(id: String) = database.sessionDao().workspaceReferences(id)
    override suspend fun activeReferences(id: String) = database.generationDao().activeWorkspaceReferences(id)
    override suspend fun removeReplacing(id: String, replacementId: String) = database.withTransaction {
        check(database.generationDao().activeWorkspaceReferences(id) == 0)
        check(replacementId == "default" || database.workspaceDao().findById(replacementId) != null)
        // Historical roots are immutable. The UI may open a new BUILD session on replacementId;
        // retained sessions with this removed root fail availability checks until explicitly changed.
        database.workspaceDao().delete(id)
    }
}
