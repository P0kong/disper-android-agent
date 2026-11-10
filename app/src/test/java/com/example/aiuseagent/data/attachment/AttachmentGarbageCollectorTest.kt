package com.example.aiuseagent.data.attachment

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentGarbageCollectorTest {
    @get:Rule val temporary=TemporaryFolder()
    private class Catalog:AttachmentCleanupCatalog {
        val objects=linkedMapOf<String,OwnedAttachmentObject>()
        val references=mutableMapOf<String,Int>()
        val deleting=mutableSetOf<String>()
        var uncertain=false;var failFinish=false
        override suspend fun candidates()=objects.keys.toList()
        override suspend fun claim(token:String):AttachmentCleanupClaim {
            if(uncertain) error("synthetic transaction uncertainty")
            if((references[token] ?: 0)>0) return AttachmentCleanupClaim.Retained
            deleting+=token
            return AttachmentCleanupClaim.Deleting(objects.getValue(token))
        }
        override suspend fun finish(token:String) {
            if(failFinish) {failFinish=false;error("synthetic interruption after file deletion")}
            check(token in deleting);objects.remove(token);deleting.remove(token)
        }
    }
    private data class Fixture(val root:Path,val catalog:Catalog,val store:OwnedAttachmentStore,val value:OwnedAttachmentObject) {
        val collector get()=AttachmentGarbageCollector(catalog,store)
        fun path(suffix:String)=root.resolve("${value.token}.$suffix")
    }
    private fun fixture():Fixture {
        val root=temporary.newFolder().toPath();val bytes="synthetic owned data".toByteArray();val token=UUID.randomUUID().toString()
        val hash=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
        val value=OwnedAttachmentObject(token,hash,bytes.size.toLong());val catalog=Catalog()
        catalog.objects[token]=value
        Files.write(root.resolve("$token.blob"),bytes)
        Files.write(root.resolve("$token.owner"),"attachment-v1\n$token\n".toByteArray())
        return Fixture(root,catalog,OwnedAttachmentStore(root,AttachmentLeaseRegistry()),value)
    }
    @Test fun twoDurableReferencesRetainTheObjectUntilBothAreRemoved()=runBlocking<Unit> {
        val f=fixture()
        for(count in listOf(2,1)) {
            f.catalog.references[f.value.token]=count
            assertEquals(CleanupSummary(0,1,0),f.collector.collectPending())
            assertTrue(Files.exists(f.path("blob")));assertTrue(f.catalog.deleting.isEmpty())
        }
        f.catalog.references.clear()
        assertEquals(CleanupSummary(1,0,0),f.collector.collectPending())
        assertFalse(Files.exists(f.path("blob")));assertFalse(Files.exists(f.path("owner")))
    }
    @Test fun independentReadAndSendLeasesBothBlockDeletion()=runBlocking<Unit> {
        val f=fixture();val read=f.store.leases.acquire(listOf(f.value.token));val send=f.store.leases.acquire(listOf(f.value.token))
        assertEquals(CleanupSummary(0,1,0),f.collector.collectPending())
        read.close();read.close()
        assertEquals(CleanupSummary(0,1,0),f.collector.collectPending())
        send.close();assertEquals(CleanupSummary(1,0,0),f.collector.collectPending())
    }
    @Test fun restartedCollectorFinishesDurableCandidateAndRepeatedCollectionIsIdempotent()=runBlocking<Unit> {
        val f=fixture()
        val restarted=AttachmentGarbageCollector(f.catalog,OwnedAttachmentStore(f.root,AttachmentLeaseRegistry()))
        assertEquals(CleanupSummary(1,0,0),restarted.collectPending())
        assertEquals(CleanupSummary(0,0,0),restarted.collectPending())
        assertTrue(f.catalog.objects.isEmpty())
    }
    @Test fun uncertainReferenceTransactionNeverDeletesFiles()=runBlocking<Unit> {
        val f=fixture();f.catalog.uncertain=true
        assertEquals(CleanupSummary(0,0,1),f.collector.collectPending())
        assertTrue(Files.exists(f.path("blob")));assertTrue(Files.exists(f.path("owner")))
        f.catalog.uncertain=false
        assertEquals(CleanupSummary(1,0,0),f.collector.collectPending())
    }
    @Test fun fileDeletionBeforeCompletionReceiptCanBeRetriedAfterRestart()=runBlocking<Unit> {
        val f=fixture();f.catalog.failFinish=true
        assertEquals(CleanupSummary(0,0,1),f.collector.collectPending())
        assertFalse(Files.exists(f.path("blob")));assertTrue(f.value.token in f.catalog.deleting)
        val restarted=AttachmentGarbageCollector(f.catalog,OwnedAttachmentStore(f.root,AttachmentLeaseRegistry()))
        assertEquals(CleanupSummary(1,0,0),restarted.collectPending())
        assertTrue(f.catalog.objects.isEmpty())
    }
    @Test fun interruptedDeletionWithOnlyOwnerMarkerRemainingCompletes()=runBlocking<Unit> {
        val f=fixture();f.catalog.deleting+=f.value.token;Files.delete(f.path("blob"))
        assertEquals(CleanupSummary(1,0,0),f.collector.collectPending())
        assertFalse(Files.exists(f.path("owner")))
    }
    @Test fun alteredContentOrOwnerMarkerIsKeptForReview()=runBlocking<Unit> {
        for(suffix in listOf("blob","owner")) {
            val f=fixture();val bytes=Files.readAllBytes(f.path(suffix));bytes[0]=(bytes[0].toInt() xor 1).toByte();Files.write(f.path(suffix),bytes)
            assertEquals(CleanupSummary(0,0,1),f.collector.collectPending())
            assertTrue(Files.exists(f.path("blob")));assertTrue(Files.exists(f.path("owner")))
        }
    }
    @Test fun exactCompletedPartBeforeAtomicRenameCanBeCollectedButUnknownPartialCannot()=runBlocking<Unit> {
        val complete=fixture();Files.move(complete.path("blob"),complete.path("part"))
        assertEquals(CleanupSummary(1,0,0),complete.collector.collectPending())
        val partial=fixture();Files.move(partial.path("blob"),partial.path("part"));Files.write(partial.path("part"),byteArrayOf(1))
        assertEquals(CleanupSummary(0,0,1),partial.collector.collectPending())
        assertTrue(Files.exists(partial.path("part")))
    }
    @Test fun unregisteredFilesAndExternalOrWorkspacePathsAreNeverDeletionCandidates()=runBlocking<Unit> {
        val f=fixture();val unrelated=f.root.resolve("unregistered.blob");Files.write(unrelated,byteArrayOf(7))
        val workspace=temporary.newFolder("workspace").toPath().resolve("keep.txt");Files.write(workspace,byteArrayOf(8))
        f.catalog.objects["../workspace/keep.txt"]=f.value.copy(token="../workspace/keep.txt")
        assertEquals(CleanupSummary(1,0,1),f.collector.collectPending())
        assertArrayEquals(byteArrayOf(7),Files.readAllBytes(unrelated));assertArrayEquals(byteArrayOf(8),Files.readAllBytes(workspace))
    }
}
