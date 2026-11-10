package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.*
import com.example.aiuseagent.data.local.AttachmentEntity
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentRestoreTest {
    @get:Rule val temp=TemporaryFolder(java.io.File("build/test-task-files").absoluteFile.apply {mkdirs()})
    private val root by lazy {temp.newFolder().toPath()}
    private val scope=AttachmentScope("draft",42,"CHAT",0,null,0,"profile",0,0)
    private fun repository()=AttachmentRepository(root,object:AttachmentSource {
        override suspend fun describe(uri:String)=AttachmentSourceInfo("fixture.txt","text/plain")
        override suspend fun open(uri:String)=ByteArrayInputStream("selected omitted".toByteArray())
    },{it==scope})
    private fun parsed(handle:AttachmentHandle)=AttachmentParseResult(handle.sha256,"fixture:1",listOf(
        AttachmentPart("chosen","page 1",text="selected"),AttachmentPart("omitted","page 2",text="omitted")))
    private suspend fun reference():AttachmentEntity {
        val repo=repository();val handle=repo.stage(scope,"content://synthetic/input",UUID.randomUUID().toString())
        repo.parsed(scope,handle,parsed(handle))
        val frozen=repo.freeze(scope,listOf(AttachmentSelection(handle.id,handle.sha256,"fixture:1",listOf("chosen"),true)),"budget:1").single()
        repo.prepareCommit(scope,listOf(frozen)).finish(42)
        return AttachmentEntity(handle.id,42,7,0,"profile",0,"fixture.txt",handle.mimeType,handle.sizeBytes,handle.localToken,handle.sha256,"PREPARED",AttachmentSelectionReceipt.encode(frozen))
    }
    @Test fun processRestartRestoresExactFrozenSelectionWithoutVisibleDraftOrSourceUri()=runTest {
        val reference=reference();val repo=repository()
        val frozen=repo.restoreReference(scope,reference)
        repo.parsed(scope,frozen.handle,parsed(frozen.handle))
        assertEquals(listOf("chosen"),repo.selectedParts(scope,frozen,"budget:1").map {it.id})
        assertTrue(repo.state.value.isEmpty())
        assertTrue(repo.acceptsInput(scope,listOf(frozen)))
        repo.remove(scope,frozen.handle.id)
        assertTrue(Files.exists(root.resolve(reference.localToken+".blob")))
    }
    @Test fun legacySelectionWrongScopeAndEscapingTokenAreRejected()=runTest {
        val reference=reference()
        for(invalid in listOf(reference.copy(selectionJson=null),reference.copy(sessionId=99),reference.copy(profileRevision=1),reference.copy(localToken="../escape"))) {
            try {repository().restoreReference(scope,invalid);fail("Invalid reference restored")}
            catch(error:AttachmentFailure) {assertNotEquals("RESTORE_NOT_IMPLEMENTED",error.code)}
        }
    }
    @Test fun changedOwnedBytesAndMissingOwnershipMarkerCannotRestore()=runTest {
        val reference=reference()
        Files.write(root.resolve(reference.localToken+".blob"),"changed".toByteArray())
        try {repository().restoreReference(scope,reference);fail("Changed content accepted")}
        catch(error:AttachmentFailure) {assertEquals("COPY_CHANGED",error.code)}
        Files.delete(root.resolve(reference.localToken+".owner"))
        try {repository().restoreReference(scope,reference);fail("Unowned file accepted")}
        catch(error:AttachmentFailure) {assertEquals("COPY_UNAVAILABLE",error.code)}
    }
}
