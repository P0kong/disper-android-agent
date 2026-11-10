package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.domain.attachment.*
import java.io.ByteArrayInputStream
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentSelectionTest {
    @get:Rule val temp=TemporaryFolder(java.io.File("build/test-task-files").absoluteFile.apply {mkdirs()})
    private val scope=AttachmentScope("draft",null,"CHAT",0,null,0,"profile",1,0)
    private var current=scope
    private fun repository()=AttachmentRepository(temp.newFolder().toPath(),object:AttachmentSource {
        override suspend fun describe(uri:String)=AttachmentSourceInfo("a.txt","text/plain")
        override suspend fun open(uri:String)=ByteArrayInputStream("first second".toByteArray())
    },{it==current})
    private suspend fun stage(repo:AttachmentRepository)=repo.stage(scope,"content://file",UUID.randomUUID().toString())
    private fun result(handle:AttachmentHandle,revision:String="text:1",complete:Boolean=false)=AttachmentParseResult(handle.sha256,revision,
        listOf(AttachmentPart("part:1","page 1",text="first"),AttachmentPart("part:2","page 2",text="second")),complete)
    private fun selection(handle:AttachmentHandle,ids:List<String> = listOf("part:1"),confirmed:Boolean=true)=
        AttachmentSelection(handle.id,handle.sha256,"text:1",ids,confirmed)
    private suspend fun failure(code:String,block:suspend ()->Unit) {
        try {block();fail("Expected $code")} catch(e:AttachmentFailure) {assertEquals(code,e.code)}
    }
    @Test fun confirmedPartsFreezeExactImmutableSelectionAndNeverIncludeUnselectedContent()=runTest {
        val repo=repository();val handle=stage(repo);repo.parsed(scope,handle,result(handle))
        val ids=mutableListOf("part:1")
        val input=repo.freeze(scope,listOf(selection(handle,ids)),"model:1").single()
        ids.add("part:2")
        assertEquals(listOf("part:1"),input.selectedPartIds)
        assertEquals(listOf("first"),repo.selectedParts(scope,input,"model:1").map {it.text})
        assertEquals(64,input.selectedContentSha256.length)
    }
    @Test fun processingUnconfirmedUnknownAndOmittedCardsCannotBeSilentlyDropped()=runTest {
        val repo=repository();val handle=stage(repo)
        failure("ATTACHMENT_NOT_READY") {repo.freeze(scope,listOf(selection(handle)),"model:1")}
        repo.parsed(scope,handle,result(handle))
        failure("SELECTION_CONFIRMATION_REQUIRED") {repo.freeze(scope,listOf(selection(handle,confirmed=false)),"model:1")}
        failure("SELECTION_INVALID") {repo.freeze(scope,listOf(selection(handle,listOf("part:other"))),"model:1")}
        stage(repo)
        failure("ATTACHMENT_LIST_CHANGED") {repo.freeze(scope,listOf(selection(handle)),"model:1")}
    }
    @Test fun scopeParserAndModelBudgetChangesInvalidatePreviouslyFrozenInput()=runTest {
        val repo=repository();val handle=stage(repo);repo.parsed(scope,handle,result(handle))
        val input=repo.freeze(scope,listOf(selection(handle)),"model:1").single()
        failure("BUDGET_CHANGED") {repo.selectedParts(scope,input,"model:2")}
        repo.parsed(scope,handle,result(handle,revision="text:2"))
        failure("PARSER_CHANGED") {repo.selectedParts(scope,input,"model:1")}
        current=scope.copy(profileRevision=2)
        failure("SCOPE_CHANGED") {repo.freeze(scope,listOf(selection(handle)),"model:1")}
    }
}
