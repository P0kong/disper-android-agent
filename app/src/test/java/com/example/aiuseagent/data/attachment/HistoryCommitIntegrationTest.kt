package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.data.attachment.parser.NormalizedAttachmentImage
import com.example.aiuseagent.domain.attachment.*
import com.example.aiuseagent.domain.provider.ProviderContentPart
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryCommitIntegrationTest {
    @get:Rule val temporary=TemporaryFolder()
    private class Journal(val root:Path):AttachmentOwnershipJournal {
        val allocated=mutableSetOf<String>();val sealed=mutableMapOf<String,OwnedAttachmentObject>()
        var failAllocate=false;var failRegister=false
        override suspend fun allocate(token:String) {
            check(!Files.exists(root.resolve("$token.owner")))
            if(failAllocate) error("synthetic allocation failed")
            allocated+=token
        }
        override suspend fun seal(value:OwnedAttachmentObject) {
            check(value.token in allocated);check(!Files.exists(root.resolve("${value.token}.blob")))
            sealed[value.token]=value
        }
        override suspend fun register(values:List<OwnedAttachmentObject>) {
            if(failRegister) error("synthetic journal failed")
            values.forEach {check(!Files.exists(root.resolve("${it.token}.owner")));sealed[it.token]=it}
        }
    }
    private data class Fixture(val root:Path,val journal:Journal,val owned:OwnedAttachmentStore,val repo:AttachmentRepository,val scope:AttachmentScope)
    private fun fixture():Fixture {
        val root=temporary.newFolder().toPath();val journal=Journal(root);val owned=OwnedAttachmentStore(root,AttachmentLeaseRegistry())
        val scope=AttachmentScope(UUID.randomUUID().toString(),null,"CHAT",0,null,0,"profile",1,0)
        val repository=AttachmentRepository(root,object:AttachmentSource {
            override suspend fun describe(uri:String)=AttachmentSourceInfo("synthetic.txt","text/plain")
            override suspend fun open(uri:String)=ByteArrayInputStream("original source".toByteArray())
        },{true},ownedStore=owned,ownership=journal)
        return Fixture(root,journal,owned,repository,scope)
    }
    private suspend fun frozen(f:Fixture,image:NormalizedAttachmentImage?=null):FrozenAttachmentInput {
        val h=f.repo.stage(f.scope,"content://synthetic/local",UUID.randomUUID().toString())
        val chosen=if(image==null) AttachmentPart("selected","first",text="selected exact text")
            else AttachmentPart("image:${image.sha256}","first image",image=h)
        f.repo.parsed(f.scope,h,AttachmentParseResult(h.sha256,"fixture:1",listOf(chosen,AttachmentPart("omitted","second",text="never selected"))))
        return f.repo.freeze(f.scope,listOf(AttachmentSelection(h.id,h.sha256,"fixture:1",listOf(chosen.id),true)),"budget:1").single()
    }
    private suspend fun assemble(f:Fixture,input:FrozenAttachmentInput,image:NormalizedAttachmentImage?=null)=
        AttachmentRequestAssembler(f.repo,"budget:1") {_,_->requireNotNull(image)}.assemble(f.scope,listOf(input),"")

    @Test fun sourceAllocationAndSealingAreDurableBeforePublicationAndDraftHoldsLease()=runBlocking<Unit> {
        val f=fixture();val input=frozen(f);val token=input.handle.localToken
        assertTrue(token in f.journal.allocated);assertEquals(input.handle.sha256,f.journal.sealed[token]?.sha256)
        assertNull(f.owned.leases.beginDelete(token))
        f.repo.remove(f.scope,input.handle.id)
        assertTrue(Files.exists(f.root.resolve("$token.blob")))
        assertNotNull(f.owned.leases.beginDelete(token)?.also {it.close()})
    }
    @Test fun allocationFailureNeverWritesAnUnjournaledSource()=runBlocking<Unit> {
        val f=fixture();f.journal.failAllocate=true
        try {frozen(f);fail("allocation must fail")} catch(_:AttachmentFailure) {}
        assertTrue(Files.list(f.root).use {it.count()==0L})
    }
    @Test fun assemblerSnapshotUsesOnlyTheSameFrozenTextAsTheRequest()=runBlocking<Unit> {
        val f=fixture();val input=frozen(f);val prepared=assemble(f,input)
        val saved=prepared.history.single().snapshot
        assertEquals(input.selectedContentSha256,saved.input.selectedContentSha256)
        assertEquals(prepared.parts.filterIsInstance<ProviderContentPart.Text>().map {it.text},saved.parts.map {it.text})
        assertFalse(HistoryAttachmentSnapshotCodec.encode(saved).contains("never selected"))
    }
    @Test fun normalizedImageIsReusedInSnapshotWithoutASecondDecode()=runBlocking<Unit> {
        val f=fixture();val bytes="synthetic normalized PNG payload".toByteArray()
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        val image=NormalizedAttachmentImage(bytes,2,3,hash);val input=frozen(f,image);var calls=0
        val prepared=AttachmentRequestAssembler(f.repo,"budget:1") {_,_->calls++;image}.assemble(f.scope,listOf(input),"")
        val material=prepared.history.single();val ref=material.snapshot.parts.single().image!!
        assertEquals(1,calls);assertEquals(hash,ref.sha256)
        assertEquals(prepared.parts.filterIsInstance<ProviderContentPart.Image>().single().sha256,ref.sha256)
        assertArrayEquals(bytes,material.images().getValue(ref.token))
        material.images().getValue(ref.token).fill(0)
        assertArrayEquals(bytes,material.images().getValue(ref.token))
    }
    @Test fun commitContainsReadableCopiesAndRetainsSendLeaseBeyondDatabaseSettlement()=runBlocking<Unit> {
        val f=fixture();val input=frozen(f);val prepared=assemble(f,input)
        val commit=f.repo.prepareCommit(f.scope,listOf(input),prepared);val record=commit.records.single()
        val saved=requireNotNull(record.snapshot)
        assertEquals(record.objects.map {it.token}.toSet(),f.journal.sealed.keys)
        assertEquals("selected exact text",HistoryAttachmentSnapshotCodec.decode(f.owned.read(saved,HistoryAttachmentSnapshotCodec.MAX_BYTES.toLong()).toString(Charsets.UTF_8)).parts.single().text)
        commit.finish(11)
        record.objects.forEach {assertNull(f.owned.leases.beginDelete(it.token))}
        commit.releaseSending();commit.releaseSending()
        record.objects.forEach {assertNotNull(f.owned.leases.beginDelete(it.token)?.also {permit->permit.close()})}
        assertTrue(Files.exists(f.root.resolve("${input.handle.localToken}.blob")))
    }
    @Test fun failedSnapshotRegistrationDoesNotConsumeDraftOrLeakNewLease()=runBlocking<Unit> {
        val f=fixture();val input=frozen(f);val prepared=assemble(f,input);f.journal.failRegister=true
        try {f.repo.prepareCommit(f.scope,listOf(input),prepared);fail("journal must fail")} catch(_:Exception) {}
        assertEquals(AttachmentState.READY,f.repo.state.value.single().state)
        f.journal.failRegister=false
        val commit=f.repo.prepareCommit(f.scope,listOf(input),prepared)
        commit.finish(null);commit.releaseSending()
        val source=input.handle.localToken
        assertNull(f.owned.leases.beginDelete(source))
        commit.records.single().objects.filter {it.token!=source}.forEach {assertNotNull(f.owned.leases.beginDelete(it.token)?.also {permit->permit.close()})}
    }
    @Test fun completedCommitSourceReadCannotRaceAnExclusiveDeletionClaim()=runBlocking<Unit> {
        val f=fixture();val input=frozen(f);val commit=f.repo.prepareCommit(f.scope,listOf(input),assemble(f,input))
        commit.finish(11);commit.releaseSending()
        requireNotNull(f.owned.leases.beginDelete(input.handle.localToken)).use {
            try {f.repo.read(f.scope.copy(sessionId=11),input.handle);fail("read must not overlap deletion")}
            catch(failure:AttachmentFailure) {assertEquals("OBJECT_BUSY",failure.code)}
        }
    }
}
