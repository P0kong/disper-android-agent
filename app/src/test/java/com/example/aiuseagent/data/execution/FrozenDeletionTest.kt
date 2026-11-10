package com.example.aiuseagent.data.execution

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** These assertions catch scope growth, stale approval and mutable inventories before deletion. */
class FrozenDeletionTest {
    @get:Rule val temp=TemporaryFolder(java.io.File("build/test-task-files").absoluteFile.apply { mkdirs() })

    @Test fun freezesEveryMemberInChildFirstOrderWithoutChangingFiles() {
        val root=temp.newFolder("root").toPath()
        Files.createDirectories(root.resolve("tree/nested"))
        Files.write(root.resolve("tree/a.txt"),"A".toByteArray())
        Files.write(root.resolve("tree/nested/b.md"),"B".toByteArray())
        val store=TaskFileStore(root,temp.newFolder("recovery").toPath())
        val plan=store.freezeDeletion("tree")
        assertEquals(listOf("tree/nested/b.md","tree/a.txt","tree/nested","tree"),plan.entries.map { it.path })
        assertEquals(listOf(false,false,true,true),plan.entries.map { it.directory })
        assertEquals("559aead08264d5795d3909718cdd05abd49572e84fe55590eef31a88a08fdffd",plan.entries[1].hash)
        assertEquals("A",String(Files.readAllBytes(root.resolve("tree/a.txt"))))
        try { (plan.entries as MutableList).clear();fail("approval inventory must be immutable") }
        catch(_: UnsupportedOperationException) {}
        store.verifyDeletion(plan)
    }

    @Test fun hundredIncludesDirectoriesAndDoesNotSilentlyTruncate() {
        val root=temp.newFolder("root").toPath();Files.createDirectory(root.resolve("tree"))
        repeat(99) { Files.write(root.resolve("tree/$it.txt"),byteArrayOf(1)) }
        val store=TaskFileStore(root,temp.newFolder("recovery").toPath())
        assertEquals(100,store.freezeDeletion("tree").entries.size)
        Files.write(root.resolve("tree/extra.txt"),byteArrayOf(1))
        rejects("BATCH_LIMIT") { store.freezeDeletion("tree") }
        assertTrue(Files.exists(root.resolve("tree/extra.txt")))
    }

    @Test fun newMemberChangedContentAndRemovedMemberInvalidateTheWholeApproval() {
        val root=temp.newFolder("root").toPath();Files.createDirectory(root.resolve("tree"))
        Files.write(root.resolve("tree/a.txt"),"A".toByteArray())
        val store=TaskFileStore(root,temp.newFolder("recovery").toPath())
        val plan=store.freezeDeletion("tree")
        Files.write(root.resolve("tree/new.txt"),"new".toByteArray())
        rejects("TREE_CHANGED") { store.verifyDeletion(plan) }
        Files.delete(root.resolve("tree/new.txt"))
        Files.write(root.resolve("tree/a.txt"),"B".toByteArray())
        rejects("TREE_CHANGED") { store.verifyDeletion(plan) }
        Files.delete(root.resolve("tree/a.txt"))
        rejects("TREE_CHANGED") { store.verifyDeletion(plan) }
        assertTrue(Files.isDirectory(root.resolve("tree")))
    }

    @Test fun aPlanCannotCrossAStoreOrWorkspaceRevision() {
        val root=temp.newFolder("root").toPath();Files.write(root.resolve("a.txt"),byteArrayOf(1))
        val recovery=temp.newFolder("recovery").toPath()
        val plan=TaskFileStore(root,recovery).freezeDeletion("a.txt")
        rejects("PLAN_SCOPE_MISMATCH") { TaskFileStore(root,recovery,1).verifyDeletion(plan) }
        rejects("PLAN_SCOPE_MISMATCH") { TaskFileStore(root,recovery).verifyDeletion(plan) }
        assertTrue(Files.exists(root.resolve("a.txt")))
    }

    @Test fun invalidRootAndUnsupportedMemberFailBeforeAnyEffect() {
        val root=temp.newFolder("root").toPath();Files.createDirectory(root.resolve("tree"))
        Files.write(root.resolve("tree/large.txt"),ByteArray(TaskFileStore.MAX_BYTES+1))
        val store=TaskFileStore(root,temp.newFolder("recovery").toPath())
        rejects("INVALID_PATH") { store.freezeDeletion("") }
        rejects("INVALID_PATH") { store.freezeDeletion("../root") }
        rejects("FILE_TOO_LARGE") { store.freezeDeletion("tree") }
        rejects("NOT_FOUND") { store.freezeDeletion("missing") }
        assertEquals((TaskFileStore.MAX_BYTES+1).toLong(),Files.size(root.resolve("tree/large.txt")))
    }

    private fun rejects(code:String,action:()->Unit) {
        try { action();fail("expected $code") } catch(failure:FileMutationFailure) { assertEquals(code,failure.code) }
    }

    @Test fun deletesOnlyFrozenMembersAndKeepsDurablePerMemberReceipts() {
        val root=temp.newFolder("root").toPath();Files.createDirectory(root.resolve("tree"))
        Files.write(root.resolve("tree/a.txt"),"A".toByteArray())
        val recovery=temp.newFolder("recovery").toPath();val store=TaskFileStore(root,recovery)
        val plan=store.freezeDeletion("tree")
        val receipt=store.deleteFrozen(plan) { true }
        assertEquals("SUCCEEDED",receipt.state)
        assertEquals(listOf("SUCCEEDED","SUCCEEDED"),receipt.members.map { it.state })
        assertFalse(Files.exists(root.resolve("tree")))
        assertEquals(receipt,TaskFileStore(root,recovery).inspectDeletion(plan.token))
        // Reuse must return its old receipt, even if a fresh target has appeared.
        Files.createDirectory(root.resolve("tree"));Files.write(root.resolve("tree/new.txt"),byteArrayOf(3))
        assertEquals(receipt,store.deleteFrozen(plan) { true })
        assertTrue(Files.exists(root.resolve("tree/new.txt")))
        store.restore(receipt.members[0].recoveryToken,"MISSING")
        assertEquals("A",String(Files.readAllBytes(root.resolve("tree/a.txt"))))
    }

    @Test fun membershipChangeAfterOneSuccessStopsAndRecordsRemainingWithoutDeletingThem() {
        val root=temp.newFolder("root").toPath();Files.createDirectory(root.resolve("tree"))
        Files.write(root.resolve("tree/a.txt"),byteArrayOf(1));Files.write(root.resolve("tree/b.txt"),byteArrayOf(2))
        val store=TaskFileStore(root,temp.newFolder("recovery").toPath());val plan=store.freezeDeletion("tree")
        var calls=0
        val result=store.deleteFrozen(plan) {
            if(++calls==2) Files.write(root.resolve("tree/new.txt"),byteArrayOf(3))
            true
        }
        assertEquals("PARTIAL",result.state);assertEquals("TREE_CHANGED",result.reason)
        assertEquals(listOf("SUCCEEDED","NOT_EXECUTED","NOT_EXECUTED"),result.members.map { it.state })
        assertFalse(Files.exists(root.resolve("tree/a.txt")))
        assertTrue(Files.exists(root.resolve("tree/b.txt")));assertTrue(Files.exists(root.resolve("tree/new.txt")))
        assertEquals(result,store.inspectDeletion(plan.token))
    }

    @Test fun permissionRevocationStopsAfterCompletedMemberAndNeverRollsItBack() {
        val root=temp.newFolder("root").toPath();Files.createDirectory(root.resolve("tree"))
        Files.write(root.resolve("tree/a.txt"),byteArrayOf(1));Files.write(root.resolve("tree/b.txt"),byteArrayOf(2))
        val store=TaskFileStore(root,temp.newFolder("recovery").toPath());val plan=store.freezeDeletion("tree")
        var calls=0
        val result=store.deleteFrozen(plan) { ++calls==1 }
        assertEquals("PARTIAL",result.state);assertEquals("SCOPE_CHANGED",result.reason)
        assertEquals(listOf("SUCCEEDED","NOT_EXECUTED","NOT_EXECUTED"),result.members.map { it.state })
        assertFalse(Files.exists(root.resolve("tree/a.txt")));assertTrue(Files.exists(root.resolve("tree/b.txt")))
        assertEquals(result,store.deleteFrozen(plan) { true })
        assertTrue(Files.exists(root.resolve("tree/b.txt")))
    }

    @Test fun deletedDirectoriesRestoreBeforeFilesAndCannotUndoOverNewContents() {
        val root=temp.newFolder("root").toPath();Files.createDirectories(root.resolve("tree/nested"))
        Files.write(root.resolve("tree/nested/a.txt"),"A".toByteArray())
        val recovery=temp.newFolder("recovery").toPath();val store=TaskFileStore(root,recovery)
        val result=store.deleteFrozen(store.freezeDeletion("tree")) { true }
        val file=result.members.first()
        val fileReview=store.inspectRecovery(file.recoveryToken)
        assertTrue(fileReview.backupVerified);assertEquals("MISSING",fileReview.currentHash)
        val rootToken=result.members.last().recoveryToken
        assertTrue(store.inspectRecovery(rootToken).backupVerified)
        val restoredRoot=store.restore(rootToken,"MISSING")
        store.restore(result.members[1].recoveryToken,"MISSING")
        store.restore(file.recoveryToken,"MISSING")
        assertEquals("A",String(Files.readAllBytes(root.resolve("tree/nested/a.txt"))))
        rejects("CONFLICT") { store.restore(restoredRoot.token,"DIRECTORY") }
        assertTrue(Files.exists(root.resolve("tree/nested/a.txt")))
        rejects("RECOVERY_INVALID") { store.restore(rootToken,"MISSING") }
    }

    @Test fun directoryRestoreInterruptionRetainsItsFollowupLocatorWithoutReplaying() {
        val root=temp.newFolder("root").toPath();Files.createDirectory(root.resolve("empty"))
        val recovery=temp.newFolder("recovery").toPath()
        val store=TaskFileStore(root,recovery)
        val deleted=store.deleteFrozen(store.freezeDeletion("empty")) { true }.members.single()
        val interrupted=TaskFileStore(root,recovery,afterRestoreReservation={throw java.io.IOException("simulated interruption")})
        try { interrupted.restore(deleted.recoveryToken,"MISSING");fail("must interrupt after durable reservation") }
        catch(_:java.io.IOException) {}
        val observed=TaskFileStore(root,recovery).inspectRecovery(deleted.recoveryToken)
        assertTrue(observed.consumed);assertNotNull(observed.followupToken)
        assertEquals("MISSING",observed.currentHash)
        assertEquals("empty",store.inspectRecovery(observed.followupToken!!).target.path)
        rejects("RECOVERY_INVALID") { store.restore(deleted.recoveryToken,"MISSING") }
        assertFalse(Files.exists(root.resolve("empty")))
    }

    @Test fun interruptedBatchRemainsObservableAndDoesNotContinueOnRetry() {
        val root=temp.newFolder("root").toPath();Files.createDirectory(root.resolve("tree"))
        Files.write(root.resolve("tree/a.txt"),byteArrayOf(1));Files.write(root.resolve("tree/b.txt"),byteArrayOf(2))
        val recovery=temp.newFolder("recovery").toPath();val store=TaskFileStore(root,recovery)
        val plan=store.freezeDeletion("tree");var checks=0
        try { store.deleteFrozen(plan) { if(++checks==2) throw AssertionError("process interruption");true };fail() }
        catch(_:AssertionError) {}
        val observed=TaskFileStore(root,recovery).inspectDeletion(plan.token)
        assertEquals("UNKNOWN",observed.state);assertEquals("INTERRUPTED_NO_REPLAY",observed.reason)
        assertEquals(listOf("SUCCEEDED","NOT_EXECUTED","NOT_EXECUTED"),observed.members.map { it.state })
        assertEquals(observed,store.deleteFrozen(plan) { true })
        assertTrue(Files.exists(root.resolve("tree/b.txt")))
    }
}
