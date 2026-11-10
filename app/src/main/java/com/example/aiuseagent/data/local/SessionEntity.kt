package com.example.aiuseagent.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

/**
 * 会话表。本阶段的会话/消息全部是本地数据，不涉及任何网络或模型调用。
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    // null is a legacy, unbound session; resolve the default only at runtime.
    val workspaceId: String? = null,
    @ColumnInfo(defaultValue = "'LEGACY'") val mode: String = "CHAT",
    @ColumnInfo(defaultValue = "0") val contextEpoch: Long = 0,
    val profileId: String? = null,
    @ColumnInfo(defaultValue = "0") val profileRevision: Long = 0,
    @ColumnInfo(defaultValue = "'VIEW_ONLY'") val permissionMode: String = "VIEW_ONLY",
)
