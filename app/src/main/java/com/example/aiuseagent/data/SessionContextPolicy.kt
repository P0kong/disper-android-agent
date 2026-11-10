package com.example.aiuseagent.data

import com.example.aiuseagent.data.local.MessageEntity
import com.example.aiuseagent.data.local.SessionEntity

/** Complete request identity. Opaque protocol state and attachments must use this same scope. */
data class SessionContextScope(
    val sessionId: Long, val mode: String, val workspaceId: String?, val workspaceVersion: Long,
    val contextEpoch: Long, val profileId: String, val profileRevision: Long,
) {
    init {
        require(mode in setOf("CHAT", "BUILD"))
        require((mode == "CHAT" && workspaceId == null) || (mode == "BUILD" && !workspaceId.isNullOrBlank()))
        require(profileId.isNotBlank() && profileRevision >= 0 && contextEpoch >= 0)
    }
}

object SessionContextPolicy {
    fun requiresNewSession(session: SessionEntity, mode: String, workspaceId: String?): Boolean =
        session.mode == "LEGACY" || session.mode != mode || (mode == "BUILD" && session.workspaceId != workspaceId)

    fun matches(session: SessionEntity, scope: SessionContextScope): Boolean =
        session.id == scope.sessionId && session.mode == scope.mode && session.workspaceId == scope.workspaceId &&
            session.contextEpoch == scope.contextEpoch && session.profileId == scope.profileId &&
            session.profileRevision == scope.profileRevision

    fun accepts(row: MessageEntity, scope: SessionContextScope): Boolean =
        row.sessionId == scope.sessionId && row.mode == scope.mode && row.contextEpoch == scope.contextEpoch &&
            row.profileId == scope.profileId && row.profileRevision == scope.profileRevision &&
            !row.isDemo && row.status == "COMPLETED" && row.role in setOf("user", "assistant")
}
