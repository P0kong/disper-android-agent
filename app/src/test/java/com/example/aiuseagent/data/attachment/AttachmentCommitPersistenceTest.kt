package com.example.aiuseagent.data.attachment

import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.aiuseagent.data.*
import com.example.aiuseagent.data.local.AppDatabase
import com.example.aiuseagent.domain.attachment.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class AttachmentCommitPersistenceTest {
    private val store=FakeDaoStore()
    private val sessions=SessionRepository(store.sessionDao,store.messageDao,store.generationPersistence)
    private val scope=AttachmentScope("draft",null,"CHAT",0,null,0,"builtin:MOCK",0,0)
    private val handle=AttachmentHandle("c10dd430-4fb6-4cb1-a098-6054e6ddf435","94692707-3c0b-4d15-9070-d3f345ee68b1","a".repeat(64),"text/plain",3)
    @Test fun successfulTransactionPersistsFrozenReceiptAndCompletesCopyReservation()=runTest {
        val frozen=FrozenAttachmentInput(handle,"text:1",listOf("chosen"),"b".repeat(64),true,"budget:1")
        val raw=AttachmentSelectionReceipt.encode(frozen)
        var finished:Long?=-1
        val commit=AttachmentCommit(scope,listOf(AttachmentCommitRecord(handle,"a.txt",raw)),{true}) {finished=it}
        val turn=sessions.prepareGeneration(null,"","MOCK",null,"g",attachments=commit) {}
        assertEquals(raw,store.attachments.value.single().selectionJson)
        assertEquals(turn.sessionId,finished)
    }
    @Test fun failedTransactionReleasesReservationWithoutInventingCommittedReference()=runTest {
        store.failAttachmentInsert=true
        var finished:Long?=-1
        val commit=AttachmentCommit(scope,listOf(AttachmentCommitRecord(handle,"a.txt")),{true}) {finished=it}
        try {sessions.prepareGeneration(null,"text","MOCK",null,"g",attachments=commit) {};fail("Expected rollback")}
        catch(_:IllegalStateException) { }
        assertNull(finished);assertTrue(store.attachments.value.isEmpty())
    }
    @Test fun migrationOnlyAddsNullableFrozenSelectionWithoutRewritingExistingRows() {
        val commands=mutableListOf<String>()
        val db=Proxy.newProxyInstance(SupportSQLiteDatabase::class.java.classLoader,arrayOf(SupportSQLiteDatabase::class.java)) { _,method,args ->
            if(method.name=="execSQL") commands+=args!![0] as String
            null
        } as SupportSQLiteDatabase
        AppDatabase.MIGRATION_3_4.migrate(db)
        assertEquals(listOf("ALTER TABLE attachments ADD COLUMN selectionJson TEXT"),commands)
    }
}
