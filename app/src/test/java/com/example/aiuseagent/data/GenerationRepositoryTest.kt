package com.example.aiuseagent.data

import com.example.aiuseagent.data.local.MessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class GenerationRepositoryTest {
    private val store = FakeDaoStore()
    private var clock = 100L
    private fun repository() = SessionRepository(store.sessionDao, store.messageDao, store.generationPersistence) { clock++ }

    @Test fun validationFailureLeavesBlankDraftAndExistingSessionUnchanged() = runTest {
        val repository = repository()
        try { repository.prepareGeneration(null, "too large", "DEEPSEEK", "default", "invalid") { error("budget") }; fail() }
        catch (_: IllegalStateException) { }
        assertTrue(store.sessions.value.isEmpty()); assertTrue(store.messages.value.isEmpty()); assertTrue(store.generations.value.isEmpty())
        val id = repository.createSession("kept")
        try { repository.prepareGeneration(id, "too large", "DEEPSEEK", "default", "invalid2") { error("budget") }; fail() }
        catch (_: IllegalStateException) { }
        assertEquals("kept", store.sessions.value.single().title); assertTrue(store.messages.value.isEmpty())
    }

    @Test fun partialUpdatesSameRowTerminalCasAndRetryReuseUser() = runTest {
        val repository = repository()
        val first = repository.prepareGeneration(null, "question", "DEEPSEEK", "default", "first") { }
        val assistant = repository.persistGeneration("first", "par", "STREAMING")
        assertEquals(assistant, repository.persistGeneration("first", "partial", "STREAMING"))
        assertEquals(assistant, repository.persistGeneration("first", "partial", "CANCELLED"))
        assertNull(repository.persistGeneration("first", "late overwrite", "COMPLETED"))
        assertEquals(2, store.messages.value.size); assertEquals("CANCELLED", store.messages.value.last().status)
        val retry = repository.prepareGeneration(first.sessionId, "ignored", "DEEPSEEK", "default", "retry", first.userMessageId) { }
        assertEquals(first.userMessageId, retry.userMessageId)
        assertEquals(listOf("question"), retry.context.map { it.content })
        repository.persistGeneration("retry", "complete", "COMPLETED")
        assertEquals(1, store.messages.value.count { it.role == "user" })
        assertEquals(first.userMessageId, store.messages.value.last().replyToMessageId)
    }

    @Test fun zeroTextFailureSurvivesAndRecoveryDoesNotResumeNetworkOrTurn() = runTest {
        val repository = repository()
        val first = repository.prepareGeneration(null, "first", "DEEPSEEK", "default", "zero") { }
        repository.persistGeneration("zero", "", "FAILED", "NETWORK")
        assertEquals(1, store.messages.value.size); assertEquals("FAILED", store.generations.value.single().status)
        repository.prepareGeneration(first.sessionId, "second", "DEEPSEEK", "default", "stream") { }
        repository.persistGeneration("stream", "partial", "STREAMING")
        repository().recoverInterruptedGenerations()
        assertEquals("INTERRUPTED", store.generations.value.last().status)
        assertEquals("INTERRUPTED", store.messages.value.last().status)
        assertNull(repository.persistGeneration("stream", "late", "COMPLETED"))
    }

    @Test fun deletionDuringGenerationCannotRecreateAnyRow() = runTest {
        val repository = repository()
        val first = repository.prepareGeneration(null, "question", "DEEPSEEK", "default", "deleted") { }
        repository.persistGeneration("deleted", "part", "STREAMING")
        repository.deleteSession(first.sessionId)
        assertNull(repository.persistGeneration("deleted", "late", "COMPLETED"))
        assertTrue(store.sessions.value.isEmpty()); assertTrue(store.messages.value.isEmpty()); assertTrue(store.generations.value.isEmpty())
    }

    @Test fun contextIsBoundedAndExcludesDemoMockPartialAndLegacyUnknownProvider() = runTest {
        val repository = repository()
        val id = repository.createSession()
        store.sessionDao.updateContext(id,0,0,"builtin:DEEPSEEK",0)
        repeat(25) { store.messageDao.insert(MessageEntity(sessionId=id, role="user",content="safe$it",createdAt=clock++,providerType="DEEPSEEK",
            mode="CHAT",profileId="builtin:DEEPSEEK")) }
        for (row in listOf(
            MessageEntity(sessionId=id,role="assistant",content="demo",createdAt=clock++,isDemo=true,providerType="DEEPSEEK"),
            MessageEntity(sessionId=id,role="assistant",content="mock",createdAt=clock++,providerType="MOCK"),
            MessageEntity(sessionId=id,role="assistant",content="partial",createdAt=clock++,providerType="DEEPSEEK",status="FAILED"),
            MessageEntity(sessionId=id,role="assistant",content="legacy",createdAt=clock++),
            MessageEntity(sessionId=id,role="tool",content="tool body",createdAt=clock++,providerType="DEEPSEEK"),
        )) store.messageDao.insert(if(row.providerType==null) row else row.copy(mode="CHAT",profileId="builtin:DEEPSEEK"))
        val turn = repository.prepareGeneration(id,"latest","DEEPSEEK","default","filtered") { context ->
            assertEquals(20,context.size); assertEquals("latest",context.last().content)
            assertTrue(context.dropLast(1).all { it.content.startsWith("safe") })
        }
        assertEquals("safe6",turn.context.first().content)
        assertEquals(20,turn.context.size)
    }

    @Test fun toolAuditContainsMetadataOnlyAndTerminalGenerationRejectsLateTools() = runTest {
        val repository = repository()
        repository.prepareGeneration(null,"question","DEEPSEEK","default","audit") { }
        repository.recordTool("audit","call1","read_text_file","COMPLETED",32,10)
        repository.persistGeneration("audit","done","COMPLETED")
        repository.recordTool("audit","late","read_text_file","COMPLETED",32,10)
        assertEquals(1,store.tools.value.size)
        assertEquals(1,store.messages.value.count { it.role == "assistant" })
    }
}
