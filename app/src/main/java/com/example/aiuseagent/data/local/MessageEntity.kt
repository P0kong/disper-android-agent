package com.example.aiuseagent.data.local

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 消息表。role 取值目前只有 "user" / "assistant"；
 * isDemo = true 表示这条消息是本地演示数据，**不是**模型返回。
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sessionId: Long,
    val role: String,
    val content: String,
    val createdAt: Long,
    val isDemo: Boolean = false,
    @ColumnInfo(defaultValue = "'COMPLETED'") val status: String = "COMPLETED",
    val providerType: String? = null,
    val generationId: String? = null,
    val replyToMessageId: Long? = null,
    @ColumnInfo(defaultValue = "'LEGACY'") val mode: String = "LEGACY",
    @ColumnInfo(defaultValue = "0") val contextEpoch: Long = 0,
    val profileId: String? = null,
    @ColumnInfo(defaultValue = "0") val profileRevision: Long = 0,
)
