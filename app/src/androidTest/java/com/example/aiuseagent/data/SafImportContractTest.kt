package com.example.aiuseagent.data

import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.aiuseagent.data.workspace.WorkspaceFailure
import com.example.aiuseagent.platform.AndroidWorkspaceIo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Requires an explicitly selected, empty synthetic SAF root named AAUA_T01_*. */
@RunWith(AndroidJUnit4::class)
class SafImportContractTest {
    @Test fun txtAndMarkdownNamesContentAndConflictArePreserved(): Unit {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val treeText = InstrumentationRegistry.getArguments().getString("safTree")
            ?: throw AssertionError("Device preparation required: pass safTree for an AAUA_T01_ synthetic directory")
        val tree = Uri.parse(treeText)
        val io = AndroidWorkspaceIo(context)
        val grant = requireNotNull(io.grant(treeText))
        assertTrue("Only an explicitly granted synthetic directory may be tested", grant.name.startsWith("AAUA_T01_"))
        assertTrue(grant.writable)
        val prefix = "transfer-${UUID.randomUUID()}"
        val root = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(root))
        val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        fun ownedDocuments(): List<Pair<String, String>> = context.contentResolver.query(childUri, columns, null, null, null)!!.use { cursor ->
            buildList { while (cursor.moveToNext()) if (cursor.getString(1).startsWith(prefix)) add(cursor.getString(0) to cursor.getString(1)) }
        }
        assertTrue(ownedDocuments().isEmpty())
        try {
            for (extension in listOf("txt", "md")) {
                val name = "$prefix.$extension"
                val bytes = "Synthetic UTF-8 $extension fixture.\n中文。\n".toByteArray()
                io.createUserFile(treeText, name, bytes)
                assertTrue(io.list(treeText, emptyList()).any { it.name == name })
                assertArrayEquals(bytes, io.read(treeText, listOf(name), 8192))
                try { io.createUserFile(treeText, name, "replacement".toByteArray()); fail("Conflict must be refused") }
                catch (failure: WorkspaceFailure) { assertEquals("NAME_CONFLICT", failure.code) }
                assertArrayEquals(bytes, io.read(treeText, listOf(name), 8192))
                Log.i("SafImportProbe", "extension=$extension exactName=true contentEqual=true conflictPreserved=true")
            }
        } finally {
            // Only this test's collision-resistant prefix in the explicitly synthetic root.
            for ((id, _) in ownedDocuments()) {
                check(DocumentsContract.deleteDocument(context.contentResolver,
                    DocumentsContract.buildDocumentUriUsingTree(tree, id)))
            }
        }
    }
}
