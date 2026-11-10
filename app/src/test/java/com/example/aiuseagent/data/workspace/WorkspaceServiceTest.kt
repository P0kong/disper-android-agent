package com.example.aiuseagent.data.workspace

import com.example.aiuseagent.data.local.WorkspaceEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class WorkspaceServiceTest {
    private class Registry : WorkspaceRegistry {
        val rows = mutableListOf<WorkspaceEntity>()
        val bindings = mutableMapOf<Long,String>()
        var active = 0
        override suspend fun list() = rows.toList()
        override suspend fun add(workspace: WorkspaceEntity) { rows += workspace }
        override suspend fun sessionExists(id: Long) = bindings.containsKey(id)
        override suspend fun bind(sessionId: Long, workspaceId: String) { bindings[sessionId] = workspaceId }
        override suspend fun references(id: String) = bindings.values.count { it == id }
        override suspend fun activeReferences(id: String) = active
        override suspend fun removeReplacing(id: String, replacementId: String) {
            bindings.replaceAll { _, old -> if(old == id) replacementId else old }; rows.removeAll { it.id == id }
        }
    }
    private class Io : WorkspaceIo {
        val grants = mutableMapOf<String,WorkspaceGrant>()
        val files = mutableMapOf("readme.md" to "hello".toByteArray())
        var listings = 0; var reads = 0; var released = 0; var writes = 0
        var entries = listOf(WorkspaceEntry("readme.md",false))
        var grantName = "chosen"
        var failAfterCreate = false
        var failExport = false
        var writeFailureCode = "UNAVAILABLE"
        override fun defaultAvailable() = true
        override fun takeTree(uri: String, flags: Int): WorkspaceGrant = WorkspaceGrant(grantName,true,true).also { grants[uri] = it }
        override fun grant(uri: String) = grants[uri]
        override fun release(uri: String) { released++; grants.remove(uri) }
        override fun list(treeUri: String?, segments: List<String>): List<WorkspaceEntry> { listings++; return entries }
        override fun read(treeUri: String?, segments: List<String>, maxBytes: Int): ByteArray {
            reads++; return files[segments.joinToString("/")] ?: throw WorkspaceFailure("NOT_FOUND")
        }
        override fun readSelected(uri: String, maxBytes: Int) = files[uri] ?: throw WorkspaceFailure("NOT_FOUND")
        override fun createUserFile(treeUri: String?, fileName: String, bytes: ByteArray) {
            if (files.containsKey(fileName)) throw WorkspaceFailure("NAME_CONFLICT")
            writes++; files[fileName] = if (failAfterCreate) bytes.copyOf(minOf(2, bytes.size)) else bytes
            if (failAfterCreate) {
                if (writeFailureCode == "CREATE_FAILED_CLEANED") files.remove(fileName)
                throw WorkspaceFailure(writeFailureCode)
            }
        }
        override fun writeSelectedEmpty(uri: String, bytes: ByteArray) {
            if (files[uri]?.isNotEmpty() == true) throw WorkspaceFailure("NAME_CONFLICT")
            writes++; files[uri] = if (failExport) bytes.copyOf(2) else bytes
            if (failExport) error("write failed")
        }
    }
    private val registry = Registry(); private val io = Io()
    private fun service() = WorkspaceService(registry,io) { it.contains("TEST_SECRET") }

    @Test fun refreshRegisterAndBindNeverScanOrReadFiles() = runTest {
        val service = service(); service.refresh()
        val id = service.register("tree",1)!!
        registry.bindings[1] = "default"; service.bind(1,id); service.refresh()
        assertEquals(0,io.listings); assertEquals(0,io.reads)
        assertEquals(id,registry.bindings[1]); assertEquals("chosen",service.name(id))
        assertEquals("默认工作区",service.name(null))
    }
    @Test fun removeRequiresExplicitReplacementAndNeverReleasesAnActiveGrant() = runTest {
        val service = service(); val id = service.register("tree",1)!!; registry.bindings[1] = id
        assertEquals(WorkspaceRemoval.REQUIRES_REPLACEMENT,service.remove(id)); assertEquals(0,io.released)
        registry.active = 1
        assertEquals(WorkspaceRemoval.BUSY,service.remove(id,"default")); assertEquals(0,io.released)
        registry.active = 0
        assertEquals(WorkspaceRemoval.REMOVED,service.remove(id,"default")); assertEquals(1,io.released)
        assertEquals("default",registry.bindings[1]); assertEquals(WorkspaceRemoval.FAILED,service.remove("default"))
    }
    @Test fun revokedGrantFailsClosedAndChangesSnapshotRevision() = runTest {
        val service = service(); val id = service.register("tree",1)!!; val before = service.version(id)
        io.grants.clear()
        val result = service.execute(id,"read_text_file","{\"path\":\"readme.md\"}",ToolBudget())
        assertFalse(result.success); assertEquals("PERMISSION_REVOKED",result.errorCode); assertEquals(0,io.reads)
        assertTrue(service.version(id)>before)
        assertEquals(WorkspaceStatus.RESELECT,service.state.value.workspaces.last().status)
    }
    @Test fun registrationRejectsKnownSecretInRawUriOrDirectoryNameBeforePersisting() = runTest {
        val service = service()
        assertNull(service.register("tree/TEST_SECRET",1))
        assertTrue(registry.rows.isEmpty()); assertTrue(io.grants.isEmpty())
        io.grantName = "directory TEST_SECRET"
        assertNull(service.register("safe-tree",1))
        assertTrue(registry.rows.isEmpty()); assertTrue(io.grants.isEmpty())
        assertFalse(service.state.value.toString().contains("TEST_SECRET"))
    }
    @Test fun traversalAbsoluteUrisMalformedJsonAndExtraKeysNeverReachIo() = runTest {
        val service = service()
        val invalid = listOf("../private.txt","/absolute.txt","C:\\private.txt","content://tree/file.txt","a//b.txt","a/%2e%2e/b.md")
        for(path in invalid) {
            val args = kotlinx.serialization.json.buildJsonObject { put("path",kotlinx.serialization.json.JsonPrimitive(path)) }.toString()
            assertFalse(service.execute("default","read_text_file",args,ToolBudget()).success)
        }
        for(args in listOf("{}","[]","{\"path\":3}","{\"path\":\"readme.md\",\"uri\":\"x\"}","{\"path\":\"readme.md\",\"path\":\"readme.md\"}"))
            assertFalse(service.execute("default","read_text_file",args,ToolBudget()).success)
        assertEquals(0,io.reads)
    }
    @Test fun listingAndFileAndAggregateLimitsAreEnforced() = runTest {
        val service = service(); io.entries = (1..35).map { WorkspaceEntry("$it.txt",false) }
        val listed = service.execute("default","list_directory","{\"path\":\"\"}",ToolBudget())
        val json = kotlinx.serialization.json.Json.parseToJsonElement(listed.content) as kotlinx.serialization.json.JsonObject
        assertEquals(20,(json["entries"] as kotlinx.serialization.json.JsonArray).size)
        assertTrue(service.execute("default","list_directory","{\"path\":\".\"}",ToolBudget()).success)
        io.files["readme.md"] = ByteArray(8192) { 'a'.code.toByte() }
        val budget = ToolBudget()
        assertTrue(service.execute("default","read_text_file","{\"path\":\"readme.md\"}",budget).success)
        assertTrue(service.execute("default","read_text_file","{\"path\":\"readme.md\"}",budget).success)
        assertEquals("AGGREGATE_LIMIT",service.execute("default","read_text_file","{\"path\":\"readme.md\"}",budget).errorCode)
        io.files["readme.md"] = ByteArray(8193) { 'a'.code.toByte() }
        assertEquals("FILE_TOO_LARGE",service.execute("default","read_text_file","{\"path\":\"readme.md\"}",ToolBudget()).errorCode)
    }
    @Test fun invalidUtf8BinaryAndKnownSecretAreRejectedWithoutLeakingBodies() = runTest {
        val service = service()
        for(bytes in listOf(byteArrayOf(0xc3.toByte(),0x28),byteArrayOf(0,1),"TEST_SECRET".toByteArray())) {
            io.files["readme.md"] = bytes
            val result = service.execute("default","read_text_file","{\"path\":\"readme.md\"}",ToolBudget())
            assertFalse(result.success); assertFalse(result.content.contains("TEST_SECRET")); assertFalse(result.toString().contains("TEST_SECRET"))
        }
        assertFalse(service.execute("default","read_text_file","{\"path\":\"picture.png\"}",ToolBudget()).success)
    }
    @Test fun userImportExportRejectConflictOversizeSecretAndInvalidNames() = runTest {
        val service = service(); io.files["source"] = "plain text".toByteArray()
        assertTrue(service.importText("default","source","new.txt"))
        assertFalse(service.importText("default","source","new.txt"))
        assertFalse(service.importText("default","source","../escape.txt"))
        io.files["source"] = "TEST_SECRET".toByteArray()
        assertFalse(service.importText("default","source","secret.txt")); assertFalse(io.files.containsKey("secret.txt"))
        io.files["source"] = ByteArray(8193)
        assertFalse(service.importText("default","source","large.txt"))
        assertTrue(service.exportText("default","new.txt","selected.txt"))
        assertFalse(service.exportText("default","new.txt","selected.txt"))
        assertEquals(2,io.writes)
    }
    @Test fun successfulImportInvalidatesOnlyItsTargetWorkspaceSnapshot() = runTest {
        val service = service()
        val custom = service.register("tree",1)!!
        val defaultBefore = service.version("default")
        val customBefore = service.version(custom)
        io.files["source"] = "new source".toByteArray()
        assertTrue(service.importText("default","source","new.txt"))
        assertEquals(defaultBefore + 1L,service.version("default"))
        assertEquals(customBefore,service.version(custom))
        assertEquals("new source",io.files["new.txt"]!!.toString(Charsets.UTF_8))
    }
    @Test fun partialFailedImportInvalidatesSnapshotButPreWriteRejectionDoesNot() = runTest {
        val service = service()
        val custom = service.register("tree",1)!!
        val before = service.version(custom)
        val defaultBefore = service.version("default")
        io.files["source"] = "new source".toByteArray()
        io.failAfterCreate = true
        assertFalse(service.importText(custom,"source","partial.txt"))
        assertEquals("ne",io.files["partial.txt"]!!.toString(Charsets.UTF_8))
        assertEquals(before + 1L,service.version(custom))
        assertEquals(defaultBefore,service.version("default"))
        val afterPartial = service.version(custom)
        io.files["source"] = "TEST_SECRET".toByteArray()
        assertFalse(service.importText(custom,"source","blocked.txt"))
        assertFalse(io.files.containsKey("blocked.txt"))
        assertEquals(afterPartial,service.version(custom))
        assertEquals(1,io.writes)
    }

    @Test fun failedImportReportsPossibleResidualInsteadOfOnlyInvalidInput() = runTest {
        val service = service()
        io.files["source"] = "synthetic source".toByteArray()
        io.failAfterCreate = true
        io.writeFailureCode = "CREATE_FAILED_RESIDUAL"
        assertFalse(service.importText("default", "source", "partial.md"))
        assertTrue(service.state.value.notice.orEmpty().contains("残留"))
        assertEquals("sy", io.files["partial.md"]!!.toString(Charsets.UTF_8))
    }

    @Test fun failedImportDistinguishesConfirmedCleanupFromPossibleResidual() = runTest {
        val service = service()
        io.files["source"] = "synthetic source".toByteArray()
        io.failAfterCreate = true
        io.writeFailureCode = "CREATE_FAILED_CLEANED"
        assertFalse(service.importText("default", "source", "partial.md"))
        assertTrue(service.state.value.notice.orEmpty().contains("已清理"))
        assertFalse(io.files.containsKey("partial.md"))
    }

    @Test fun partialExportFailureWarnsOfResidualAndDoesNotDeleteSelectedTarget() = runTest {
        val service = service()
        io.failExport = true
        assertFalse(service.exportText("default", "readme.md", "selected.md"))
        assertTrue(service.state.value.notice.orEmpty().contains("导出未完成"))
        assertTrue(service.state.value.notice.orEmpty().contains("残留"))
        assertEquals("he", io.files.getValue("selected.md").toString(Charsets.UTF_8))
    }
}
