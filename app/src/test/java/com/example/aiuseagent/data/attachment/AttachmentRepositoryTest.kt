package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentRepositoryTest {
    @get:Rule val temp=TemporaryFolder(java.io.File("build/test-task-files").absoluteFile.apply {mkdirs()})
    private val scope=AttachmentScope("draft",null,"CHAT",1,null,0,"profile",1,0)
    private fun id()=UUID.randomUUID().toString()
    private fun source(bytes:ByteArray)=object:AttachmentSource {
        override suspend fun describe(uri:String)=AttachmentSourceInfo("same.txt","text/plain",0)
        override suspend fun open(uri:String)=ByteArrayInputStream(bytes)
    }
    private suspend fun failure(code:String,block:suspend ()->Unit) {
        try {block();fail("Expected $code")} catch(e:AttachmentFailure) {assertEquals(code,e.code)}
    }
    @Test fun sameNamesHaveDistinctOwnedCopiesWithoutWritingOrDeletingSource()=runTest {
        val root=temp.newFolder().toPath();val original="retained".toByteArray()
        val repository=AttachmentRepository(root,source(original),{it==scope})
        val one=repository.stage(scope,"content://one",id());val two=repository.stage(scope,"content://two",id())
        assertNotEquals(one.localToken,two.localToken);assertNotEquals(one.id,two.id)
        assertEquals(2,repository.state.value.size)
        assertArrayEquals(original,repository.read(scope,one))
        repository.remove(scope,one.id)
        assertArrayEquals(original,repository.read(scope,two));assertEquals(1,repository.state.value.size)
        assertEquals("retained",String(original))
    }
    @Test fun actualBytesEnforcePerFileAndAggregateLimitsDespiteFalseMetadata()=runTest {
        val repository=AttachmentRepository(temp.newFolder().toPath(),source(byteArrayOf(1,2,3)),{true},
            AttachmentLimits(maxFileBytes=3,maxTotalBytes=5))
        repository.stage(scope,"content://one",id())
        failure("TOTAL_LIMIT") {repository.stage(scope,"content://two",id())}
        assertEquals(1,repository.state.value.count {it.handle!=null})
        val tooLarge=AttachmentRepository(temp.newFolder().toPath(),source(byteArrayOf(1,2,3,4)),{true},
            AttachmentLimits(maxFileBytes=3,maxTotalBytes=5))
        failure("FILE_LIMIT") {tooLarge.stage(scope,"content://large",id())}
        assertEquals(AttachmentState.FAILED,tooLarge.state.value.single().state)
        assertNull(tooLarge.state.value.single().handle)
    }
    @Test fun tenAttachmentsIncludesInFlightReservationsAndDuplicateTicketIsRejected()=runTest {
        val repository=AttachmentRepository(temp.newFolder().toPath(),source(byteArrayOf(1)),{true})
        val request=id();repository.stage(scope,"content://one",request)
        failure("REQUEST_REPLAY") {repository.stage(scope,"content://same",request)}
        repeat(9){repository.stage(scope,"content://$it",id())}
        failure("COUNT_LIMIT") {repository.stage(scope,"content://overflow",id())}
        assertEquals(10,repository.state.value.size)
    }
    @Test fun cancelledOpenCannotResurrectCardOrLeaveOwnedFiles()=runTest {
        val release=CompletableDeferred<Unit>();val root=temp.newFolder().toPath()
        val source=object:AttachmentSource {
            override suspend fun describe(uri:String)=AttachmentSourceInfo("a.txt","text/plain")
            override suspend fun open(uri:String):ByteArrayInputStream {release.await();return ByteArrayInputStream(byteArrayOf(1))}
        }
        val repository=AttachmentRepository(root,source,{true});val request=id()
        val task=async {runCatching {repository.stage(scope,"content://pending",request)}}
        runCurrent();val card=repository.state.value.single()
        repository.cancel(scope,card.id);release.complete(Unit)
        assertTrue(task.await().isFailure);assertTrue(repository.state.value.isEmpty())
        assertEquals(0L,Files.list(root).use {it.count()})
    }
    @Test fun changedScopeAndForgedHandleCannotReadStagedContent()=runTest {
        var current=scope;val repository=AttachmentRepository(temp.newFolder().toPath(),source(byteArrayOf(1)),{it==current})
        val handle=repository.stage(scope,"content://one",id())
        failure("HANDLE_INVALID") {repository.read(scope,handle.copy(localToken="../private"))}
        current=scope.copy(selectionRevision=1)
        failure("SCOPE_CHANGED") {repository.read(scope,handle)}
        failure("SCOPE_CHANGED") {repository.stage(scope,"content://late",id())}
    }
    @Test fun scopeChangeDuringReadRemovesOnlyThisUncommittedCopy()=runTest {
        var current=true;val root=temp.newFolder().toPath()
        val source=object:AttachmentSource {
            override suspend fun describe(uri:String)=AttachmentSourceInfo("a.txt","text/plain")
            override suspend fun open(uri:String)=object:ByteArrayInputStream(byteArrayOf(1)) {
                override fun read(b:ByteArray,off:Int,len:Int):Int {current=false;return super.read(b,off,len)}
            }
        }
        val repository=AttachmentRepository(root,source,{current})
        failure("SCOPE_CHANGED") {repository.stage(scope,"content://late",id())}
        assertTrue(repository.state.value.none {it.handle!=null})
        assertEquals(0L,Files.list(root).use {it.count()})
    }
}
