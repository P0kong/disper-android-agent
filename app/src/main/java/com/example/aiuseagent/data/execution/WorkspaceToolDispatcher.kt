package com.example.aiuseagent.data.execution

import com.example.aiuseagent.data.SessionContextScope
import com.example.aiuseagent.data.generation.GenerationCoordinator
import com.example.aiuseagent.data.workspace.*
import com.example.aiuseagent.domain.provider.ProviderToolCall
import com.example.aiuseagent.domain.provider.ToolDefinition
import java.security.MessageDigest
import kotlinx.serialization.json.*

/** One trusted route from provider calls to the broker; unavailable backends remain unadvertised. */
class WorkspaceToolDispatcher(
    private val broker: ExecutionBroker,
    private val read: suspend (String,String,String,ToolBudget)->WorkspaceToolResult,
    private val mutations: (SessionContextScope)->WorkspaceMutationService,
    private val facts: suspend (SessionContextScope)->OperationFacts,
    private val mutationsVerified: Boolean=false,
    private val containsSecret: (String)->Boolean={false},
) {
    fun tools(workspaceId: String): List<ToolDefinition> = GenerationCoordinator.FILE_TOOLS +
        if(mutationsVerified && workspaceId==WorkspaceService.DEFAULT_ID) WorkspaceMutationService.TOOLS else emptyList()

    suspend fun execute(generationId: String,scope: SessionContextScope,call: ProviderToolCall,budget: ToolBudget): WorkspaceToolResult {
        if(call.id.length !in 1..160) return rejected("INVALID_ARGUMENT")
        if(scope.mode!="BUILD") return rejected("DENIED")
        if(containsSecret(call.arguments)) return rejected("SECRET_REJECTED")
        if(call.name !in tools(requireNotNull(scope.workspaceId)).map { it.name }) return rejected("UNSUPPORTED")
        val service=if(call.name in WorkspaceMutationService.TOOL_NAMES) mutations(scope) else null
        val prepared=try { service?.prepare(call.name,call.arguments) }
            catch(failure: WorkspaceFailure) { return rejected(failure.code) }
        val effect=prepared?.effect ?: Effect.READ
        val digest=MessageDigest.getInstance("SHA-256").digest((call.name+"\u0000"+call.arguments).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val operation=ExecutionOperation(ApprovalBinding(generationId,call.id,scope,digest),facts(scope).copy(effect=effect),
            prepared?.preview ?: "读取当前工作区任务文件",prepared?.recoveryReference)
        var result: WorkspaceToolResult?=null
        val outcome=broker.executeGuarded(operation) { stillAllowed ->
            val executed=if(prepared!=null) requireNotNull(service).executeChecked(prepared,budget,stillAllowed)
                else read(requireNotNull(scope.workspaceId),call.name,call.arguments,budget)
            result=executed
            val receipt=if(executed.success && prepared!=null) runCatching {
                Json.parseToJsonElement(executed.content).jsonObject["recovery_token"]?.jsonPrimitive?.content?.let { "recovery:$it" }
            }.getOrNull() else executed.errorCode
            ExecutionOutcome(if(executed.success) "APPLIED" else if(executed.errorCode in setOf("PARTIAL","UNKNOWN")) requireNotNull(executed.errorCode) else "FAILED",receipt)
        }
        return result.takeIf { outcome.state in setOf("APPLIED","PARTIAL","FAILED") }
            ?: rejected(outcome.state,if(outcome.state=="UNKNOWN") "状态未知，文件可能已修改；请检查，不会自动重试。"
                else "操作未执行或已有结果不可重放。")
    }
    private fun rejected(code: String,message: String?=null)=WorkspaceToolResult(false,
        buildJsonObject { put("error",code);message?.let { put("message",it) } }.toString(),errorCode=code)
}
