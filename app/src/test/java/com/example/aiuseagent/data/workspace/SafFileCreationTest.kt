package com.example.aiuseagent.data.workspace

import org.junit.Assert.*
import org.junit.Test

class SafFileCreationTest {
    private class Provider : NewDocumentStore {
        val documents = linkedMapOf<String, Pair<String, ByteArray>>()
        var canWrite = true
        var failWrite = false
        var allowDelete = true
        var returnedId: String? = null
        var changeIdentityAfterWrite = false
        var foreignWriteBeforeOpenFailure = false
        var failMetadataAfterCreate = false
        var failChildrenAfterCreate = false
        var writes = 0
        var deletes = 0
        override fun writable() = canWrite
        override fun children(): List<NewDocument> {
            if (failChildrenAfterCreate && documents.containsKey("new")) error("provider query failed")
            return documents.map { (id, value) -> NewDocument(id, value.first, value.second.size.toLong()) }
        }
        override fun create(mimeType: String, name: String): String {
            returnedId?.let { return it }
            // Models the reported provider boundary: a mismatched MIME adds .txt.
            val actual = if (name.endsWith(".md") && mimeType == "text/plain") "$name.txt" else name
            documents["new"] = actual to byteArrayOf()
            return "new"
        }
        override fun metadata(id: String): NewDocument? {
            if (failMetadataAfterCreate && documents.containsKey("new")) error("provider metadata failed")
            return documents[id]?.let { NewDocument(id, it.first, it.second.size.toLong()) }
        }
        override fun writeEmpty(id: String, bytes: ByteArray) {
            val current = documents.getValue(id)
            if (foreignWriteBeforeOpenFailure) {
                documents[id] = current.first to "foreign".toByteArray()
                error("open failed after another writer changed the file")
            }
            if (current.second.isNotEmpty()) throw WorkspaceFailure("NAME_CONFLICT")
            writes++
            documents[id] = current.first to if (failWrite) bytes.copyOf(2) else bytes
            if (changeIdentityAfterWrite) documents[id] = "someone-else.txt" to "foreign".toByteArray()
            if (failWrite) throw WorkspaceFailure("UNAVAILABLE")
        }
        override fun delete(id: String): Boolean {
            deletes++
            if (!allowDelete) return false
            return documents.remove(id) != null
        }
    }

    @Test fun markdownImportRetainsMarkdownNameAndBytes() {
        val provider = Provider()
        assertEquals("notes.md", SafFileCreation(provider).create("notes.md", "# synthetic".toByteArray()))
        assertEquals("notes.md", provider.documents.getValue("new").first)
        assertEquals("# synthetic", provider.documents.getValue("new").second.toString(Charsets.UTF_8))
    }

    @Test fun emptyExistingDocumentReturnedByProviderIsNeverWrittenOrDeleted() {
        val provider = Provider()
        provider.documents["old"] = "other.txt" to byteArrayOf()
        provider.returnedId = "old"
        expectFailure("UNTRUSTED_CREATE_RESULT") { SafFileCreation(provider).create("new.txt", "new".toByteArray()) }
        assertEquals(0, provider.writes)
        assertEquals(0, provider.deletes)
        assertArrayEquals(byteArrayOf(), provider.documents.getValue("old").second)
    }

    @Test fun failedWritePreservesResidualWhenProviderCannotProveExclusiveOwnership() {
        val provider = Provider()
        provider.documents["old"] = "old.txt" to "keep".toByteArray()
        provider.failWrite = true
        expectFailure("CREATE_FAILED_RESIDUAL") { SafFileCreation(provider).create("new.txt", "content".toByteArray()) }
        assertEquals("co", provider.documents.getValue("new").second.toString(Charsets.UTF_8))
        assertEquals(0, provider.deletes)
        assertEquals("keep", provider.documents.getValue("old").second.toString(Charsets.UTF_8))
    }

    @Test fun failedCleanupReportsResidualWithoutClaimingRollback() {
        val provider = Provider().apply { failWrite = true; allowDelete = false }
        expectFailure("CREATE_FAILED_RESIDUAL") { SafFileCreation(provider).create("new.txt", "content".toByteArray()) }
        assertEquals("co", provider.documents.getValue("new").second.toString(Charsets.UTF_8))
    }

    @Test fun identityChangePreventsCleanupOfAnUncertainDocument() {
        val provider = Provider().apply { failWrite = true; changeIdentityAfterWrite = true }
        expectFailure("CREATE_FAILED_RESIDUAL") { SafFileCreation(provider).create("new.txt", "content".toByteArray()) }
        assertEquals(0, provider.deletes)
        assertEquals("foreign", provider.documents.getValue("new").second.toString(Charsets.UTF_8))
    }

    @Test fun readOnlyAndNameConflictHaveNoCreateWriteOrDeleteEffects() {
        val provider = Provider().apply { canWrite = false }
        expectFailure("READ_ONLY") { SafFileCreation(provider).create("new.txt", byteArrayOf()) }
        assertTrue(provider.documents.isEmpty())
        provider.canWrite = true
        provider.documents["old"] = "new.txt" to "keep".toByteArray()
        expectFailure("NAME_CONFLICT") { SafFileCreation(provider).create("new.txt", byteArrayOf()) }
        assertEquals(1, provider.documents.size)
        assertEquals(0, provider.writes)
        assertEquals(0, provider.deletes)
    }

    @Test fun sameIdentityWithConcurrentContentIsNeverDeletedAfterOpenFailure() {
        val provider = Provider().apply { foreignWriteBeforeOpenFailure = true }
        expectFailure("CREATE_FAILED_RESIDUAL") { SafFileCreation(provider).create("new.txt", "ours".toByteArray()) }
        assertEquals("foreign", provider.documents.getValue("new").second.toString(Charsets.UTF_8))
        assertEquals(0, provider.deletes)
    }

    @Test fun postCreateMetadataFailureReportsResidualAndPreservesDocument() {
        val provider = Provider().apply { failMetadataAfterCreate = true }
        expectFailure("CREATE_FAILED_RESIDUAL") { SafFileCreation(provider).create("new.txt", "ours".toByteArray()) }
        assertTrue(provider.documents.containsKey("new"))
        assertEquals(0, provider.writes)
        assertEquals(0, provider.deletes)
    }

    @Test fun postCreateEnumerationFailureReportsResidualAndPreservesDocument() {
        val provider = Provider().apply { failChildrenAfterCreate = true }
        expectFailure("CREATE_FAILED_RESIDUAL") { SafFileCreation(provider).create("new.txt", "ours".toByteArray()) }
        assertTrue(provider.documents.containsKey("new"))
        assertEquals(0, provider.writes)
        assertEquals(0, provider.deletes)
    }

    private fun expectFailure(code: String, action: () -> Unit) {
        try { action(); fail("Expected $code") }
        catch (failure: WorkspaceFailure) { assertEquals(code, failure.code) }
    }
}
