package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.data.local.*
import com.example.aiuseagent.domain.attachment.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryAttachmentReaderTest {
    @get:Rule val temporary=TemporaryFolder()
    private data class Fixture(val root:Path,val snapshot:HistoryAttachmentSnapshot,val imageBytes:ByteArray?,val store:OwnedAttachmentStore)
    private suspend fun fixture(image:Boolean=false):Fixture {
        val root=temporary.newFolder().toPath()
        val bytes="original owned synthetic file".toByteArray()
        val scope=AttachmentScope(UUID.randomUUID().toString(),null,"CHAT",0,null,0,"historic-profile",1,0)
        val repository=AttachmentRepository(root,object:AttachmentSource {
            override suspend fun describe(uri:String)=AttachmentSourceInfo("synthetic.txt","text/plain",bytes.size.toLong())
            override suspend fun open(uri:String)=ByteArrayInputStream(bytes)
        },{true})
        val handle=repository.stage(scope,"content://synthetic/will-disappear",UUID.randomUUID().toString())
        val imageBytes=if(image) "synthetic normalized image bytes".toByteArray() else null
        val hash=imageBytes?.let {digest(it)}
        val part=if(image) AttachmentPart("image:$hash","selected image",image=handle)
            else AttachmentPart("line:1","line 1",text="confirmed visible text")
        repository.parsed(scope,handle,AttachmentParseResult(handle.sha256,"old-parser:1",listOf(part,
            AttachmentPart("line:2","line 2",text="unselected secret fixture"))))
        val frozen=repository.freeze(scope,listOf(AttachmentSelection(handle.id,handle.sha256,"old-parser:1",listOf(part.id),true)),"old-budget:1").single()
        val saved=if(image) HistorySnapshotPart(part.id,part.source,image=HistoryImageReference(UUID.randomUUID().toString(),hash!!,imageBytes!!.size.toLong(),1,1))
            else HistorySnapshotPart(part.id,part.source,text=part.text)
        return Fixture(root,HistoryAttachmentSnapshot(frozen,listOf(saved)),imageBytes,OwnedAttachmentStore(root,AttachmentLeaseRegistry()))
    }
    private suspend fun prepare(f:Fixture):PreparedHistoryAttachment=f.store.prepare(f.snapshot,
        f.snapshot.parts.single().image?.let {mapOf(it.token to f.imageBytes!!)} ?: emptyMap()) {}
    private fun record(f:Fixture,p:PreparedHistoryAttachment):HistoryAttachmentRecord {
        val h=f.snapshot.input.handle
        return HistoryAttachmentRecord(AttachmentEntity(h.id,11,22,3,"historic-profile",7,"synthetic.txt",h.mimeType,
            h.sizeBytes,h.localToken,h.sha256,"PREPARED",AttachmentSelectionReceipt.encode(f.snapshot.input)),
            MessageEntity(22,11,"user","",0,contextEpoch=3,profileId="historic-profile",profileRevision=7),p.snapshot,p.objects)
    }
    private fun reader(f:Fixture,r:HistoryAttachmentRecord)=HistoryAttachmentReader({r},f.store)
    private suspend fun open(f:Fixture,r:HistoryAttachmentRecord)=reader(f,r).open(11,22,r.attachment.id)
    private fun file(f:Fixture,token:String,suffix:String="blob")=f.root.resolve("$token.$suffix")

    @Test fun reopenPreservesOnlyFrozenContentWithoutSourceUriOrCurrentParser()=runBlocking<Unit> {
        val f=fixture();val p=prepare(f);val r=record(f,p);p.close()
        val reopened=OwnedAttachmentStore(f.root,AttachmentLeaseRegistry())
        val result=HistoryAttachmentReader({r},reopened).open(11,22,r.attachment.id) as HistoryPreviewResult.Readable
        result.use {
            assertEquals(listOf("confirmed visible text"),it.snapshot.parts.map {part->part.text})
            assertEquals("old-parser:1",it.snapshot.input.parserRevision)
            assertEquals("old-budget:1",it.snapshot.input.modelBudgetRevision)
            assertEquals(f.snapshot.input.handle.sha256,digest(Files.readAllBytes(file(f,f.snapshot.input.handle.localToken))))
        }
    }
    @Test fun imageBytesAreExactAndDefensivelyCopied()=runBlocking<Unit> {
        val f=fixture(true);val p=prepare(f);val r=record(f,p);p.close()
        (open(f,r) as HistoryPreviewResult.Readable).use {read ->
            val id=f.snapshot.parts.single().id
            assertArrayEquals(f.imageBytes,read.imageBytes(id))
            read.imageBytes(id)!!.fill(0)
            assertArrayEquals(f.imageBytes,read.imageBytes(id))
        }
    }
    @Test fun crossSessionAndInconsistentMessageOwnerAreRejectedBeforeReadingFiles()=runBlocking<Unit> {
        val f=fixture();val p=prepare(f);val r=record(f,p);p.close()
        Files.delete(file(f,p.snapshot.token))
        assertEquals(HistoryPreviewResult.Unauthorized,reader(f,r).open(12,22,r.attachment.id))
        assertEquals(HistoryPreviewResult.Unauthorized,reader(f,r).open(11,23,r.attachment.id))
        for(message in listOf(r.message.copy(sessionId=12),r.message.copy(role="assistant"),
            r.message.copy(profileRevision=8),r.message.copy(contextEpoch=4))) {
            assertEquals(HistoryPreviewResult.Unauthorized,open(f,r.copy(message=message)))
        }
    }
    @Test fun replacedSnapshotAndSourceAndMarkerAreCorrupt()=runBlocking<Unit> {
        for(target in listOf("snapshot","source","owner")) {
            val f=fixture();val p=prepare(f);val r=record(f,p);p.close()
            val path=when(target) {
                "snapshot"->file(f,p.snapshot.token)
                "source"->file(f,f.snapshot.input.handle.localToken)
                else->file(f,p.snapshot.token,"owner")
            }
            val old=Files.readAllBytes(path);old[0]=(old[0].toInt() xor 1).toByte();Files.write(path,old)
            assertEquals(HistoryPreviewResult.Corrupt,open(f,r))
        }
    }
    @Test fun missingCopiesAndLegacyRowsAreUnavailableWithoutReparsing()=runBlocking<Unit> {
        val f=fixture();val p=prepare(f);val r=record(f,p);p.close()
        assertTrue(open(f,r.copy(snapshot=null,objects=emptyList())) is HistoryPreviewResult.Missing)
        Files.delete(file(f,p.snapshot.token))
        assertTrue(open(f,r) is HistoryPreviewResult.Missing)
    }
    @Test fun previewHoldsEveryOwnedObjectUntilClosed()=runBlocking<Unit> {
        val f=fixture(true);val p=prepare(f);val r=record(f,p);p.close()
        val read=open(f,r) as HistoryPreviewResult.Readable
        r.objects.forEach {assertNull(f.store.leases.beginDelete(it.token))}
        read.close();read.close()
        r.objects.forEach {assertNotNull(f.store.leases.beginDelete(it.token)?.also {permit->permit.close()})}
    }
    @Test fun changedDatabaseBindingDuringReadCannotReturnContentOrLeakLease()=runBlocking<Unit> {
        val f=fixture();val p=prepare(f);val r=record(f,p);p.close();var calls=0
        val read=HistoryAttachmentReader({if(calls++==0) r else null},f.store).open(11,22,r.attachment.id)
        assertEquals(HistoryPreviewResult.Unauthorized,read)
        p.objects.forEach {assertNotNull(f.store.leases.beginDelete(it.token)?.also {permit->permit.close()})}
    }
    @Test fun snapshotCannotReadAnImageAbsentFromDurableObjectReferences()=runBlocking<Unit> {
        val f=fixture(true);val p=prepare(f);val r=record(f,p);p.close()
        val imageToken=f.snapshot.parts.single().image!!.token
        assertEquals(HistoryPreviewResult.Corrupt,open(f,r.copy(objects=r.objects.filter {it.token!=imageToken})))
    }
    @Test fun journalRegistrationPrecedesWritesAndPreparationHoldsSourceAndNewObjects()=runBlocking<Unit> {
        val f=fixture(true);var registered=false
        val images=mapOf(f.snapshot.parts.single().image!!.token to f.imageBytes!!)
        val p=f.store.prepare(f.snapshot,images) {objects ->
            assertTrue(objects.isNotEmpty())
            objects.forEach {assertFalse(Files.exists(file(f,it.token)));assertNull(f.store.leases.beginDelete(it.token))}
            registered=true
        }
        assertTrue(registered)
        p.objects.forEach {assertTrue(Files.exists(file(f,it.token)));assertNull(f.store.leases.beginDelete(it.token))}
        p.close()
    }
    @Test fun failedOrCancelledJournalWritesNoFilesAndReleasesAllLeases()=runBlocking<Unit> {
        for(cancelled in listOf(false,true)) {
            val f=fixture();val before=Files.list(f.root).use {it.toList().toSet()};var allocated=emptyList<OwnedAttachmentObject>()
            try {
                f.store.prepare(f.snapshot,emptyMap()) {allocated=it;if(cancelled) throw CancellationException("synthetic cancellation") else error("synthetic journal failure")}
                fail("preparation must fail")
            } catch(failure:Exception) {assertEquals(cancelled,failure is CancellationException)}
            assertTrue(allocated.isNotEmpty())
            assertEquals(before,Files.list(f.root).use {it.toList().toSet()})
            (allocated.map {it.token}+f.snapshot.input.handle.localToken).forEach {assertNotNull(f.store.leases.beginDelete(it)?.also {permit->permit.close()})}
        }
    }
    @Test fun closingLastPreviewSchedulesCleanupAfterItsMessageWasDeleted()=runBlocking<Unit> {
        val f=fixture();val p=prepare(f);var current:HistoryAttachmentRecord?=record(f,p);p.close()
        val values=p.objects.associateBy {it.token}.toMutableMap()
        val catalog=object:AttachmentCleanupCatalog {
            override suspend fun candidates()=values.keys.toList()
            override suspend fun claim(token:String)=if(current!=null) AttachmentCleanupClaim.Retained else AttachmentCleanupClaim.Deleting(values.getValue(token))
            override suspend fun finish(token:String) {values.remove(token)}
        }
        val collector=AttachmentGarbageCollector(catalog,f.store);val done=CompletableDeferred<CleanupSummary>();var schedules=0
        val read=HistoryAttachmentReader({current},f.store,onUnused={schedules++;launch {done.complete(collector.collectPending())}})
            .open(11,22,f.snapshot.input.handle.id) as HistoryPreviewResult.Readable
        current=null
        assertEquals(CleanupSummary(0,p.objects.size,0),collector.collectPending())
        read.close();read.close()
        assertEquals(CleanupSummary(p.objects.size,0,0),withTimeout(3000) {done.await()})
        assertEquals(1,schedules);assertTrue(values.isEmpty())
    }
    private fun digest(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
}
