package com.example.aiuseagent.data.execution

import com.example.aiuseagent.data.SessionContextScope
import com.example.aiuseagent.data.workspace.*
import com.example.aiuseagent.domain.provider.ProviderToolCall
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlinx.serialization.json.*

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspaceToolDispatcherTest {
    @get:Rule val temp=TemporaryFolder(java.io.File("build/test-task-files").absoluteFile.apply { mkdirs() })
    private val scope=SessionContextScope(1,"BUILD","default",1,1,"p",1)
    private val call=ProviderToolCall("call","write_text_file","""{"path":"safe.md","expected_hash":"MISSING","text":"created"}""")
    @Test fun successfulDeletionCanBeInspectedThroughDispatcherAndRestoredParentFirst()=runTest {
        val root=temp.newFolder("root").toPath();Files.createDirectories(root.resolve("tree/sub"))
        Files.writeString(root.resolve("tree/sub/a.md"),"retained")
        val service=WorkspaceMutationService(TaskFileStore(root,temp.newFolder("recovery").toPath()))
        val broker=ExecutionBroker(MemoryOperationJournal(),ApprovalRepository(),{PermissionSnapshot(PermissionLevel.FULL,0)},{true},{it.facts})
        val dispatcher=WorkspaceToolDispatcher(broker,{_,_,_,_->error("not a read")},{service},{OperationFacts(Effect.READ)},true)
        val deleted=dispatcher.execute("g",scope,ProviderToolCall("delete","delete_path","""{"path":"tree"}"""),ToolBudget())
        assertTrue(deleted.success)
        val token=Json.parseToJsonElement(deleted.content).jsonObject.getValue("batch_token").jsonPrimitive.content
        val listed=dispatcher.execute("g",scope,ProviderToolCall("inspect","inspect_delete_batch","""{"batch_token":"$token","offset":"0"}"""),ToolBudget())
        assertTrue(listed.content,listed.success)
        val members=Json.parseToJsonElement(listed.content).jsonObject.getValue("members").jsonArray
        for((index,member) in members.reversed().withIndex()) {
            val recovery=member.jsonObject.getValue("recovery_token").jsonPrimitive.content
            val restored=dispatcher.execute("g",scope,ProviderToolCall("restore-$index","restore_file","""{"recovery_token":"$recovery","expected_hash":"MISSING"}"""),ToolBudget())
            assertTrue(restored.content,restored.success)
        }
        assertEquals("retained",Files.readString(root.resolve("tree/sub/a.md")))
    }
    @Test fun batchInspectionIsPagedBudgetedAndHidesSensitiveOrLateScopeData()=runTest {
        val root=temp.newFolder("root").toPath();Files.createDirectory(root.resolve("tree"))
        repeat(12) { Files.writeString(root.resolve("tree/$it.md"),"content") }
        var sensitive=false;var valid=true;var revokeOnRead=false
        val store=TaskFileStore(root,temp.newFolder("recovery").toPath())
        val plan=store.freezeDeletion("tree");store.deleteFrozen(plan) { true }
        val service=WorkspaceMutationService(store) { if(revokeOnRead && it=="tree") valid=false;sensitive && it.endsWith(".md") }
        val broker=ExecutionBroker(MemoryOperationJournal(),ApprovalRepository(),{PermissionSnapshot(PermissionLevel.VIEW_ONLY,0)},{valid},{it.facts})
        val dispatcher=WorkspaceToolDispatcher(broker,{_,_,_,_->error("not a read")},{service},{OperationFacts(Effect.READ)},true)
        fun inspect(id:String,offset:String="0")=ProviderToolCall(id,"inspect_delete_batch","""{"batch_token":"${plan.token}","offset":"$offset"}""")
        val first=dispatcher.execute("g",scope,inspect("first"),ToolBudget())
        assertTrue(first.content,first.success)
        val page=Json.parseToJsonElement(first.content).jsonObject
        assertEquals(10,page.getValue("members").jsonArray.size);assertEquals("10",page.getValue("next_offset").jsonPrimitive.content)
        assertEquals(3,Json.parseToJsonElement(dispatcher.execute("g",scope,inspect("second","10"),ToolBudget()).content).jsonObject.getValue("members").jsonArray.size)
        assertEquals("AGGREGATE_LIMIT",dispatcher.execute("g",scope,inspect("budget"),ToolBudget(0)).errorCode)
        assertEquals("INVALID_ARGUMENTS",dispatcher.execute("g",scope,inspect("invalid","-1"),ToolBudget()).errorCode)
        sensitive=true
        val hidden=dispatcher.execute("g",scope,inspect("secret"),ToolBudget())
        assertEquals("SENSITIVE_CONTENT",hidden.errorCode);assertFalse(hidden.content.contains(".md"))
        sensitive=false;revokeOnRead=true
        val changed=dispatcher.execute("g",scope,inspect("late"),ToolBudget())
        assertEquals("DENIED",changed.errorCode);assertFalse(changed.content.contains(".md"))
        assertFalse(Files.exists(root.resolve("tree")))
    }
    @Test fun lostDurableCompletionStillLinksUnknownToActualTargetAndRecoveryMetadata()=runTest {
        val memory=MemoryOperationJournal()
        val journal=object:OperationJournal by memory {
            override suspend fun transition(id:String,expected:String,state:String,receipt:String?,updatedAt:Long):Boolean {
                if(state=="APPLIED") error("synthetic completion persistence failure")
                return memory.transition(id,expected,state,receipt,updatedAt)
            }
        }
        val root=temp.newFolder("root").toPath();val backup=temp.newFolder("recovery").toPath()
        val store=TaskFileStore(root,backup)
        val service=WorkspaceMutationService(store)
        val broker=ExecutionBroker(journal,ApprovalRepository(),{PermissionSnapshot(PermissionLevel.FULL,0)},{true},{it.facts})
        val dispatcher=WorkspaceToolDispatcher(broker,{_,_,_,_->error("not a read")},{service},{OperationFacts(Effect.READ)},true)
        assertEquals("UNKNOWN",dispatcher.execute("g",scope,call,ToolBudget()).errorCode)
        assertEquals("created",Files.readString(root.resolve("safe.md")))
        val reference=memory.find("g","call")?.receipt
        assertNotNull("UNKNOWN must retain a pre-effect recovery locator",reference)
        val inspection=store.inspectRecovery(requireNotNull(reference).removePrefix("recovery:"))
        assertEquals("safe.md",inspection.target.path)
        assertEquals(TaskFileStore.hash("created".toByteArray()),inspection.currentHash)
        assertTrue(inspection.backupVerified)
        assertEquals("UNKNOWN",dispatcher.execute("g",scope,call,ToolBudget()).errorCode)
        assertEquals(1,Files.list(backup).use { files -> files.filter { it.toString().endsWith(".meta") }.count() }.toInt())
    }
    @Test fun invalidCallIdsAreRejectedBeforePreparingAnyBackend()=runTest {
        val broker=ExecutionBroker(MemoryOperationJournal(),ApprovalRepository(),{PermissionSnapshot(PermissionLevel.FULL,0)},{true},{it.facts})
        var backendRequests=0
        val dispatcher=WorkspaceToolDispatcher(broker,{_,_,_,_->error("must not read")},{backendRequests++;error("must not prepare")},{OperationFacts(Effect.READ)},true)
        for(id in listOf("","x".repeat(161),"x".repeat(256))) {
            assertEquals("INVALID_ARGUMENT",dispatcher.execute("g",scope,call.copy(id=id),ToolBudget()).errorCode)
        }
        assertEquals(0,backendRequests)
    }
    @Test fun viewingWaitsForApprovalAndDuplicateCallCannotRepeatFileMutation()=runTest {
        val approvals=ApprovalRepository { testScheduler.currentTime }
        val broker=ExecutionBroker(MemoryOperationJournal(),approvals,{PermissionSnapshot(PermissionLevel.VIEW_ONLY,0)},{true},{it.facts})
        val root=temp.newFolder("root").toPath()
        val service=WorkspaceMutationService(TaskFileStore(root,temp.newFolder("recovery").toPath()))
        val dispatcher=WorkspaceToolDispatcher(broker,{_,_,_,_->WorkspaceToolResult(true,"read")},{service},{OperationFacts(Effect.READ)},true)
        val result=async { dispatcher.execute("g",scope,call,ToolBudget()) }
        runCurrent();assertFalse(Files.exists(root.resolve("safe.md")))
        assertTrue(approvals.pending.value.single().preview.contains("safe.md"))
        approvals.resolveFromUser(approvals.pending.value.single().id,true)
        assertTrue(result.await().success)
        assertEquals("created",Files.readString(root.resolve("safe.md")))
        Files.writeString(root.resolve("safe.md"),"later change")
        assertFalse(dispatcher.execute("g",scope,call,ToolBudget()).success)
        assertEquals("later change",Files.readString(root.resolve("safe.md")))
    }
    @Test fun deviceGateAndSafScopeNeverAdvertiseOrExecuteUnverifiedMutations()=runTest {
        val broker=ExecutionBroker(MemoryOperationJournal(),ApprovalRepository(),{PermissionSnapshot(PermissionLevel.FULL,0)},{true},{it.facts})
        var serviceRequests=0
        val root=temp.newFolder("root").toPath()
        val service=WorkspaceMutationService(TaskFileStore(root,temp.newFolder("recovery").toPath()))
        val disabled=WorkspaceToolDispatcher(broker,{_,_,_,_->WorkspaceToolResult(true,"read")},{serviceRequests++;service},{OperationFacts(Effect.READ)})
        assertFalse(disabled.tools("default").any { it.name==call.name })
        assertEquals("UNSUPPORTED",disabled.execute("g",scope,call,ToolBudget()).errorCode)
        val enabled=WorkspaceToolDispatcher(broker,{_,_,_,_->WorkspaceToolResult(true,"read")},{serviceRequests++;service},{OperationFacts(Effect.READ)},true)
        assertFalse(enabled.tools("saf-root").any { it.name==call.name })
        assertEquals("UNSUPPORTED",enabled.execute("g",scope.copy(workspaceId="saf-root"),call,ToolBudget()).errorCode)
        assertEquals(0,serviceRequests)
        assertFalse(Files.exists(root.resolve("safe.md")))
    }
    @Test fun revocationAfterApprovalLeavesRealTaskFilesUntouched()=runTest {
        val approvals=ApprovalRepository { testScheduler.currentTime };var valid=true
        val broker=ExecutionBroker(MemoryOperationJournal(),approvals,{PermissionSnapshot(PermissionLevel.VIEW_ONLY,0)},{valid},{it.facts})
        val root=temp.newFolder("root").toPath()
        val service=WorkspaceMutationService(TaskFileStore(root,temp.newFolder("recovery").toPath()))
        val dispatcher=WorkspaceToolDispatcher(broker,{_,_,_,_->WorkspaceToolResult(true,"read")},{service},{OperationFacts(Effect.READ)},true)
        val result=async { dispatcher.execute("g",scope,call,ToolBudget()) }
        runCurrent();valid=false;approvals.resolveFromUser(approvals.pending.value.single().id,true)
        assertFalse(result.await().success)
        assertFalse(Files.exists(root.resolve("safe.md")))
    }
    @Test fun recursiveApprovalNamesFrozenMembersAndRejectsAddedMembersBeforeEffects()=runTest {
        val root=temp.newFolder("root").toPath();Files.createDirectory(root.resolve("tree"))
        Files.write(root.resolve("tree/a.md"),byteArrayOf(1))
        val approvals=ApprovalRepository { testScheduler.currentTime }
        val broker=ExecutionBroker(MemoryOperationJournal(),approvals,{PermissionSnapshot(PermissionLevel.VIEW_ONLY,0)},{true},{it.facts})
        val service=WorkspaceMutationService(TaskFileStore(root,temp.newFolder("recovery").toPath()))
        val dispatcher=WorkspaceToolDispatcher(broker,{_,_,_,_->error("not a read")},{service},{OperationFacts(Effect.READ)},true)
        val result=async { dispatcher.execute("g",scope,ProviderToolCall("tree","delete_path","""{"path":"tree"}"""),ToolBudget()) }
        runCurrent()
        assertEquals(1,approvals.pending.value.size)
        assertTrue(approvals.pending.value.single().preview.contains("tree/a.md"))
        Files.write(root.resolve("tree/new.md"),byteArrayOf(2))
        approvals.resolveFromUser(approvals.pending.value.single().id,true)
        assertEquals("TREE_CHANGED",result.await().errorCode)
        assertTrue(Files.exists(root.resolve("tree/a.md")));assertTrue(Files.exists(root.resolve("tree/new.md")))
    }
    @Test fun recursiveDeleteRechecksRealScopeBetweenMembersAndRetainsPartialLocator()=runTest {
        val root=temp.newFolder("root").toPath();Files.createDirectory(root.resolve("tree"))
        Files.write(root.resolve("tree/a.md"),byteArrayOf(1));Files.write(root.resolve("tree/b.md"),byteArrayOf(2))
        val journal=MemoryOperationJournal()
        val broker=ExecutionBroker(journal,ApprovalRepository(),{PermissionSnapshot(PermissionLevel.FULL,0)},
            {Files.exists(root.resolve("tree/a.md"))},{it.facts})
        val store=TaskFileStore(root,temp.newFolder("recovery").toPath());val service=WorkspaceMutationService(store)
        val dispatcher=WorkspaceToolDispatcher(broker,{_,_,_,_->error("not a read")},{service},{OperationFacts(Effect.READ)},true)
        val result=dispatcher.execute("g",scope,ProviderToolCall("tree","delete_path","""{"path":"tree"}"""),ToolBudget())
        assertFalse(result.success);assertEquals("PARTIAL",result.errorCode)
        assertFalse(Files.exists(root.resolve("tree/a.md")));assertTrue(Files.exists(root.resolve("tree/b.md")))
        val record=journal.find("g","tree")!!
        assertEquals("PARTIAL",record.state);assertTrue(record.receipt!!.startsWith("deletion:"))
        val receipt=store.inspectDeletion(record.receipt!!.removePrefix("deletion:"))
        assertEquals(listOf("SUCCEEDED","NOT_EXECUTED","NOT_EXECUTED"),receipt.members.map { it.state })
    }
    @Test fun recursiveDeleteBudgetAndSensitiveMemberChecksHappenBeforeAnyEffect()=runTest {
        val root=temp.newFolder("root").toPath();Files.createDirectory(root.resolve("tree"))
        Files.write(root.resolve("tree/a.md"),byteArrayOf(1))
        val broker=ExecutionBroker(MemoryOperationJournal(),ApprovalRepository(),{PermissionSnapshot(PermissionLevel.FULL,0)},{true},{it.facts})
        val service=WorkspaceMutationService(TaskFileStore(root,temp.newFolder("recovery").toPath())) { it.contains("CANARY") }
        val dispatcher=WorkspaceToolDispatcher(broker,{_,_,_,_->error("not a read")},{service},{OperationFacts(Effect.READ)},true)
        val call=ProviderToolCall("tree","delete_path","""{"path":"tree"}""")
        assertEquals("AGGREGATE_LIMIT",dispatcher.execute("g",scope,call,ToolBudget(0)).errorCode)
        Files.write(root.resolve("tree/CANARY.md"),byteArrayOf(2))
        assertEquals("SENSITIVE_CONTENT",dispatcher.execute("g",scope,call.copy(id="other"),ToolBudget()).errorCode)
        assertTrue(Files.exists(root.resolve("tree/a.md")));assertTrue(Files.exists(root.resolve("tree/CANARY.md")))
    }
    @Test fun batchReceiptStorageLossAfterOneDeletionIsUnknownRatherThanOrdinaryFailure()=runTest {
        val root=temp.newFolder("root").toPath();Files.createDirectory(root.resolve("tree"))
        Files.write(root.resolve("tree/a.md"),byteArrayOf(1));Files.write(root.resolve("tree/b.md"),byteArrayOf(2))
        val recovery=temp.newFolder("recovery").toPath();val saved=recovery.resolveSibling("retained-recovery")
        val journal=MemoryOperationJournal();var displaced=false
        val broker=ExecutionBroker(journal,ApprovalRepository(),{PermissionSnapshot(PermissionLevel.FULL,0)}, {
            if(!Files.exists(root.resolve("tree/a.md")) && !displaced) {
                Files.move(recovery,saved);Files.write(recovery,byteArrayOf(1));displaced=true
            };true
        },{it.facts})
        val service=WorkspaceMutationService(TaskFileStore(root,recovery))
        val dispatcher=WorkspaceToolDispatcher(broker,{_,_,_,_->error("not a read")},{service},{OperationFacts(Effect.READ)},true)
        val result=dispatcher.execute("g",scope,ProviderToolCall("tree","delete_path","""{"path":"tree"}"""),ToolBudget())
        assertEquals("UNKNOWN",result.errorCode);assertEquals("UNKNOWN",journal.find("g","tree")!!.state)
        assertFalse(Files.exists(root.resolve("tree/a.md")));assertTrue(Files.exists(root.resolve("tree/b.md")))
        assertTrue(Files.isDirectory(saved))
    }
}
