package com.example.aiuseagent.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiuseagent.data.local.AppDatabase
import com.example.aiuseagent.data.workspace.RoomWorkspaceRegistry
import com.example.aiuseagent.data.workspace.WorkspaceEntry
import com.example.aiuseagent.data.workspace.WorkspaceGrant
import com.example.aiuseagent.data.workspace.WorkspaceIo
import com.example.aiuseagent.data.workspace.WorkspaceRemoval
import com.example.aiuseagent.data.workspace.WorkspaceService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real Room transactions and references; the grant adapter is deliberately a local fake. */
@RunWith(AndroidJUnit4::class)
class WorkspaceReferencesTest {
    @Test fun sharedWorkspaceRemovalPreservesHistoricBindingsAndRefusesActiveUse() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        try {
            val io = GrantIo()
            val service = WorkspaceService(RoomWorkspaceRegistry(database),io)
            val repository = SessionRepository(database.sessionDao(),database.messageDao(),RoomGenerationPersistence(database))
            val workspace = service.register("content://fixture/tree/shared",1)!!
            val first = repository.createSession("first","BUILD")
            val second = repository.createSession("second","BUILD")
            service.bind(first,workspace); service.bind(second,workspace)
            repository.appendMessage(first,"user","first preserved")
            repository.appendMessage(second,"user","second preserved")
            assertEquals(2,database.sessionDao().workspaceReferences(workspace))
            assertEquals(WorkspaceRemoval.REQUIRES_REPLACEMENT,service.remove(workspace))
            try { service.bind(first,"default");fail("Populated root cannot be replaced") } catch(_:IllegalStateException) { }
            assertEquals(2,database.sessionDao().workspaceReferences(workspace))
            assertEquals(0,io.releases)
            repository.prepareGeneration(second,"generation question","DEEPSEEK",workspace,"workspace-reference-test",mode="BUILD") { }
            assertEquals(WorkspaceRemoval.BUSY,service.remove(workspace,"default"))
            assertEquals(0,io.releases)
            repository.persistGeneration("workspace-reference-test","","CANCELLED")
            assertEquals(WorkspaceRemoval.REMOVED,service.remove(workspace,"default"))
            assertEquals(1,io.releases)
            assertEquals(workspace,database.sessionDao().findById(first)!!.workspaceId)
            assertEquals(workspace,database.sessionDao().findById(second)!!.workspaceId)
            assertEquals(workspace,database.generationDao().findById("workspace-reference-test")!!.workspaceId)
            assertNull(database.workspaceDao().findById(workspace))
            assertEquals("first preserved",database.messageDao().listInSession(first).single().content)
            assertEquals("second preserved",database.messageDao().listInSession(second).first().content)
        } finally { database.close() }
    }

    private class GrantIo : WorkspaceIo {
        private var granted = false
        var releases = 0
        override fun defaultAvailable() = true
        override fun takeTree(uri: String,flags: Int): WorkspaceGrant {
            granted = true; return WorkspaceGrant("shared fixture",true,true)
        }
        override fun grant(uri: String) = if(granted) WorkspaceGrant("shared fixture",true,true) else null
        override fun release(uri: String) { granted = false; releases++ }
        override fun list(treeUri: String?,segments: List<String>): List<WorkspaceEntry> = error("No scan expected")
        override fun read(treeUri: String?,segments: List<String>,maxBytes: Int): ByteArray = error("No read expected")
        override fun readSelected(uri: String,maxBytes: Int): ByteArray = error("No import expected")
        override fun createUserFile(treeUri: String?,fileName: String,bytes: ByteArray): Unit = error("No import expected")
        override fun writeSelectedEmpty(uri: String,bytes: ByteArray): Unit = error("No export expected")
    }
}
