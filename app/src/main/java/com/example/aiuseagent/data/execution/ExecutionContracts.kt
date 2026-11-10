package com.example.aiuseagent.data.execution

import com.example.aiuseagent.data.SessionContextScope

enum class PermissionLevel { VIEW_ONLY, WORKSPACE_EDIT, FULL }
enum class Effect { READ, WRITE, DELETE, COMMAND_READ, COMMAND_WRITE }
enum class GateDecision { ALLOW, APPROVAL_REQUIRED, SYSTEM_GRANT_REQUIRED, DENY, UNSUPPORTED }

/** Produced by the trusted resource resolver, never populated from model claims of safety. */
data class OperationFacts(val effect: Effect,val currentWorkspace: Boolean=true,val androidGranted: Boolean=true,
    val writable: Boolean=true,val protectedResource: Boolean=false,val isolated: Boolean=false,val network: Boolean=false)
data class ApprovalBinding(val generationId: String,val callId: String,val scope: SessionContextScope,val digest: String)
data class PendingApproval(val id: String,val binding: ApprovalBinding,val preview: String,val expiresAt: Long)
data class ExecutionOperation(val binding: ApprovalBinding,val facts: OperationFacts,val preview: String,
    val recoveryReference: String?=null) {
    init { require(recoveryReference==null || recoveryReference.matches(Regex("(?:recovery|deletion):[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) }
}
data class ExecutionOutcome(val state: String,val receipt: String?=null) {
    init { require(state in setOf("APPLIED","PARTIAL","FAILED","DENIED","CANCELLED","UNKNOWN","CONFLICT","UNSUPPORTED","SYSTEM_GRANT_REQUIRED")) }
}

object PermissionGate {
    fun decide(level: PermissionLevel,facts: OperationFacts): GateDecision {
        if(facts.protectedResource) return GateDecision.DENY
        if(!facts.androidGranted) return GateDecision.SYSTEM_GRANT_REQUIRED
        val writes=facts.effect in setOf(Effect.WRITE,Effect.DELETE,Effect.COMMAND_WRITE)
        if(writes && !facts.writable) return GateDecision.DENY
        if(facts.effect in setOf(Effect.COMMAND_READ,Effect.COMMAND_WRITE) && !facts.isolated) return GateDecision.UNSUPPORTED
        if(level==PermissionLevel.FULL) return GateDecision.ALLOW
        if(!facts.currentWorkspace || facts.network || writes && level==PermissionLevel.VIEW_ONLY) return GateDecision.APPROVAL_REQUIRED
        return GateDecision.ALLOW
    }
}
