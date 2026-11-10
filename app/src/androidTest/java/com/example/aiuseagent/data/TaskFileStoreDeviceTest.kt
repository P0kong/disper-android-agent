package com.example.aiuseagent.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiuseagent.data.execution.TaskFileStore
import com.example.aiuseagent.data.execution.FileMutationFailure
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Only synthetic files in a fresh private test directory; never touches the user's task root. */
@RunWith(AndroidJUnit4::class)
class TaskFileStoreDeviceTest {
    @Test fun frozenTreeDeletionRejectsLinksAndRestoresDirectoriesOnAndroid() {
        val cache=InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val sandbox=File(cache,"synthetic-tree-${UUID.randomUUID()}")
        check(sandbox.parentFile==cache && sandbox.mkdir())
        try {
            val root=File(sandbox,"root").apply { check(mkdir()) }.toPath()
            val recovery=File(sandbox,"recovery").apply { check(mkdir()) }.toPath()
            Files.createDirectories(root.resolve("tree/nested"))
            Files.write(root.resolve("tree/nested/a.md"),"retained".toByteArray())
            val canary=File(sandbox,"outside.txt").apply { writeText("untouched") }
            Files.createSymbolicLink(root.resolve("tree/link.txt"),canary.toPath())
            val store=TaskFileStore(root,recovery)
            try { store.freezeDeletion("tree");fail("a link must invalidate the entire inventory") }
            catch(failure:FileMutationFailure) { assertEquals("SYMLINK_ESCAPE",failure.code) }
            assertEquals("untouched",canary.readText())
            Files.delete(root.resolve("tree/link.txt"))
            val plan=store.freezeDeletion("tree")
            val receipt=store.deleteFrozen(plan) { true }
            assertEquals("SUCCEEDED",receipt.state);assertFalse(Files.exists(root.resolve("tree")))
            val reopened=TaskFileStore(root,recovery)
            assertEquals(receipt,reopened.inspectDeletion(plan.token))
            assertTrue(reopened.inspectRecovery(receipt.members.first().recoveryToken).backupVerified)
            for(member in receipt.members.asReversed()) reopened.restore(member.recoveryToken,"MISSING")
            assertEquals("retained",String(Files.readAllBytes(root.resolve("tree/nested/a.md"))))
            assertEquals(receipt,store.deleteFrozen(plan) { true })
            assertTrue(Files.exists(root.resolve("tree/nested/a.md")))

            Files.createDirectory(root.resolve("partial"))
            Files.write(root.resolve("partial/a.txt"),byteArrayOf(1));Files.write(root.resolve("partial/b.txt"),byteArrayOf(2))
            val partial=store.deleteFrozen(store.freezeDeletion("partial")) { Files.exists(root.resolve("partial/a.txt")) }
            assertEquals("PARTIAL",partial.state)
            assertEquals(listOf("SUCCEEDED","NOT_EXECUTED","NOT_EXECUTED"),partial.members.map { it.state })
            assertEquals(partial,TaskFileStore(root,recovery).inspectDeletion(partial.token))
            assertTrue(Files.exists(root.resolve("partial/b.txt")))
        } finally {
            check(sandbox.canonicalFile.parentFile==cache)
            check(sandbox.deleteRecursively())
        }
    }
    @Test fun actualAndroidFilesystemSupportsConflictSafeWriteDeleteRecoveryAndRejectsLinks() {
        val cache=InstrumentationRegistry.getInstrumentation().targetContext.cacheDir.canonicalFile
        val sandbox=File(cache,"synthetic-mutation-${UUID.randomUUID()}")
        check(sandbox.parentFile==cache && sandbox.mkdir())
        try {
            val root=File(sandbox,"root").apply { check(mkdir()) }.toPath()
            val recovery=File(sandbox,"recovery").apply { check(mkdir()) }.toPath()
            val store=TaskFileStore(root,recovery)
            store.write("task.txt","MISSING","before".toByteArray())
            val before=store.inspect("task.txt").hash
            store.patch("task.txt",before,"before","after")
            try { store.write("task.txt",before,"stale".toByteArray());fail("stale hash must fail") }
            catch(failure: FileMutationFailure) { assertEquals("CONFLICT",failure.code) }
            val receipt=store.delete("task.txt",store.inspect("task.txt").hash)
            assertEquals("MISSING",store.inspect("task.txt").hash)
            TaskFileStore(root,recovery).restore(receipt.token,"MISSING")
            assertEquals("after",String(store.inspect("task.txt").bytes!!))
            val canary=File(sandbox,"canary.txt").apply { writeText("outside") }
            Files.createSymbolicLink(root.resolve("escape.txt"),canary.toPath())
            try { store.write("escape.txt","MISSING","bad".toByteArray());fail("symlink must fail") }
            catch(failure: FileMutationFailure) { assertEquals("SYMLINK_ESCAPE",failure.code) }
            assertEquals("outside",canary.readText())
        } finally {
            check(sandbox.canonicalFile.parentFile==cache)
            // FileTreeWalk does not follow symlink directories; this test creates only a file link.
            check(sandbox.deleteRecursively())
        }
    }
}
