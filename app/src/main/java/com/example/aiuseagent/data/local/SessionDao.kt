package com.example.aiuseagent.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY updatedAt DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun findById(id: Long): SessionEntity?

    @Insert
    suspend fun insert(session: SessionEntity): Long

    @Query("UPDATE sessions SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE sessions SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE sessions SET workspaceId = :workspaceId WHERE id = :id")
    suspend fun bindWorkspace(id: Long, workspaceId: String)

    @Query("SELECT COUNT(*) FROM sessions WHERE workspaceId = :workspaceId")
    suspend fun workspaceReferences(workspaceId: String): Int

    @Query("UPDATE sessions SET workspaceId = :replacementId WHERE workspaceId = :workspaceId")
    suspend fun replaceWorkspace(workspaceId: String, replacementId: String)

    @Query("UPDATE sessions SET contextEpoch = :epoch, profileId = :profileId, profileRevision = :revision WHERE id = :id AND contextEpoch = :expectedEpoch")
    suspend fun updateContext(id: Long, expectedEpoch: Long, epoch: Long, profileId: String, revision: Long): Int

    @Query("UPDATE sessions SET permissionMode = :level WHERE id = :id AND mode = 'BUILD' AND :level IN ('VIEW_ONLY','WORKSPACE_EDIT')")
    suspend fun updatePermission(id: Long, level: String): Int
}
