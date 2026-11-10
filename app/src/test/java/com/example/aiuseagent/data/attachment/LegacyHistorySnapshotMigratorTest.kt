package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.data.attachment.parser.TextAttachmentParser
import com.example.aiuseagent.data.local.*
import com.example.aiuseagent.domain.attachment.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LegacyHistorySnapshotMigratorTest {
    @get:Rule val temporary=TemporaryFolder()
    private class Journal(val root:Path):AttachmentOwnershipJournal {
        val values=mutableListOf<OwnedAttachmentObject>()
        override suspend fun allocate(token:String)=error("not used")
        override suspend fun seal(value:OwnedAttachmentObject)=error("not used")
        override suspend fun register(values:List<OwnedAttachmentObject>) {
            values.forEach {assertFalse(Files.exists(root.resolve("${it.token}.blob")))}
            this.values+=values
        }
    }
    private data class Fixture(val root:Path,val store:OwnedAttachmentStore,val journal:Journal,
        var record:HistoryAttachmentRecord,val text:String) {
        var bindCalls=0
        suspend fun bind(expected:HistoryAttachmentRecord,p:PreparedHistoryAttachment):Boolean {
            bindCalls++;assertEquals(record,expected)
            p.objects.forEach {assertNull(store.leases.beginDelete(it.token))}
            record=record.copy(attachment=record.attachment.copy(snapshotToken=p.snapshot.token,
                snapshotSha256=p.snapshot.sha256,snapshotSizeBytes=p.snapshot.sizeBytes),snapshot=p.snapshot,objects=p.objects)
            return true
        }
        fun migrator()=LegacyHistorySnapshotMigrator(store,journal,::bind)
    }
    private suspend fun fixture(partial:Boolean=false):Fixture {
        val root=temporary.newFolder().toPath();val text=if(partial) "a".repeat(200000)+"UNSELECTED_SUFFIX" else "已发送的旧文本\nsecond line"
        val bytes=("\uFEFF"+text).toByteArray()
        val scope=AttachmentScope(UUID.randomUUID().toString(),null,"CHAT",0,null,0,"old",1,0)
        val repo=AttachmentRepository(root,object:AttachmentSource {
            override suspend fun describe(uri:String)=AttachmentSourceInfo("old.txt","text/plain")
            override suspend fun open(uri:String)=ByteArrayInputStream(bytes)
        },{true})
        val h=repo.stage(scope,"content://synthetic/no-longer-available",UUID.randomUUID().toString())
        val parsed=TextAttachmentParser {repo.read(scope,it)}.parse(h,AttachmentLimits())
        repo.parsed(scope,h,parsed)
        val input=repo.freeze(scope,listOf(AttachmentSelection(h.id,h.sha256,"utf8:1",parsed.parts.map {it.id},true)),"historic-budget:1").single()
        val row=AttachmentEntity(h.id,11,22,3,"old",7,"old.txt",h.mimeType,h.sizeBytes,h.localToken,h.sha256,"PREPARED",AttachmentSelectionReceipt.encode(input))
        return Fixture(root,OwnedAttachmentStore(root,AttachmentLeaseRegistry()),Journal(root),HistoryAttachmentRecord(row,
            MessageEntity(22,11,"user","",0,contextEpoch=3,profileId="old",profileRevision=7),null,emptyList()),text)
    }
    private fun released(f:Fixture) {
        (f.journal.values.map {it.token}+f.record.attachment.localToken).forEach {token ->
            assertNotNull(f.store.leases.beginDelete(token)?.also {it.close()})
        }
    }
    @Test fun exactLegacyTextIsMigratedWithoutChangingOriginalReceiptOrSource()=runBlocking<Unit> {
        val f=fixture();val row=f.record.attachment
        val before=Files.readAllBytes(f.root.resolve("${row.localToken}.blob"))
        assertTrue(f.migrator().upgrade(f.record));assertEquals(1,f.bindCalls)
        assertEquals(row.selectionJson,f.record.attachment.selectionJson)
        (HistoryAttachmentReader({f.record},f.store).open(11,22,row.id) as HistoryPreviewResult.Readable).use {
            assertEquals(f.text,it.snapshot.parts.single().text)
            assertEquals("historic-budget:1",it.snapshot.input.modelBudgetRevision)
        }
        assertArrayEquals(before,Files.readAllBytes(f.root.resolve("${row.localToken}.blob")));released(f)
    }
    @Test fun onlyTheHistoricallyConfirmedPrefixCanEnterTheSnapshot()=runBlocking<Unit> {
        val f=fixture(true);assertTrue(f.migrator().upgrade(f.record))
        (HistoryAttachmentReader({f.record},f.store).open(11,22,f.record.attachment.id) as HistoryPreviewResult.Readable).use {
            assertEquals("a".repeat(200000),it.snapshot.parts.single().text)
            assertFalse(HistoryAttachmentSnapshotCodec.encode(it.snapshot).contains("UNSELECTED_SUFFIX"))
        }
        released(f)
    }
    @Test fun unsupportedRevisionDigestOrConfirmationNeverCreatesNewObjects()=runBlocking<Unit> {
        for(kind in listOf("revision","digest","confirmation","receipt")) {
            val f=fixture(true);val row=f.record.attachment
            val receipt=when(kind) {
                "revision"->row.selectionJson!!.replace("utf8:1","utf8:2")
                "digest"->row.selectionJson!!.replace(AttachmentSelectionReceipt.decode(row.selectionJson!!,
                    AttachmentHandle(row.id,row.localToken,row.sha256,row.mimeType,row.sizeBytes)).selectedContentSha256,"0".repeat(64))
                "confirmation"->row.selectionJson!!.replace("\"confirmed\":true","\"confirmed\":false")
                else->null
            }
            f.record=f.record.copy(attachment=row.copy(selectionJson=receipt))
            assertFalse(f.migrator().upgrade(f.record));assertEquals(0,f.bindCalls);assertTrue(f.journal.values.isEmpty());released(f)
        }
    }
    @Test fun sourceReplacementFailsBeforeNewJournalEntries()=runBlocking<Unit> {
        val f=fixture();val path=f.root.resolve("${f.record.attachment.localToken}.blob")
        val bytes=Files.readAllBytes(path);bytes[0]=0;Files.write(path,bytes)
        try {f.migrator().upgrade(f.record);fail("source replacement must be rejected")}
        catch(failure:AttachmentFailure) {assertEquals("OWNED_OBJECT_CORRUPT",failure.code)}
        assertTrue(f.journal.values.isEmpty());released(f)
    }
    @Test fun lostBindingRaceAndUncertainTransactionKeepOnlyJournaledCandidatesAndReleaseLeases()=runBlocking<Unit> {
        for(kind in listOf("race","uncertain","cancel")) {
            val f=fixture();var called=false;var cleanup=false
            val migrator=LegacyHistorySnapshotMigrator(f.store,f.journal,{_,p ->
                called=true;assertTrue(f.journal.values.contains(p.snapshot));assertNull(f.store.leases.beginDelete(p.snapshot.token))
                when(kind) {"uncertain"->error("synthetic uncertain transaction");"cancel"->throw CancellationException("synthetic cancellation");else->false}
            },{cleanup=true})
            val failure=runCatching {assertFalse(migrator.upgrade(f.record))}.exceptionOrNull()
            assertTrue(called);assertTrue(cleanup);assertNull(f.record.snapshot)
            if(kind=="cancel") assertTrue(failure is CancellationException)
            if(kind=="uncertain") assertTrue(failure is IllegalStateException)
            if(kind=="race") assertNull(failure)
            f.journal.values.forEach {assertTrue(Files.exists(f.root.resolve("${it.token}.blob")))};released(f)
        }
    }
    @Test fun readerAuthorizesBeforeMigrationThenRechecksActualMessageBinding()=runBlocking<Unit> {
        val f=fixture();val migrator=f.migrator();var calls=0
        val reader=HistoryAttachmentReader({f.record},f.store,legacyUpgrade={calls++;migrator.upgrade(it)})
        assertEquals(HistoryPreviewResult.Unauthorized,reader.open(12,22,f.record.attachment.id));assertEquals(0,calls)
        (reader.open(11,22,f.record.attachment.id) as HistoryPreviewResult.Readable).close();assertEquals(1,calls);released(f)
        val g=fixture();val raced=HistoryAttachmentReader({g.record},g.store,legacyUpgrade={
            g.migrator().upgrade(it);g.record=g.record.copy(message=g.record.message.copy(role="assistant"));true
        })
        assertEquals(HistoryPreviewResult.Unauthorized,raced.open(11,22,g.record.attachment.id));released(g)
    }
}
