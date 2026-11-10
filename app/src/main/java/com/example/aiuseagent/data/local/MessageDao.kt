package com.example.aiuseagent.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    fun observeMessages(sessionId: Long): Flow<List<MessageEntity>>

    @Query("SELECT COUNT(*) FROM messages WHERE sessionId = :sessionId")
    suspend fun countInSession(sessionId: Long): Int

    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun findById(id: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY createdAt ASC, id ASC")
    suspend fun listInSession(sessionId: Long): List<MessageEntity>

    @Query("UPDATE messages SET content = :content, status = :status WHERE id = :id AND sessionId = :sessionId AND generationId = :generationId AND status = 'STREAMING'")
    suspend fun updateGeneration(id: Long, sessionId: Long, generationId: String, content: String, status: String): Int

    @Query("UPDATE messages SET status = 'INTERRUPTED' WHERE status = 'STREAMING'")
    suspend fun recoverInterrupted(): Int
    @Query("UPDATE messages SET status = 'INTERRUPTED' WHERE sessionId = :sessionId AND status = 'STREAMING'")
    suspend fun interruptSession(sessionId: Long): Int
}
