package com.example.aiuseagent.data.execution

import com.example.aiuseagent.data.SessionContextScope
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RecoveryInspectionTest {
    @get:Rule val temp=TemporaryFolder(java.io.File("build/test-task-files").absoluteFile.apply { mkdirs() })
    @Test fun interruptionAfterRestoreReservationKeepsBothRecoveryDirectionsInspectable() {
        val root=temp.newFolder("root").toPath();val backup=temp.newFolder("backup").toPath()
        val store=TaskFileStore(root,backup,afterRestoreReservation={throw java.io.IOException("synthetic interruption")})
        store.write("a.txt","MISSING","before".toByteArray())
        val changed=store.write("a.txt",store.inspect("a.txt").hash,"after".toByteArray())
        val next=UUID.randomUUID().toString()
        try {store.restore(changed.token,changed.afterHash,recoveryToken=next);fail("interruption required")}
        catch(_:java.io.IOException){}
        val reopened=TaskFileStore(root,backup)
        assertTrue(reopened.inspectRecovery(changed.token).consumed)
        val pending=reopened.inspectRecovery(next)
        assertEquals("a.txt",pending.target.path)
        assertTrue(pending.backupVerified)
        assertEquals(changed.afterHash,pending.currentHash)
        assertEquals("after",Files.readString(root.resolve("a.txt")))
    }

    @Test fun unknownRetainsPreEffectRecoveryReferenceAndDuplicateCannotReplay()=runTest {
        val journal=MemoryOperationJournal()
        val scope=SessionContextScope(1,"BUILD","default",1,1,"p",1)
        val ref="recovery:${UUID.randomUUID()}"
        val operation=ExecutionOperation(ApprovalBinding("g","call",scope,"digest"),OperationFacts(Effect.WRITE),"synthetic",ref)
        val broker=ExecutionBroker(journal,ApprovalRepository(),{PermissionSnapshot(PermissionLevel.FULL,0)},{true},{it.facts})
        var effects=0
        val result=broker.execute(operation) {
            assertEquals(ref,journal.find("g","call")?.receipt)
            effects++
            error("synthetic lost response")
        }
        assertEquals("UNKNOWN",result.state)
        assertEquals(ref,journal.find("g","call")?.receipt)
        broker.recover()
        assertEquals("UNKNOWN",broker.execute(operation){effects++;ExecutionOutcome("APPLIED")}.state)
        assertEquals(1,effects)
    }

    @Test fun reservedTokenConnectsMetadataAndConsumedRestoreRemainsInspectable() {
        val root=temp.newFolder("root").toPath();val backup=temp.newFolder("backup").toPath()
        val store=TaskFileStore(root,backup)
        store.write("a.txt","MISSING","before".toByteArray())
        val before=store.inspect("a.txt").hash
        val token=UUID.randomUUID().toString()
        val written=store.write("a.txt",before,"after".toByteArray(),token)
        assertEquals(token,written.token)
        val inspected=store.inspectRecovery(token)
        assertEquals("a.txt",inspected.target.path)
        assertTrue(inspected.backupVerified)
        assertFalse(inspected.consumed)
        assertEquals(written.afterHash,inspected.currentHash)
        val followup=UUID.randomUUID().toString()
        val restored=store.restore(token,written.afterHash,recoveryToken=followup)
        val reopened=TaskFileStore(root,backup)
        val consumed=reopened.inspectRecovery(token)
        assertTrue(consumed.consumed)
        assertEquals(followup,consumed.followupToken)
        assertEquals(restored.afterHash,consumed.currentHash)
        try { reopened.restore(token,written.afterHash);fail("Consumed tokens remain non-replayable") }
        catch(e:FileMutationFailure){assertEquals("RECOVERY_INVALID",e.code)}
        assertEquals("before",Files.readString(root.resolve("a.txt")))
    }

    @Test fun interruptedLegacyReservationAndCorruptBackupCanBeInspectedWithoutRestoring() {
        val root=temp.newFolder("root").toPath();val backup=temp.newFolder("backup").toPath()
        val store=TaskFileStore(root,backup)
        store.write("a.txt","MISSING","before".toByteArray())
        val removed=store.delete("a.txt",store.inspect("a.txt").hash)
        Files.createFile(backup.resolve("${removed.token}.used"))
        var inspected=store.inspectRecovery(removed.token)
        assertTrue(inspected.consumed);assertNull(inspected.followupToken)
        assertTrue(inspected.backupVerified);assertEquals("MISSING",inspected.currentHash)
        Files.writeString(backup.resolve("${removed.token}.body"),"corrupt")
        inspected=store.inspectRecovery(removed.token)
        assertFalse(inspected.backupVerified)
        assertEquals("MISSING",store.inspect("a.txt").hash)
    }

    @Test fun reservedTokenCannotBeReusedAndInspectionCannotCrossWorkspaceRevisions() {
        val root=temp.newFolder("root").toPath();val backup=temp.newFolder("backup").toPath()
        val store=TaskFileStore(root,backup,3);val token=UUID.randomUUID().toString()
        store.write("a.txt","MISSING","first".toByteArray(),token)
        try {store.write("b.txt","MISSING","second".toByteArray(),token);fail("No token reuse")}
        catch(_:Exception){}
        assertEquals("MISSING",store.inspect("b.txt").hash)
        try {TaskFileStore(root,backup,4).inspectRecovery(token);fail("No cross-revision inspection")}
        catch(e:FileMutationFailure){assertEquals("RECOVERY_ROOT_MISMATCH",e.code)}
    }
}
