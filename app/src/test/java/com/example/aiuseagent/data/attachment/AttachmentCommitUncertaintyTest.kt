package com.example.aiuseagent.data.attachment

import com.example.aiuseagent.data.*
import com.example.aiuseagent.data.local.GenerationDao
import com.example.aiuseagent.data.generation.*
import com.example.aiuseagent.data.provider.ProviderRepository
import com.example.aiuseagent.domain.attachment.*
import com.example.aiuseagent.domain.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AttachmentCommitUncertaintyTest {
    @Test fun unknownCommitOutcomeNeverReleasesPossiblyReferencedCopy()=runTest {
        val store=FakeDaoStore()
        var uncertain=false
        val persistence=object:GenerationPersistence by store.generationPersistence {
            override val generations=object:GenerationDao by store.generationDao {
                override suspend fun findById(id:String)=if(uncertain) error("Synthetic database unavailable") else store.generationDao.findById(id)
            }
            override suspend fun transaction(block:suspend ()->Unit) {
                store.generationPersistence.transaction(block)
                if(store.attachments.value.isNotEmpty()) {uncertain=true;throw CancellationException("Synthetic lost commit acknowledgement")}
            }
        }
        val sessions=SessionRepository(store.sessionDao,store.messageDao,persistence)
        val engineScope=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val providers=ProviderRepository(ProviderRegistry(mapOf(ProviderType.MOCK to com.example.aiuseagent.data.provider.mock.MockProvider())))
        val engine=GenerationCoordinator(sessions,providers,engineScope,{_,_,_,_->error("No tools")},{false})
        val scope=AttachmentScope("draft",null,"CHAT",0,null,0,"builtin:MOCK",0,0)
        val handle=AttachmentHandle("c10dd430-4fb6-4cb1-a098-6054e6ddf435","94692707-3c0b-4d15-9070-d3f345ee68b1","a".repeat(64),"text/plain",3)
        val finished=mutableListOf<Long?>()
        val commit=AttachmentCommit(scope,listOf(AttachmentCommitRecord(handle,"synthetic.txt")),{true}) {finished+=it}
        val prepared=PreparedAttachmentInput(listOf(ProviderContentPart.Text("synthetic")),ProviderAttachmentPayloads(),"")
        try {
            advanceUntilIdle()
            assertTrue(engine.submit(GenerationSubmission(null,"",attachments=AttachmentGenerationInput(scope,prepared,commit,{true}) {emptyMap()})))
            advanceUntilIdle()
            assertEquals(1,store.attachments.value.size)
            assertTrue("Unknown DB outcome must retain reservation",finished.isEmpty())
        } finally {engineScope.cancel()}
    }
}
