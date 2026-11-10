package com.example.aiuseagent.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "generations", foreignKeys = [ForeignKey(entity = SessionEntity::class,
    parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("sessionId")])
data class GenerationEntity(
    @PrimaryKey val id: String,
    val sessionId: Long,
    val userMessageId: Long,
    val assistantMessageId: Long? = null,
    val providerType: String,
    val workspaceId: String,
    val status: String = "STREAMING",
    val createdAt: Long,
    val updatedAt: Long,
    val errorKind: String? = null,
    @ColumnInfo(defaultValue = "'LEGACY'") val mode: String = "CHAT",
    @ColumnInfo(defaultValue = "0") val contextEpoch: Long = 0,
    val profileId: String? = null,
    @ColumnInfo(defaultValue = "0") val profileRevision: Long = 0,
    @ColumnInfo(defaultValue = "0") val workspaceVersion: Long = 0,
) {
    override fun toString(): String = "GenerationEntity(id=$id,sessionId=$sessionId,status=$status)"
}

/** Audit metadata only: neither file bodies nor tool argument JSON are persisted here. */
@Entity(tableName = "tool_rounds", primaryKeys = ["generationId", "callId"],
    foreignKeys = [ForeignKey(entity = GenerationEntity::class, parentColumns = ["id"],
        childColumns = ["generationId"], onDelete = ForeignKey.CASCADE)])
data class ToolRoundEntity(
    val generationId: String,
    val callId: String,
    val name: String,
    val status: String,
    val bytes: Int,
    val durationMs: Long,
    val sequence: Long? = null,
)
