package com.example.aiuseagent.data

import com.example.aiuseagent.data.local.MessageEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SessionContextPolicyTest {
    private val store = FakeDaoStore()
    private val repository = SessionRepository(store.sessionDao, store.messageDao, store.generationPersistence)

    @Test fun changingBuildRootCreatesNewSessionAndNeverSendsOldRootContent() = runTest {
        val first = repository.prepareGeneration(null,"A secret fixture","DEEPSEEK","A","a",
            mode="BUILD",profileId="profile-one",profileRevision=1) { }
        repository.persistGeneration("a","A answer","COMPLETED")
        val next = repository.prepareGeneration(first.sessionId,"B question","DEEPSEEK","B","b",
            mode="BUILD",profileId="profile-one",profileRevision=1) { }
        assertNotEquals(first.sessionId,next.sessionId)
        assertEquals(listOf("B question"),next.context.map { it.content })
        assertEquals("A",store.sessions.value.first { it.id==first.sessionId }.workspaceId)
        assertEquals(3,store.messages.value.size)
    }

    @Test fun sameRootKeepsSessionAndSameScopeHistory() = runTest {
        val first = repository.prepareGeneration(null,"first","DEEPSEEK","A","a",
            mode="BUILD",profileId="profile-one",profileRevision=1) { }
        repository.persistGeneration("a","answer","COMPLETED")
        val next = repository.prepareGeneration(first.sessionId,"next","DEEPSEEK","A","b",
            mode="BUILD",profileId="profile-one",profileRevision=1) { }
        assertEquals(first.sessionId,next.sessionId)
        assertEquals(listOf("first","answer","next"),next.context.map { it.content })
    }

    @Test fun chatHasNoWorkspaceAndSwitchingModeDoesNotReuseBuildHistory() = runTest {
        val build = repository.prepareGeneration(null,"workspace content","DEEPSEEK","A","a",
            mode="BUILD",profileId="p",profileRevision=1) { }
        repository.persistGeneration("a","build answer","COMPLETED")
        val chat = repository.prepareGeneration(build.sessionId,"chat question","DEEPSEEK","A","b",
            mode="CHAT",profileId="p",profileRevision=1) { }
        assertNotEquals(build.sessionId,chat.sessionId)
        assertNull(store.sessions.value.first { it.id==chat.sessionId }.workspaceId)
        assertEquals("",store.generations.value.last().workspaceId)
        assertEquals(listOf("chat question"),chat.context.map { it.content })
    }

    @Test fun sameProtocolDifferentProfileAndReturnToOldProfileNeverReviveHistory() = runTest {
        val first = repository.prepareGeneration(null,"first profile","DEEPSEEK",null,"a",
            profileId="one",profileRevision=1) { }
        repository.persistGeneration("a","first answer","COMPLETED")
        val second = repository.prepareGeneration(first.sessionId,"second profile","DEEPSEEK",null,"b",
            profileId="two",profileRevision=1) { }
        repository.persistGeneration("b","second answer","COMPLETED")
        val back = repository.prepareGeneration(first.sessionId,"back","DEEPSEEK",null,"c",
            profileId="one",profileRevision=1) { }
        assertEquals(first.sessionId,second.sessionId)
        assertEquals(first.sessionId,back.sessionId)
        assertEquals(listOf("second profile"),second.context.map { it.content })
        assertEquals(listOf("back"),back.context.map { it.content })
        assertTrue(back.scope.contextEpoch>second.scope.contextEpoch)
    }

    @Test fun revisionChangeDropsContextAndRejectsLateOldGenerationPersistence() = runTest {
        val first = repository.prepareGeneration(null,"first","DEEPSEEK",null,"a",profileId="p",profileRevision=1) { }
        val next = repository.prepareGeneration(first.sessionId,"next","DEEPSEEK",null,"b",profileId="p",profileRevision=2) { }
        assertEquals(listOf("next"),next.context.map { it.content })
        assertNull(repository.persistGeneration("a","late old connection","COMPLETED"))
        assertFalse(store.messages.value.any { it.content=="late old connection" })
    }

    @Test fun legacyHistoryRemainsReadableButContinuationCreatesSeparateSession() = runTest {
        val legacy=store.sessionDao.insert(com.example.aiuseagent.data.local.SessionEntity(
            title="legacy",createdAt=1,updatedAt=1,workspaceId="A",mode="LEGACY"))
        store.messageDao.insert(MessageEntity(sessionId=legacy,role="user",content="old",createdAt=1,providerType="DEEPSEEK"))
        val turn=repository.prepareGeneration(legacy,"new","DEEPSEEK",null,"g",profileId="p",profileRevision=1) { }
        assertNotEquals(legacy,turn.sessionId)
        assertEquals(listOf("new"),turn.context.map { it.content })
        assertEquals("old",store.messageDao.listInSession(legacy).single().content)
    }

    @Test fun toolSequenceIsMonotonicAndDuplicateCallDoesNotReplaceAudit() = runTest {
        repository.prepareGeneration(null,"fixture","DEEPSEEK","A","g",mode="BUILD") { }
        repository.recordTool("g","z","read_text_file","SUCCESS",1,1)
        repository.recordTool("g","a","read_text_file","SUCCESS",2,2)
        repository.recordTool("g","z","read_text_file","FAILED",3,3)
        assertEquals(listOf(1L,2L),store.tools.value.map { it.sequence })
        assertEquals(listOf("z","a"),store.tools.value.map { it.callId })
        assertEquals("SUCCESS",store.tools.value.first().status)
    }
}
