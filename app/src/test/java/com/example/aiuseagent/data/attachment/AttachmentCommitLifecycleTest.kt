package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.*
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentCommitLifecycleTest {
    @get:Rule val temp=TemporaryFolder(java.io.File("build/test-task-files").absoluteFile.apply {mkdirs()})
    private val scope=AttachmentScope("draft",null,"CHAT",0,null,0,"profile",0,0)
    private var current=scope
    private val root by lazy {temp.newFolder().toPath()}
    private val repo by lazy { AttachmentRepository(root,object:AttachmentSource {
        override suspend fun describe(uri:String)=AttachmentSourceInfo("synthetic.txt","text/plain")
        override suspend fun open(uri:String)=ByteArrayInputStream("selected omitted".toByteArray())
    },{it==current}) }
    private suspend fun frozen():FrozenAttachmentInput {
        val handle=repo.stage(scope,"content://synthetic/file",UUID.randomUUID().toString())
        repo.parsed(scope,handle,AttachmentParseResult(handle.sha256,"fixture:1",listOf(
            AttachmentPart("first","page 1",text="selected"),AttachmentPart("second","page 2",text="omitted"))))
        return repo.freeze(scope,listOf(AttachmentSelection(handle.id,handle.sha256,"fixture:1",listOf("first"),true)),"budget:1").single()
    }
    @Test fun removalDuringPendingCommitCannotDeleteFileAndRollbackReleasesCopy()=runTest {
        val input=frozen();val commit=repo.prepareCommit(scope,listOf(input))
        repo.remove(scope,input.handle.id)
        assertTrue(Files.exists(root.resolve(input.handle.localToken+".blob")))
        assertFalse(commit.isCurrent())
        commit.finish(null)
        assertFalse(Files.exists(root.resolve(input.handle.localToken+".blob")))
    }
    @Test fun committedCopySurvivesDraftRemovalAndOnlyExactBlankToSessionBindingIsAccepted()=runTest {
        val input=frozen();val commit=repo.prepareCommit(scope,listOf(input))
        commit.finish(42L);current=scope.copy(sessionId=42L)
        assertTrue(repo.acceptsInput(scope,listOf(input)))
        repo.remove(current,input.handle.id)
        assertTrue(Files.exists(root.resolve(input.handle.localToken+".blob")))
        current=current.copy(draftId="another")
        assertFalse(repo.acceptsInput(scope,listOf(input)))
    }
    @Test fun receiptRoundTripRetainsExactConfirmedPartsAndRejectsDifferentSource()=runTest {
        val input=frozen();val encoded=AttachmentSelectionReceipt.encode(input)
        val decoded=AttachmentSelectionReceipt.decode(encoded,input.handle)
        assertEquals(listOf("first"),decoded.selectedPartIds)
        assertEquals(input.selectedContentSha256,decoded.selectedContentSha256)
        assertEquals(input.modelBudgetRevision,decoded.modelBudgetRevision)
        try {AttachmentSelectionReceipt.decode(encoded,input.handle.copy(sha256="f".repeat(64)));fail("Wrong source accepted")}
        catch(error:AttachmentFailure) {assertEquals("SELECTION_RECEIPT_INVALID",error.code)}
    }
}
