package com.example.aiuseagent.data.execution

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TaskFileStoreTest {
    @get:Rule val temp=TemporaryFolder(java.io.File("build/test-task-files").absoluteFile.apply { mkdirs() })
    private fun store(): TaskFileStore=TaskFileStore(temp.newFolder("root").toPath(),temp.newFolder("recovery").toPath())
    @Test fun expectedHashPreventsLostUpdatesAndPatchRequiresOneExactMatch() {
        val store=store()
        store.write("a.txt","MISSING","old".toByteArray())
        val hash=store.inspect("a.txt").hash
        store.patch("a.txt",hash,"old","new")
        assertEquals("new",String(store.inspect("a.txt").bytes!!))
        try { store.write("a.txt",hash,"stale".toByteArray());fail() } catch(e: FileMutationFailure) { assertEquals("CONFLICT",e.code) }
        val changed=store.inspect("a.txt").hash
        try { store.patch("a.txt",changed,"absent","x");fail() } catch(e: FileMutationFailure) { assertEquals("PATCH_AMBIGUOUS",e.code) }
        assertEquals("new",String(store.inspect("a.txt").bytes!!))
    }
    @Test fun deleteHasVerifiedRecoveryAndRestoreNeverOverwritesNewWork() {
        val store=store();store.write("a.txt","MISSING","keep".toByteArray())
        val receipt=store.delete("a.txt",store.inspect("a.txt").hash)
        assertEquals("MISSING",store.inspect("a.txt").hash)
        store.write("a.txt","MISSING","replacement".toByteArray())
        try { store.restore(receipt.token,"MISSING");fail() } catch(e: FileMutationFailure) { assertEquals("CONFLICT",e.code) }
        assertEquals("replacement",String(store.inspect("a.txt").bytes!!))
        store.delete("a.txt",store.inspect("a.txt").hash)
        store.restore(receipt.token,"MISSING")
        assertEquals("keep",String(store.inspect("a.txt").bytes!!))
        try { store.restore(receipt.token,"MISSING");fail() } catch(_: FileMutationFailure) {}
    }
    @Test fun traversalAndOversizedDataFailBeforeTaskResourceMutation() {
        val store=store()
        for(path in listOf("../private.txt","/absolute.txt","C:/private.txt","a/../b.txt")) {
            try { store.write(path,"MISSING",byteArrayOf(1));fail(path) } catch(_: FileMutationFailure) {}
        }
        try { store.write("large.txt","MISSING",ByteArray(TaskFileStore.MAX_BYTES+1));fail() } catch(e: FileMutationFailure) {assertEquals("FILE_TOO_LARGE",e.code)}
        assertEquals("MISSING",store.inspect("large.txt").hash)
    }
    @Test fun durableRecoverySurvivesNewStoreInstanceAndTamperingIsDetected() {
        val root=temp.newFolder("root").toPath();val recovery=temp.newFolder("recovery").toPath()
        val store=TaskFileStore(root,recovery);store.write("a.txt","MISSING","old".toByteArray())
        val receipt=store.write("a.txt",store.inspect("a.txt").hash,"new".toByteArray())
        TaskFileStore(root,recovery).restore(receipt.token,store.inspect("a.txt").hash)
        assertEquals("old",String(store.inspect("a.txt").bytes!!))
        val deleted=store.delete("a.txt",store.inspect("a.txt").hash)
        Files.write(recovery.resolve(deleted.token+".body"),"tampered".toByteArray())
        try { store.restore(deleted.token,"MISSING");fail() } catch(e: FileMutationFailure) {assertEquals("RECOVERY_INVALID",e.code)}
        assertEquals("MISSING",store.inspect("a.txt").hash)
    }
    @Test fun aRecoveryTokenCannotCrossTaskRootsSharingOneRecoveryDirectory() {
        val first=temp.newFolder("a").toPath();val second=temp.newFolder("b").toPath();val recovery=temp.newFolder("recovery").toPath()
        val a=TaskFileStore(first,recovery);val b=TaskFileStore(second,recovery)
        a.write("private.txt","MISSING","belongs-to-A".toByteArray())
        val token=a.delete("private.txt",a.inspect("private.txt").hash).token
        try { b.restore(token,"MISSING");fail("token must stay bound to root A") }
        catch(e: FileMutationFailure) { assertEquals("RECOVERY_ROOT_MISMATCH",e.code) }
        assertEquals("MISSING",b.inspect("private.txt").hash)
        try { TaskFileStore(first,recovery,1).restore(token,"MISSING");fail("changed root revision must fail") }
        catch(e: FileMutationFailure) { assertEquals("RECOVERY_ROOT_MISMATCH",e.code) }
        a.restore(token,"MISSING")
        assertEquals("belongs-to-A",String(a.inspect("private.txt").bytes!!))
    }
}
