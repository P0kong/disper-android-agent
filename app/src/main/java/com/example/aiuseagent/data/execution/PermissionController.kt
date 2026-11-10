package com.example.aiuseagent.data.execution

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only non-FULL choices are persisted. An instance is owned by the application process. */
class PermissionController(
    private val load: suspend (Long)->String?,
    private val save: suspend (Long,String)->Unit,
    private val invalidateApprovals: ()->Unit,
) {
    private val mutex=Mutex()
    private val states=MutableStateFlow<Map<Long,PermissionSnapshot>>(emptyMap())
    val state=states.asStateFlow()
    private suspend fun loaded(id: Long): PermissionSnapshot {
        require(id>0)
        return states.value[id] ?: PermissionSnapshot(
            if(load(id)=="WORKSPACE_EDIT") PermissionLevel.WORKSPACE_EDIT else PermissionLevel.VIEW_ONLY,0
        ).also { states.value=states.value+(id to it) }
    }
    suspend fun snapshot(id: Long)=mutex.withLock { loaded(id) }
    suspend fun select(id: Long,level: PermissionLevel,fullConfirmed: Boolean): Boolean=mutex.withLock {
        if(level==PermissionLevel.FULL && !fullConfirmed) return@withLock false
        val previous=loaded(id)
        if(previous.level==level) return@withLock true
        if(level!=PermissionLevel.FULL) save(id,level.name)
        invalidateApprovals()
        states.value=states.value+(id to PermissionSnapshot(level,Math.addExact(previous.revision,1)))
        true
    }
}
