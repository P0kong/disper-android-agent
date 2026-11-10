package com.example.aiuseagent.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiuseagent.data.execution.*
import com.example.aiuseagent.data.local.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Dedicated synthetic database/files only; reopening models recovery, not a physical power cut. */
@RunWith(AndroidJUnit4::class)
class OperationJournalDeviceTest {
    @Test fun partialBatchRemainsListedForInspectionAfterRoomReopen()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="synthetic-partial-${UUID.randomUUID()}"
        fun open()=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        var db=open()
        try {
            val session=db.sessionDao().insert(SessionEntity(title="synthetic",createdAt=1,updatedAt=1,workspaceId="default",mode="BUILD"))
            db.generationDao().insert(GenerationEntity("g",session,1,providerType="MOCK",workspaceId="default",createdAt=1,updatedAt=1,mode="BUILD"))
            val scope=SessionContextScope(session,"BUILD","default",1,1,"p",1)
            val reference="deletion:${UUID.randomUUID()}"
            val journal=RoomOperationJournal(db.operationJournalDao())
            assertTrue(journal.insert(JournalRecord("partial",ApprovalBinding("g","partial",scope,"digest"),"DELETE","WAITING",reference,1,1)))
            assertTrue(journal.transition("partial","WAITING","RUNNING",reference,2))
            assertTrue(journal.transition("partial","RUNNING","PARTIAL",reference,3))
            assertTrue(journal.insert(JournalRecord("successful-delete",ApprovalBinding("g","successful-delete",scope,"digest"),"DELETE","WAITING",reference,1,1)))
            assertTrue(journal.transition("successful-delete","WAITING","RUNNING",reference,2))
            assertTrue(journal.transition("successful-delete","RUNNING","APPLIED",reference,3))
            db.close();db=open()
            val reopened=RoomOperationJournal(db.operationJournalDao());reopened.recover(4)
            val rows=reopened.uncertain(session)
            assertEquals(2,rows.size)
            val partial=rows.single { it.state=="PARTIAL" }
            assertEquals(reference,partial.receipt)
            assertEquals(reference,rows.single { it.state=="APPLIED" }.receipt)
            val broker=ExecutionBroker(reopened,ApprovalRepository(),{PermissionSnapshot(PermissionLevel.FULL,0)},{true},{it.facts})
            val operation=ExecutionOperation(partial.binding,OperationFacts(Effect.DELETE),"synthetic",reference)
            assertEquals("PARTIAL",broker.execute(operation) { fail("partial calls must not replay");ExecutionOutcome("APPLIED") }.state)
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally { db.close();check(context.deleteDatabase(name)) }
    }
    @Test fun receiptLossAndAbandonedPartialWriteStayUnknownAfterRoomReopen()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="synthetic-journal-${UUID.randomUUID()}"
        val cache=context.cacheDir.canonicalFile
        val folder=File(cache,name).apply { check(mkdir()) }
        fun open()=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        var db=open()
        try {
            val session=db.sessionDao().insert(SessionEntity(title="synthetic",createdAt=1,updatedAt=1,workspaceId="default",mode="BUILD"))
            db.generationDao().insert(GenerationEntity("g",session,1,providerType="MOCK",workspaceId="default",createdAt=1,updatedAt=1,mode="BUILD"))
            val scope=SessionContextScope(session,"BUILD","default",1,1,"p",1)
            fun operation(id: String)=ExecutionOperation(ApprovalBinding("g",id,scope,"synthetic-digest"),OperationFacts(Effect.WRITE),"Synthetic write")
            fun broker(journal: OperationJournal)=ExecutionBroker(journal,ApprovalRepository(),{PermissionSnapshot(PermissionLevel.FULL,0)},{true},{it.facts})
            val completed=File(folder,"completed.txt")
            val journal=RoomOperationJournal(db.operationJournalDao())
            assertEquals("UNKNOWN",broker(journal).execute(operation("lost-receipt")) {
                completed.writeText("committed");throw java.io.IOException("synthetic receipt loss")
            }.state)
            val partial=File(folder,"partial.txt")
            assertTrue(journal.insert(JournalRecord("partial-op",operation("partial").binding,"WRITE","RUNNING",null,1,1)))
            partial.writeText("partial")
            db.close();db=open()
            val reopened=RoomOperationJournal(db.operationJournalDao())
            val recovered=broker(reopened);recovered.recover()
            for(id in listOf("lost-receipt","partial")) {
                assertEquals("UNKNOWN",reopened.find("g",id)!!.state)
                assertEquals("UNKNOWN",recovered.execute(operation(id)) { fail("must not replay");ExecutionOutcome("APPLIED") }.state)
            }
            assertEquals("committed",completed.readText());assertEquals("partial",partial.readText())
            db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
        } finally {
            db.close();check(context.deleteDatabase(name))
            check(folder.canonicalFile.parentFile==cache);check(folder.deleteRecursively())
        }
    }

    @Test fun cancelledApprovalHasNoFileEffectAndPersistsAcrossReopen()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="synthetic-approval-${UUID.randomUUID()}"
        val cache=context.cacheDir.canonicalFile
        val folder=File(cache,name).apply { check(mkdir()) }
        fun open()=Room.databaseBuilder(context,AppDatabase::class.java,name).build()
        var db=open()
        try {
            val session=db.sessionDao().insert(SessionEntity(title="synthetic",createdAt=1,updatedAt=1,workspaceId="default",mode="BUILD"))
            db.generationDao().insert(GenerationEntity("g",session,1,providerType="MOCK",workspaceId="default",createdAt=1,updatedAt=1,mode="BUILD"))
            val scope=SessionContextScope(session,"BUILD","default",1,1,"p",1)
            val approvals=ApprovalRepository()
            val broker=ExecutionBroker(RoomOperationJournal(db.operationJournalDao()),approvals,{PermissionSnapshot(PermissionLevel.VIEW_ONLY,0)},{true},{it.facts})
            val target=File(folder,"untouched.txt")
            val operation=ExecutionOperation(ApprovalBinding("g","cancel",scope,"synthetic-digest"),OperationFacts(Effect.WRITE),"Synthetic write")
            val request=launch { broker.execute(operation) { target.writeText("unexpected");ExecutionOutcome("APPLIED") } }
            val approval=withTimeout(5000) { approvals.pending.first { it.isNotEmpty() }.single() }
            assertFalse(target.exists());request.cancelAndJoin()
            assertFalse(approvals.resolveFromUser(approval.id,true));assertFalse(target.exists())
            db.close();db=open()
            assertEquals("CANCELLED",RoomOperationJournal(db.operationJournalDao()).find("g","cancel")!!.state)
        } finally {
            db.close();check(context.deleteDatabase(name))
            check(folder.canonicalFile.parentFile==cache);check(folder.deleteRecursively())
        }
    }
}
