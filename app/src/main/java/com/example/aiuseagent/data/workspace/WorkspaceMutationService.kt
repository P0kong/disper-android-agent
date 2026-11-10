package com.example.aiuseagent.data.workspace

import com.example.aiuseagent.data.execution.*
import com.example.aiuseagent.domain.provider.ToolDefinition
import kotlinx.serialization.json.*

/** Prepared by trusted code before requesting permission; model JSON never supplies effect/scope. */
class PreparedFileOperation internal constructor(val name: String, val effect: Effect,
    internal val arguments: Map<String,String>, val preview: String,
    internal val owner: Any, internal val recoveryTarget: FileRecoveryTarget?,internal val deletion:FrozenDeletion?=null) {
    // Trusted locator only; allocation has no file effect and never comes from model JSON.
    internal val recoveryToken: String?=deletion?.token ?: if(effect==Effect.READ) null else java.util.UUID.randomUUID().toString()
    internal val recoveryReference:String? get()=recoveryToken?.let { if(deletion!=null) "deletion:$it" else "recovery:$it" }
    override fun toString()="PreparedFileOperation(name=$name,effect=$effect)"
}

/** Default private task root only. SAF capabilities need a separate proven adapter. */
class WorkspaceMutationService(private val store: TaskFileStore,
    private val containsSecret: (String)->Boolean = { false }) {
    private val identity=Any()
    fun prepare(name: String, arguments: String): PreparedFileOperation {
        val required=FIELDS[name] ?: fail("UNKNOWN_TOOL")
        if(arguments.toByteArray(Charsets.UTF_8).size>64*1024) fail("INVALID_ARGUMENTS")
        if(containsSecret(arguments)) fail("SENSITIVE_CONTENT")
        val values=parseStrings(arguments)
        if(values.keys!=required.toSet()) fail("INVALID_ARGUMENTS")
        if(values.values.any(containsSecret)) fail("SENSITIVE_CONTENT")
        values["batch_token"]?.let { if(!it.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) fail("RECOVERY_INVALID") }
        values["offset"]?.let { if(!it.matches(Regex("0|[1-9][0-9]?"))) fail("INVALID_ARGUMENTS") }
        values["path"]?.let {
            val last=WorkspaceService.pathSegments(it,false).last()
            if(name!="delete_path") WorkspaceService.checkTextName(last)
        }
        values["expected_hash"]?.let { if(it!="MISSING" && !(name=="restore_file" && it=="DIRECTORY") && !it.matches(Regex("[0-9a-f]{64}"))) fail("INVALID_HASH") }
        for(key in listOf("text","old_text","new_text")) values[key]?.let {
            WorkspaceService.decodeText(it.toByteArray(Charsets.UTF_8))
        }
        values["recovery_token"]?.let { if(!it.matches(Regex("[0-9a-f-]{36}"))) fail("RECOVERY_INVALID") }
        val recoveryTarget=values["recovery_token"]?.let { token ->
            try { store.describeRecovery(token) } catch(failure: FileMutationFailure) { fail(failure.code) }
        }
        recoveryTarget?.let {
            val last=WorkspaceService.pathSegments(it.path,false).last()
            if(!it.directory) WorkspaceService.checkTextName(last)
            if(containsSecret(it.path)) fail("SENSITIVE_CONTENT")
            if(it.afterHash!=values["expected_hash"]) fail("CONFLICT")
        }
        val deletion=if(name=="delete_path") try { store.freezeDeletion(values.getValue("path")) }
            catch(failure:FileMutationFailure) { fail(failure.code) } else null
        deletion?.entries?.forEach {
            if(containsSecret(it.path)) fail("SENSITIVE_CONTENT")
            if(!it.directory) WorkspaceService.checkTextName(WorkspaceService.pathSegments(it.path,false).last())
        }
        val effect=when {
            name in setOf("inspect_text_file","inspect_delete_batch")->Effect.READ
            name in setOf("delete_file","delete_path") || recoveryTarget?.beforeHash=="MISSING"->Effect.DELETE
            else->Effect.WRITE
        }
        val label=when(name) { "inspect_text_file"->"检查文本"; "write_text_file"->"写入文本"
            "patch_text_file"->"替换文本片段"; "delete_file"->"删除文件（保留恢复副本）"; else->"恢复文件" }
        val recoveryNotice=if(name=="restore_file" && effect==Effect.DELETE) "（撤回新建，将删除此文件）" else ""
        val preview=if(deletion!=null) "递归删除：${deletion.target}\n已冻结 ${deletion.entries.size} 项，逐项保留恢复记录；部分成功不会回滚。\n"+
            deletion.entries.joinToString("\n") { if(it.directory) "目录：${it.path}" else "文件：${it.path}" }
            else if(name=="inspect_delete_batch") "只读核查删除批次"
            else "$label$recoveryNotice：${values["path"] ?: recoveryTarget?.path}\n预期状态：${values["expected_hash"] ?: "只读"}"
        // ApprovalRepository has a bounded preview. Never silently truncate a deletion inventory.
        if(preview.length>4096) fail("PREVIEW_LIMIT")
        return PreparedFileOperation(name,effect,values.toMap(),preview,identity,recoveryTarget,deletion)
    }

    suspend fun executeChecked(operation:PreparedFileOperation,budget:ToolBudget,stillAllowed:suspend ()->Boolean):WorkspaceToolResult {
        if(operation.deletion==null) {
            val result=execute(operation,budget)
            // Suppress read observations if scope/revocation changed during the filesystem read.
            return if(operation.effect==Effect.READ && !stillAllowed()) rejected("DENIED") else result
        }
        if(operation.owner!==identity) return rejected("CONFLICT")
        if(!budget.consume(RECEIPT_BUDGET)) return rejected("AGGREGATE_LIMIT")
        val receipt=try { store.deleteFrozenChecked(operation.deletion,stillAllowed) }
            catch(failure:FileMutationFailure) {
                // Only these start-of-batch checks prove that no member effect began.
                // Loss of the durable progress store after earlier members must reach Broker UNKNOWN.
                if(failure.code in setOf("TREE_CHANGED","PLAN_SCOPE_MISMATCH")) return rejected(failure.code)
                throw failure
            }
        val body=buildJsonObject {
            put("batch_token",receipt.token);put("state",receipt.state)
            put("completed",receipt.members.count { it.state=="SUCCEEDED" })
            put("not_executed",receipt.members.count { it.state=="NOT_EXECUTED" })
            put("unknown",receipt.members.count { it.state=="UNKNOWN" })
            receipt.reason?.let { put("reason",it) }
        }.toString()
        return WorkspaceToolResult(receipt.state=="SUCCEEDED",body,body.toByteArray(Charsets.UTF_8).size,
            if(receipt.state=="SUCCEEDED") null else if(receipt.state=="DENIED") receipt.reason else receipt.state)
    }

    /** Called synchronously at the broker's final effect boundary. IO exceptions propagate as UNKNOWN. */
    fun execute(operation: PreparedFileOperation,budget: ToolBudget): WorkspaceToolResult {
        if(operation.owner!==identity) return rejected("CONFLICT")
        if(operation.deletion!=null) return rejected("CHECKED_EXECUTION_REQUIRED")
        val a=operation.arguments
        try {
            if(operation.name=="inspect_delete_batch") {
                val batch=store.inspectDeletion(a.getValue("batch_token"))
                if(containsSecret(batch.target) || batch.members.any { containsSecret(it.entry.path) }) fail("SENSITIVE_CONTENT")
                val offset=a.getValue("offset").toInt()
                if(offset>=batch.members.size) fail("INVALID_ARGUMENTS")
                val page=batch.members.drop(offset).take(10)
                val result=buildJsonObject {
                    put("batch_token",batch.token);put("state",batch.state);put("total",batch.members.size)
                    put("next_offset",if(offset+page.size<batch.members.size) (offset+page.size).toString() else null)
                    put("restore_order","Restore successful directories parent-first, then files. Inspect uncertain members before recovery; never replay the batch.")
                    putJsonArray("members") { page.forEach { member -> add(buildJsonObject {
                        put("path",member.entry.path);put("directory",member.entry.directory);put("state",member.state)
                        if(member.state!="NOT_EXECUTED") put("recovery_token",member.recoveryToken)
                        if(member.state=="SUCCEEDED") put("expected_hash","MISSING")
                    }) } }
                }.toString()
                val bytes=result.toByteArray(Charsets.UTF_8).size
                if(!budget.consume(bytes)) fail("AGGREGATE_LIMIT")
                return WorkspaceToolResult(true,result,bytes)
            }
            if(operation.name=="inspect_text_file") {
                val snapshot=store.inspect(a.getValue("path"))
                val body=snapshot.bytes?.let(WorkspaceService::decodeText)
                if(body!=null && containsSecret(body)) fail("SENSITIVE_CONTENT")
                val result=buildJsonObject { put("sha256",snapshot.hash);put("text",body) }.toString()
                val bytes=result.toByteArray(Charsets.UTF_8).size
                if(!budget.consume(bytes)) fail("AGGREGATE_LIMIT")
                return WorkspaceToolResult(true,result,bytes)
            }
            // Reserve a bounded receipt before touching files. No post-effect budget rejection.
            if(!budget.consume(RECEIPT_BUDGET)) fail("AGGREGATE_LIMIT")
            val receipt=when(operation.name) {
                "write_text_file"->store.write(a.getValue("path"),a.getValue("expected_hash"),a.getValue("text").toByteArray(Charsets.UTF_8),requireNotNull(operation.recoveryToken))
                "patch_text_file"->safePatch(a,requireNotNull(operation.recoveryToken))
                "delete_file"->store.delete(a.getValue("path"),a.getValue("expected_hash"),requireNotNull(operation.recoveryToken))
                "restore_file"->store.restore(a.getValue("recovery_token"),a.getValue("expected_hash"),requireNotNull(operation.recoveryTarget),requireNotNull(operation.recoveryToken))
                else->fail("UNKNOWN_TOOL")
            }
            val result=buildJsonObject {
                put("recovery_token",receipt.token);put("before_hash",receipt.beforeHash);put("after_hash",receipt.afterHash)
            }.toString()
            return WorkspaceToolResult(true,result,result.toByteArray(Charsets.UTF_8).size)
        } catch(failure: FileMutationFailure) {
            // A failed readback is not evidence that the mutation was rolled back.
            if(failure.code=="COMMIT_UNVERIFIED") throw failure
            return rejected(failure.code)
        } catch(failure: WorkspaceFailure) { return rejected(failure.code) }
    }

    private fun safePatch(a: Map<String,String>,recoveryToken: String): FileReceipt {
        val path=a.getValue("path");val expected=a.getValue("expected_hash")
        val snapshot=store.inspect(path)
        if(snapshot.hash!=expected) fail("CONFLICT")
        val text=WorkspaceService.decodeText(snapshot.bytes ?: fail("NOT_FILE"))
        val old=a.getValue("old_text");val at=if(old.isEmpty()) -1 else text.indexOf(old)
        if(at<0 || text.indexOf(old,at+1)>=0) fail("PATCH_AMBIGUOUS")
        val replacement=text.substring(0,at)+a.getValue("new_text")+text.substring(at+old.length)
        if(containsSecret(replacement)) fail("SENSITIVE_CONTENT")
        val bytes=replacement.toByteArray(Charsets.UTF_8)
        WorkspaceService.decodeText(bytes)
        // The store rechecks the same expected hash immediately before committing.
        return store.write(path,expected,bytes,recoveryToken)
    }

    companion object {
        private const val RECEIPT_BUDGET=512
        private val FIELDS=linkedMapOf(
            "inspect_text_file" to listOf("path"),
            "inspect_delete_batch" to listOf("batch_token","offset"),
            "write_text_file" to listOf("path","expected_hash","text"),
            "patch_text_file" to listOf("path","expected_hash","old_text","new_text"),
            "delete_file" to listOf("path","expected_hash"),
            "delete_path" to listOf("path"),
            "restore_file" to listOf("recovery_token","expected_hash"),
        )
        val TOOL_NAMES: Set<String> = FIELDS.keys.toSet()
        val TOOLS: List<ToolDefinition> = FIELDS.map { (name,fields)->
            ToolDefinition(name,when(name) {
                "inspect_text_file"->"Inspect a relative UTF-8 TXT/MD file (8 KiB maximum) and get its exact sha256, or MISSING."
                "inspect_delete_batch"->"Read a deletion receipt and per-member recovery tokens, at most 10 members per page. Start with offset string 0; use next_offset until null. Read-only; does not retry deletion or restore anything."
                "write_text_file"->"Create or replace a relative TXT/MD file, up to 8 KiB, only if expected_hash matches. MISSING means create only."
                "patch_text_file"->"Replace exactly one old_text occurrence in a TXT/MD file after checking expected_hash."
                "delete_file"->"Delete one TXT/MD file after checking expected_hash, retaining an app-private recovery copy. No recursion."
                "delete_path"->"Freeze and delete up to 100 entries including directories in a relative private subtree. Files must be TXT/MD, each at most 8 KiB. Scope changes stop remaining entries; partial results are not rolled back. Use inspect_delete_batch with the returned batch_token for individual recovery tokens."
                else->"Restore a previous file operation using its recovery_token and expected current hash; conflicting files are never overwritten."
            },buildJsonObject {
                put("type","object");put("additionalProperties",false)
                putJsonObject("properties") { fields.forEach { key-> putJsonObject(key) { put("type","string") } } }
                putJsonArray("required") { fields.forEach { add(it) } }
            })
        }
        private fun parseStrings(input: String): Map<String,String> {
            var cursor=0
            fun ws() { while(cursor<input.length && input[cursor] in " \t\r\n") cursor++ }
            fun character(expected: Char) { ws();if(cursor>=input.length || input[cursor++]!=expected) fail("INVALID_ARGUMENTS") }
            fun string(): String {
                ws();val start=cursor
                if(cursor>=input.length || input[cursor++]!='"') fail("INVALID_ARGUMENTS")
                while(cursor<input.length) {
                    when(input[cursor++]) {
                        '\\'-> { if(cursor>=input.length) fail("INVALID_ARGUMENTS");cursor++ }
                        '"'->return try { Json.parseToJsonElement(input.substring(start,cursor)).jsonPrimitive.content }
                            catch(_: Exception) { fail("INVALID_ARGUMENTS") }
                    }
                }
                fail("INVALID_ARGUMENTS")
            }
            val result=linkedMapOf<String,String>()
            character('{');ws()
            if(cursor<input.length && input[cursor]=='}') { cursor++ } else while(true) {
                val key=string();character(':');val value=string()
                if(result.put(key,value)!=null || result.size>4) fail("INVALID_ARGUMENTS")
                ws();if(cursor>=input.length) fail("INVALID_ARGUMENTS")
                when(input[cursor++]) { '}'->break; ','->Unit; else->fail("INVALID_ARGUMENTS") }
            }
            ws();if(cursor!=input.length) fail("INVALID_ARGUMENTS")
            return result
        }
        private fun rejected(code: String)=WorkspaceToolResult(false,buildJsonObject { put("error",code) }.toString(),errorCode=code)
        private fun fail(code: String): Nothing=throw WorkspaceFailure(code)
    }
}
