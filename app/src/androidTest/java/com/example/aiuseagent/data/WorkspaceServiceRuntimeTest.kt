package com.example.aiuseagent.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiuseagent.data.local.AppDatabase
import com.example.aiuseagent.data.workspace.ToolBudget
import com.example.aiuseagent.data.workspace.WorkspaceService
import com.example.aiuseagent.platform.AndroidWorkspaceIo
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Runs service initialization and JSON argument matching on Android's actual regex runtime. */
@RunWith(AndroidJUnit4::class)
class WorkspaceServiceRuntimeTest {
    @Test fun androidServiceInitializesReadsFixtureAndRejectsDuplicateOrExtraArguments() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // A separate in-memory Room instance never opens or changes the app's main database.
        val database = Room.inMemoryDatabaseBuilder(context,AppDatabase::class.java).build()
        val root = File(context.filesDir.canonicalFile,"workspaces/default")
        val fixture = File(root,"runtime-${UUID.randomUUID()}.md")
        var created = false
        try {
            assertTrue(AndroidWorkspaceIo(context).defaultAvailable())
            assertTrue(fixture.createNewFile())
            created = true
            val content = "Android UTF-8 fixture.\n"
            fixture.writeText(content,Charsets.UTF_8)
            val service = WorkspaceService(context,database)
            val arguments = "{\"path\":\"${fixture.name}\"}"
            val normal = service.execute("default","read_text_file",arguments,ToolBudget())
            assertTrue(normal.success)
            assertEquals(content,normal.content)
            assertEquals(content.toByteArray(Charsets.UTF_8).size,normal.bytes)
            val invalid = listOf(
                "{\"path\":\"${fixture.name}\",\"path\":\"${fixture.name}\"}",
                "{\"path\":\"${fixture.name}\",\"uri\":\"content://untrusted/file\"}",
            )
            for (json in invalid) {
                val rejected = service.execute("default","read_text_file",json,ToolBudget())
                assertFalse(rejected.success)
                assertEquals("INVALID_ARGUMENTS",rejected.errorCode)
            }
            assertEquals(content,fixture.readText(Charsets.UTF_8))
        } finally {
            database.close()
            if (created) {
                check(requireNotNull(fixture.parentFile).canonicalFile == root.canonicalFile)
                Files.deleteIfExists(fixture.toPath())
            }
        }
    }
}
