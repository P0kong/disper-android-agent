package com.example.aiuseagent.data

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiuseagent.AppContainer
import com.example.aiuseagent.data.execution.*
import com.example.aiuseagent.data.local.*
import com.example.aiuseagent.domain.provider.ProviderType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real AppContainer broker/resolvers, with a separate synthetic Room database and files/preferences. */
@RunWith(AndroidJUnit4::class)
class ProductionApprovalScopeTest {
    @Test fun realRevocationBetweenBatchMembersPersistsPartialAndStopsRemainingEffects()=runBlocking {
        for(change in listOf("permission","profile","workspace","stop")) fixture { container,context,db ->
            val seeded=seed(container,db)
            val sessionId=seeded.binding.scope.sessionId
            container.permissions.select(sessionId,PermissionLevel.FULL,true)
            val files=context.filesDir.canonicalFile.toPath()
            val root=files.resolve("workspaces/default")
            java.nio.file.Files.createDirectory(root.resolve("tree"))
            java.nio.file.Files.write(root.resolve("tree/a.md"),byteArrayOf(1))
            java.nio.file.Files.write(root.resolve("tree/b.md"),byteArrayOf(2))
            val store=TaskFileStore(root,files.resolve("workspace-recovery/default"),seeded.binding.scope.workspaceVersion)
            val plan=store.freezeDeletion("tree")
            val operation=seeded.copy(facts=OperationFacts(Effect.DELETE),recoveryReference="deletion:${plan.token}")
            var changed=false
            val outcome=container.executionBroker.executeGuarded(operation) { stillAllowed ->
                val receipt=store.deleteFrozenChecked(plan) {
                    if(!java.nio.file.Files.exists(root.resolve("tree/a.md")) && !changed) {
                        changed=true
                        when(change) {
                            "permission"->container.permissions.select(sessionId,PermissionLevel.VIEW_ONLY,false)
                            "profile"->container.providerRepository.selectActive(ProviderType.OPENAI_COMPATIBLE)
                            "workspace"->db.openHelper.writableDatabase.execSQL("UPDATE sessions SET workspaceId='synthetic-other' WHERE id=?",arrayOf(sessionId))
                            else->db.openHelper.writableDatabase.execSQL("UPDATE generations SET status='CANCELLED' WHERE id=?",arrayOf(operation.binding.generationId))
                        }
                    }
                    stillAllowed()
                }
                assertEquals("PARTIAL",receipt.state)
                ExecutionOutcome("PARTIAL")
            }
            assertEquals("PARTIAL",outcome.state);assertTrue(changed)
            assertFalse(java.nio.file.Files.exists(root.resolve("tree/a.md")))
            assertTrue(java.nio.file.Files.exists(root.resolve("tree/b.md")))
            val record=RoomOperationJournal(db.operationJournalDao()).find(operation.binding.generationId,"call")!!
            assertEquals("PARTIAL",record.state);assertEquals("deletion:${plan.token}",record.receipt)
            if(change=="permission") {
                val reviewed=container.operationRecoveryInspector.review(sessionId).single()
                assertEquals("OBSERVED",reviewed.code)
                assertEquals(listOf("SUCCEEDED","NOT_EXECUTED","NOT_EXECUTED"),reviewed.batch!!.members.map { it.state })
            }
        }
    }
    private suspend fun fixture(block:suspend (AppContainer,Context,AppDatabase)->Unit) {
        val base=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(base.cacheDir.canonicalFile,"synthetic-appcontainer-${UUID.randomUUID()}").apply {check(mkdir())}
        val ownFiles=File(root,"files").apply {check(mkdir())}
        val names=mutableSetOf<String>()
        val context=object:ContextWrapper(base) {
            override fun getApplicationContext():Context=this
            override fun getFilesDir():File=ownFiles
            override fun getCacheDir():File=root
            override fun getSharedPreferences(name:String,mode:Int):SharedPreferences {
                val own="${root.name}-$name";names.add(own)
                return base.getSharedPreferences(own,mode)
            }
        }
        val db=Room.inMemoryDatabaseBuilder(base,AppDatabase::class.java).build()
        val owner=SupervisorJob();val scope=CoroutineScope(owner+Dispatchers.Main.immediate)
        try {
            val container=AppContainer(context,databaseFactory={db},externalScope=scope)
            // Startup recovery must finish before this test creates an active generation.
            withTimeout(5000){container.generationCoordinator.state.first {it.ready}}
            container.workspaceService.refresh()
            assertFalse(container.workspaceTools.tools("default").any {it.name=="write_text_file"})
            block(container,context,db)
        } finally {
            owner.cancelAndJoin();db.close()
            names.forEach {base.deleteSharedPreferences(it)}
            check(root.canonicalFile.parentFile==base.cacheDir.canonicalFile)
            check(root.deleteRecursively())
        }
    }
    private suspend fun seed(container:AppContainer,db:AppDatabase,reference:String?=null):ExecutionOperation {
        val profile=container.providerRepository.prepare()
        val version=container.workspaceService.version("default")
        val session=db.sessionDao().insert(SessionEntity(title="synthetic",createdAt=1,updatedAt=1,workspaceId="default",mode="BUILD",
            contextEpoch=1,profileId=profile.profileId,profileRevision=profile.profileRevision))
        val generation="synthetic-${UUID.randomUUID()}"
        db.generationDao().insert(GenerationEntity(generation,session,1,providerType="MOCK",workspaceId="default",createdAt=1,updatedAt=1,
            mode="BUILD",contextEpoch=1,profileId=profile.profileId,profileRevision=profile.profileRevision,workspaceVersion=version))
        return ExecutionOperation(ApprovalBinding(generation,"call",SessionContextScope(session,"BUILD","default",version,1,profile.profileId,profile.profileRevision),"synthetic-digest"),
            OperationFacts(Effect.WRITE),"synthetic file",reference)
    }
    @Test fun realScopeChecksRejectChangesAfterApprovedRunningReservation()=runBlocking {
        for(change in listOf("workspace","profile","stop")) fixture { container,context,db -> coroutineScope {
            val operation=seed(container,db)
            val statement=when(change) {
                "workspace" -> "UPDATE sessions SET workspaceId='synthetic-other' WHERE id=NEW.sessionId;"
                "profile" -> "UPDATE sessions SET profileRevision=profileRevision+1 WHERE id=NEW.sessionId;"
                else -> "UPDATE generations SET status='CANCELLED' WHERE id=NEW.generationId;"
            }
            // Deterministically inject the concurrent change at the real durable RUNNING boundary.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER synthetic_scope_change AFTER UPDATE OF state ON operation_journal WHEN NEW.state='RUNNING' BEGIN $statement END")
            val target=File(context.filesDir,"must-not-exist.txt")
            val request=async {container.executionBroker.execute(operation){target.writeText("unexpected");ExecutionOutcome("APPLIED")}}
            val pending=withTimeout(5000){container.approvals.pending.first {it.isNotEmpty()}.single()}
            assertFalse(target.exists())
            assertTrue(container.approvals.resolveFromUser(pending.id,true))
            assertNotEquals("APPLIED",request.await().state)
            assertFalse(target.exists())
            assertFalse(container.approvals.resolveFromUser(pending.id,true))
        }}
    }
    @Test fun actualProviderSwitchInvalidatesWaitingRequestAndLateApproval()=runBlocking {
        fixture {container,context,db -> coroutineScope {
            val operation=seed(container,db);val target=File(context.filesDir,"untouched.txt")
            val request=async {container.executionBroker.execute(operation){target.writeText("unexpected");ExecutionOutcome("APPLIED")}}
            val pending=withTimeout(5000){container.approvals.pending.first {it.isNotEmpty()}.single()}
            container.providerRepository.selectActive(if(container.providerRepository.activeType()==ProviderType.MOCK)ProviderType.OPENAI_COMPATIBLE else ProviderType.MOCK)
            container.approvals.resolveFromUser(pending.id,true)
            assertNotEquals("APPLIED",request.await().state);assertFalse(target.exists())
            assertFalse(container.approvals.resolveFromUser(pending.id,true))
        }}
    }
    @Test fun realRoomUnknownReviewRetainsTargetBackupAndNeverReplays()=runBlocking {
        fixture {container,context,db ->
            val token=UUID.randomUUID().toString()
            val operation=seed(container,db,"recovery:$token")
            container.permissions.select(operation.binding.scope.sessionId,PermissionLevel.FULL,true)
            val files=context.filesDir.canonicalFile.toPath()
            val store=TaskFileStore(files.resolve("workspaces/default"),files.resolve("workspace-recovery/default"),operation.binding.scope.workspaceVersion)
            val outcome=container.executionBroker.execute(operation) {
                store.write("lost.txt","MISSING","synthetic".toByteArray(),token)
                throw java.io.IOException("synthetic lost response")
            }
            assertEquals("UNKNOWN",outcome.state)
            assertEquals("synthetic",File(context.filesDir,"workspaces/default/lost.txt").readText())
            container.executionBroker.recover()
            val reviewed=container.operationRecoveryInspector.review(operation.binding.scope.sessionId).single()
            assertEquals("OBSERVED",reviewed.code);assertEquals("lost.txt",reviewed.inspection!!.target.path)
            assertTrue(reviewed.inspection!!.backupVerified)
            assertEquals("UNKNOWN",container.executionBroker.execute(operation){fail("No replay");ExecutionOutcome("APPLIED")}.state)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use {assertFalse(it.moveToFirst())}
        }
    }
}
