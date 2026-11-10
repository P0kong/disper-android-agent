package com.example.aiuseagent.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiuseagent.data.workspace.WorkspaceFailure
import com.example.aiuseagent.platform.AndroidWorkspaceIo
import java.io.File
import java.nio.file.Files
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DefaultWorkspaceBoundaryTest {
    @Test fun actualPrivateAdapterBoundsListingAndRejectsSymlinkEscape() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val io = AndroidWorkspaceIo(context)
        assertTrue(io.defaultAvailable())
        val root = File(context.filesDir.canonicalFile, "workspaces/default")
        val name = "boundary-${UUID.randomUUID()}"
        val owned = File(root, name)
        assertTrue(owned.mkdir())
        try {
            repeat(25) { File(owned,"$it.txt").writeText("fixture $it") }
            assertEquals(20,io.list(null,listOf(name)).size)
            assertEquals("fixture 0",io.read(null,listOf(name,"0.txt"),8192).toString(Charsets.UTF_8))
            val link = File(owned,"escape")
            Files.createSymbolicLink(link.toPath(),context.cacheDir.canonicalFile.toPath())
            try { io.read(null,listOf(name,"escape","outside.txt"),8192); fail("symlink must fail closed") }
            catch (failure: WorkspaceFailure) { assertEquals("SYMLINK_ESCAPE",failure.code) }
            assertTrue(io.list(null,listOf(name)).none { it.name == "escape" })
        } finally {
            // Delete only files created under this unique test directory; never follow the link.
            check(requireNotNull(owned.parentFile).canonicalFile == root.canonicalFile)
            owned.listFiles().orEmpty().forEach { Files.deleteIfExists(it.toPath()) }
            check(owned.delete())
        }
    }
}
