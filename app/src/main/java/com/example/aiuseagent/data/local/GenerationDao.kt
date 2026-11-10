package com.example.aiuseagent.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface GenerationDao {
    @Insert suspend fun insert(generation: GenerationEntity)
    @Query("SELECT * FROM generations WHERE id = :id")
    suspend fun findById(id: String): GenerationEntity?
    @Query("SELECT * FROM generations WHERE sessionId = :sessionId ORDER BY createdAt ASC")
    fun observe(sessionId: Long): Flow<List<GenerationEntity>>
    @Query("UPDATE generations SET assistantMessageId = :assistantId, status = :status, updatedAt = :updatedAt, errorKind = :errorKind WHERE id = :id AND status = 'STREAMING'")
    suspend fun updateActive(id: String, assistantId: Long?, status: String, updatedAt: Long, errorKind: String?): Int
    @Query("UPDATE generations SET status = 'INTERRUPTED', errorKind = 'PROCESS_INTERRUPTED', updatedAt = :updatedAt WHERE status = 'STREAMING'")
    suspend fun recoverInterrupted(updatedAt: Long): Int
    @Query("SELECT COUNT(*) FROM generations WHERE workspaceId = :workspaceId AND status = 'STREAMING'")
    suspend fun activeWorkspaceReferences(workspaceId: String): Int
    @Query("UPDATE generations SET workspaceId = :replacementId WHERE workspaceId = :workspaceId")
    suspend fun replaceWorkspace(workspaceId: String, replacementId: String)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun recordTool(tool: ToolRoundEntity)
    @Query("SELECT * FROM tool_rounds WHERE generationId = :generationId ORDER BY sequence IS NULL, sequence ASC")
    fun observeTools(generationId: String): Flow<List<ToolRoundEntity>>
    @Query("SELECT * FROM tool_rounds WHERE generationId = :generationId AND callId = :callId")
    suspend fun findTool(generationId: String, callId: String): ToolRoundEntity?
    @Query("SELECT COALESCE(MAX(sequence), 0) FROM tool_rounds WHERE generationId = :generationId")
    suspend fun lastToolSequence(generationId: String): Long
    @Query("UPDATE generations SET status = 'INTERRUPTED', errorKind = 'CONTEXT_CHANGED', updatedAt = :updatedAt WHERE sessionId = :sessionId AND status = 'STREAMING'")
    suspend fun interruptSession(sessionId: Long, updatedAt: Long): Int
}
