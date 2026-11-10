package com.example.aiuseagent.data.workspace

import com.example.aiuseagent.data.execution.*
import java.nio.file.Files
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceMutationTest {
    @get:Rule val temp=TemporaryFolder(java.io.File("build/test-task-files").absoluteFile.apply { mkdirs() })
    private val root by lazy { temp.newFolder("root").toPath() }
    private val service by lazy { WorkspaceMutationService(TaskFileStore(root,temp.newFolder("recovery").toPath())) { it.contains("PRIVATE_CANARY") } }
    private fun args(vararg pairs: Pair<String,String>)=buildJsonObject { pairs.forEach { put(it.first,it.second) } }.toString()
    private fun execute(name: String,vararg pairs: Pair<String,String>): WorkspaceToolResult =
        service.execute(service.prepare(name,args(*pairs)),ToolBudget())

    @Test fun inspectedHashControlsWritePatchAndRecoverableDelete() {
        assertTrue(execute("write_text_file","path" to "a.md","expected_hash" to "MISSING","text" to "first").success)
        val inspected=Json.parseToJsonElement(execute("inspect_text_file","path" to "a.md").content).jsonObject
        assertEquals("first",inspected.getValue("text").jsonPrimitive.content)
        val hash=inspected.getValue("sha256").jsonPrimitive.content
        assertTrue(execute("patch_text_file","path" to "a.md","expected_hash" to hash,"old_text" to "first","new_text" to "second").success)
        assertEquals("CONFLICT",execute("write_text_file","path" to "a.md","expected_hash" to hash,"text" to "lost").errorCode)
        val deleted=execute("delete_file","path" to "a.md","expected_hash" to TaskFileStore.hash("second".toByteArray()))
        val token=Json.parseToJsonElement(deleted.content).jsonObject.getValue("recovery_token").jsonPrimitive.content
        assertFalse(Files.exists(root.resolve("a.md")))
        assertTrue(execute("restore_file","recovery_token" to token,"expected_hash" to "MISSING").success)
        assertEquals("second",Files.readString(root.resolve("a.md")))
    }
    @Test fun duplicateExtraNonStringAndEscapedDuplicateArgumentsAreRejectedBeforeEffects() {
        for(arguments in listOf("{\"path\":\"a.md\",\"path\":\"b.md\"}",
            "{\"path\":\"a.md\",\"pa\\u0074h\":\"b.md\"}","{\"path\":false}",
            "{\"path\":\"a.md\",\"unknown\":\"x\"}","{\"path\":\"a.md\",}")) {
            try { service.prepare("inspect_text_file",arguments);fail(arguments) }
            catch(e: WorkspaceFailure) { assertEquals("INVALID_ARGUMENTS",e.code) }
        }
        assertEquals(0,Files.list(root).use { it.count() }.toInt())
    }
    @Test fun secretsTraversalUnsupportedNamesAndLargeInputsNeverMutateTaskFiles() {
        val cases=listOf(
            args("path" to "../escape.md","expected_hash" to "MISSING","text" to "x"),
            args("path" to "data.db","expected_hash" to "MISSING","text" to "x"),
            args("path" to "safe.md","expected_hash" to "MISSING","text" to "PRIVATE_CANARY"),
            args("path" to "safe.md","expected_hash" to "MISSING","text" to "x".repeat(8193)))
        for(arguments in cases) {
            try { service.prepare("write_text_file",arguments);fail() } catch(_: WorkspaceFailure) { }
        }
        assertEquals(0,Files.list(root).use { it.count() }.toInt())
    }
    @Test fun budgetRefusalHappensBeforeWriteAndPreparedOperationBindsTheTarget() {
        val prepared=service.prepare("write_text_file",args("path" to "a.md","expected_hash" to "MISSING","text" to "fixed"))
        assertEquals(Effect.WRITE,prepared.effect)
        assertEquals("AGGREGATE_LIMIT",service.execute(prepared,ToolBudget(0)).errorCode)
        assertFalse(Files.exists(root.resolve("a.md")))
        assertTrue(service.execute(prepared,ToolBudget()).success)
        assertEquals("fixed",Files.readString(root.resolve("a.md")))
        assertFalse(Files.exists(root.resolve("b.md")))
    }
    @Test fun preparedOperationCannotBeTransferredToAnotherTaskRoot() {
        val prepared=service.prepare("write_text_file",args("path" to "a.md","expected_hash" to "MISSING","text" to "belongs-to-A"))
        val otherRoot=temp.newFolder("other").toPath()
        val other=WorkspaceMutationService(TaskFileStore(otherRoot,temp.newFolder("other-recovery").toPath()))
        assertEquals("CONFLICT",other.execute(prepared,ToolBudget()).errorCode)
        assertFalse(Files.exists(otherRoot.resolve("a.md")))
        assertTrue(service.execute(prepared,ToolBudget()).success)
    }
    @Test fun patchCannotAssembleKnownCredentialFromIndividuallyHarmlessFragments() {
        Files.writeString(root.resolve("a.md"),"PRIVATE_x")
        val result=execute("patch_text_file","path" to "a.md","expected_hash" to TaskFileStore.hash("PRIVATE_x".toByteArray()),
            "old_text" to "x","new_text" to "CANARY")
        assertEquals("SENSITIVE_CONTENT",result.errorCode)
        assertEquals("PRIVATE_x",Files.readString(root.resolve("a.md")))
    }
    @Test fun restorePreparationNamesActualPathAndDeletionEffect() {
        val created=execute("write_text_file","path" to "a.md","expected_hash" to "MISSING","text" to "new")
        val token=Json.parseToJsonElement(created.content).jsonObject.getValue("recovery_token").jsonPrimitive.content
        val prepared=service.prepare("restore_file",args("recovery_token" to token,"expected_hash" to TaskFileStore.hash("new".toByteArray())))
        assertEquals(Effect.DELETE,prepared.effect)
        assertTrue(prepared.preview.contains("a.md"))
        assertTrue(service.execute(prepared,ToolBudget()).success)
        assertFalse(Files.exists(root.resolve("a.md")))
    }
    @Test fun restoreRejectsChangedTargetAfterPreparation() {
        val recovery=temp.newFolder("bound-recovery").toPath()
        val local=WorkspaceMutationService(TaskFileStore(root,recovery))
        val created=local.execute(local.prepare("write_text_file",args("path" to "a.md","expected_hash" to "MISSING","text" to "new")),ToolBudget())
        val token=Json.parseToJsonElement(created.content).jsonObject.getValue("recovery_token").jsonPrimitive.content
        val prepared=local.prepare("restore_file",args("recovery_token" to token,"expected_hash" to TaskFileStore.hash("new".toByteArray())))
        Files.writeString(root.resolve("b.md"),"new")
        val metadata=recovery.resolve("$token.meta")
        val altered=java.io.ByteArrayOutputStream()
        java.io.DataInputStream(Files.newInputStream(metadata)).use { input ->
            java.io.DataOutputStream(altered).use { out ->
                out.writeInt(input.readInt());out.writeUTF(input.readUTF());input.readUTF();out.writeUTF("b.md")
                out.writeUTF(input.readUTF());out.writeUTF(input.readUTF())
            }
        }
        Files.write(metadata,altered.toByteArray())
        assertEquals("CONFLICT",local.execute(prepared,ToolBudget()).errorCode)
        assertEquals("new",Files.readString(root.resolve("a.md")))
        assertEquals("new",Files.readString(root.resolve("b.md")))
    }
    @Test fun directoryRecoveryUsesTheSamePreparedScopeAndDeletePermissionForUndo() {
        Files.createDirectory(root.resolve("empty"))
        val store=TaskFileStore(root,temp.newFolder("directory-recovery").toPath())
        val local=WorkspaceMutationService(store)
        val deleted=store.deleteFrozen(store.freezeDeletion("empty")) { true }.members.single()
        val restore=local.prepare("restore_file",args("recovery_token" to deleted.recoveryToken,"expected_hash" to "MISSING"))
        assertEquals(Effect.WRITE,restore.effect)
        val result=local.execute(restore,ToolBudget());assertTrue(result.success)
        assertTrue(Files.isDirectory(root.resolve("empty")))
        val token=Json.parseToJsonElement(result.content).jsonObject.getValue("recovery_token").jsonPrimitive.content
        val undo=local.prepare("restore_file",args("recovery_token" to token,"expected_hash" to "DIRECTORY"))
        assertEquals(Effect.DELETE,undo.effect)
        assertTrue(local.execute(undo,ToolBudget()).success)
        assertFalse(Files.exists(root.resolve("empty")))
    }
}
