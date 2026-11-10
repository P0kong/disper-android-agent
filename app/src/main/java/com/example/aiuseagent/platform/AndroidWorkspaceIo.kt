package com.example.aiuseagent.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.example.aiuseagent.data.workspace.WorkspaceEntry
import com.example.aiuseagent.data.workspace.WorkspaceFailure
import com.example.aiuseagent.data.workspace.WorkspaceGrant
import com.example.aiuseagent.data.workspace.WorkspaceIo
import com.example.aiuseagent.data.workspace.WorkspaceService
import com.example.aiuseagent.data.workspace.NewDocument
import com.example.aiuseagent.data.workspace.NewDocumentStore
import com.example.aiuseagent.data.workspace.SafFileCreation
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files

/** Only the app-private default directory and explicitly persisted SAF trees can be addressed. */
class AndroidWorkspaceIo(context: Context) : WorkspaceIo {
    private val resolver = context.contentResolver
    private val privateFiles = context.filesDir.canonicalFile
    private val defaultRoot = File(privateFiles, "workspaces/default")

    override fun defaultAvailable(): Boolean = runCatching {
        val parent = defaultRoot.parentFile!!
        if (Files.isSymbolicLink(parent.toPath()) || Files.isSymbolicLink(defaultRoot.toPath())) return@runCatching false
        (defaultRoot.isDirectory || defaultRoot.mkdirs()) &&
            defaultRoot.canonicalFile == defaultRoot.absoluteFile && defaultRoot.canRead()
    }.getOrDefault(false)

    override fun takeTree(uri: String, flags: Int): WorkspaceGrant {
        val tree = tree(uri)
        if (flags and Intent.FLAG_GRANT_READ_URI_PERMISSION == 0) fail("PERMISSION_REVOKED")
        val modes = flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        resolver.takePersistableUriPermission(tree, modes)
        return grant(uri) ?: fail("PERMISSION_REVOKED")
    }

    override fun grant(uri: String): WorkspaceGrant? {
        val tree = tree(uri)
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == tree && it.isReadPermission }
            ?: return null
        val root = rootDocument(tree)
        val metadata = metadata(root) ?: return null
        if (metadata.mime != DocumentsContract.Document.MIME_TYPE_DIR) return null
        return WorkspaceGrant(metadata.name, true, permission.isWritePermission &&
            metadata.flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0)
    }

    override fun release(uri: String) {
        val tree = tree(uri)
        val permission = resolver.persistedUriPermissions.firstOrNull { it.uri == tree } ?: return
        val flags = (if (permission.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
            (if (permission.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
        if (flags != 0) resolver.releasePersistableUriPermission(tree, flags)
    }

    override fun list(treeUri: String?, segments: List<String>): List<WorkspaceEntry> {
        if (treeUri == null) {
            val directory = privatePath(segments)
            if (!directory.isDirectory) fail("NOT_DIRECTORY")
            return Files.newDirectoryStream(directory.toPath()).use { stream ->
                buildList {
                    var visited = 0
                    for (path in stream) {
                        if (++visited > 4096) fail("DIRECTORY_LIMIT")
                        if (!Files.isSymbolicLink(path)) add(WorkspaceEntry(path.fileName.toString(), Files.isDirectory(path)))
                        if (size == WorkspaceService.MAX_ENTRIES) break
                    }
                }
            }
        }
        val tree = tree(treeUri)
        val document = resolve(tree, segments)
        if (metadata(document)?.mime != DocumentsContract.Document.MIME_TYPE_DIR) fail("NOT_DIRECTORY")
        return children(tree, document, WorkspaceService.MAX_ENTRIES).map {
            WorkspaceEntry(it.name, it.mime == DocumentsContract.Document.MIME_TYPE_DIR)
        }
    }

    override fun read(treeUri: String?, segments: List<String>, maxBytes: Int): ByteArray {
        if (treeUri == null) {
            val file = privatePath(segments)
            if (!file.isFile) fail("NOT_FILE")
            if (file.length() > maxBytes) fail("FILE_TOO_LARGE")
            return file.inputStream().use { bounded(it, maxBytes) }
        }
        val tree = tree(treeUri)
        val document = resolve(tree, segments)
        val info = metadata(document) ?: fail("UNAVAILABLE")
        if (info.mime == DocumentsContract.Document.MIME_TYPE_DIR) fail("NOT_FILE")
        if (info.size != null && info.size > maxBytes) fail("FILE_TOO_LARGE")
        return resolver.openInputStream(document)?.use { bounded(it, maxBytes) } ?: fail("UNAVAILABLE")
    }

    override fun readSelected(uri: String, maxBytes: Int): ByteArray {
        val selected = Uri.parse(uri)
        if (selected.scheme != "content") fail("INVALID_SELECTION")
        resolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (!it.moveToFirst()) fail("UNAVAILABLE")
            WorkspaceService.checkTextName(it.getString(0))
            if (!it.isNull(1) && it.getLong(1) > maxBytes) fail("FILE_TOO_LARGE")
        } ?: fail("UNAVAILABLE")
        return resolver.openInputStream(selected)?.use { bounded(it, maxBytes) } ?: fail("UNAVAILABLE")
    }

    override fun createUserFile(treeUri: String?, fileName: String, bytes: ByteArray) {
        WorkspaceService.checkTextName(fileName)
        if (treeUri == null) {
            val file = privatePath(listOf(fileName))
            if (!file.createNewFile()) fail("NAME_CONFLICT")
            try { file.outputStream().use { it.write(bytes) } }
            catch (_: Exception) {
                fail(if (file.delete()) "CREATE_FAILED_CLEANED" else "CREATE_FAILED_RESIDUAL")
            }
            return
        }
        val tree = tree(treeUri)
        val root = rootDocument(tree)
        SafFileCreation(object : NewDocumentStore {
            private fun document(id: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, id)
            override fun writable() = grant(treeUri)?.writable == true
            override fun children(): List<NewDocument> {
                val found = this@AndroidWorkspaceIo.children(tree, root, 4097)
                if (found.size > 4096) fail("DIRECTORY_LIMIT")
                return found.map { NewDocument(it.id, it.name, it.size) }
            }
            override fun create(mimeType: String, name: String): String {
                val created = DocumentsContract.createDocument(resolver, root, mimeType, name) ?: fail("UNAVAILABLE")
                if (created.authority != tree.authority) fail("UNTRUSTED_CREATE_RESULT")
                return DocumentsContract.getDocumentId(created)
            }
            override fun metadata(id: String): NewDocument? = this@AndroidWorkspaceIo.metadata(document(id))
                ?.let { NewDocument(it.id, it.name, it.size) }
            override fun writeEmpty(id: String, bytes: ByteArray) = this@AndroidWorkspaceIo.writeEmpty(document(id), bytes)
            override fun delete(id: String) = DocumentsContract.deleteDocument(resolver, document(id))
        }).create(fileName, bytes)
    }

    override fun writeSelectedEmpty(uri: String, bytes: ByteArray) {
        val selected = Uri.parse(uri)
        if (selected.scheme != "content") fail("INVALID_SELECTION")
        resolver.query(selected, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (!it.moveToFirst()) fail("UNAVAILABLE")
            WorkspaceService.checkTextName(it.getString(0))
            if (!it.isNull(1) && it.getLong(1) != 0L) fail("NAME_CONFLICT")
        } ?: fail("UNAVAILABLE")
        writeEmpty(selected, bytes)
    }

    private fun writeEmpty(selected: Uri, bytes: ByteArray) {
        val descriptor = resolver.openFileDescriptor(selected, "rw") ?: fail("UNAVAILABLE")
        descriptor.use { pfd ->
            // Opening rw does not truncate existing content. Unknown/nonzero sizes are refused.
            if (pfd.statSize != 0L) fail("NAME_CONFLICT")
            FileOutputStream(pfd.fileDescriptor).use { it.write(bytes); it.flush() }
        }
    }

    private fun privatePath(segments: List<String>): File {
        if (!defaultAvailable()) fail("UNAVAILABLE")
        var result = defaultRoot
        for (segment in segments) {
            if (!WorkspaceService.safeSegment(segment)) fail("INVALID_PATH")
            result = File(result, segment)
            if (Files.isSymbolicLink(result.toPath()) || result.canonicalFile != result.absoluteFile)
                fail("SYMLINK_ESCAPE")
        }
        if (!result.canonicalFile.toPath().startsWith(defaultRoot.toPath())) fail("INVALID_PATH")
        return result
    }

    private data class Document(val id: String, val name: String, val mime: String, val flags: Int,
        val size: Long?)
    private val columns = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.COLUMN_SIZE)

    private fun metadata(uri: Uri): Document? = resolver.query(uri, columns, null, null, null)?.use {
        if (!it.moveToFirst()) null else Document(it.getString(0), it.getString(1), it.getString(2),
            it.getInt(3), if (it.isNull(4)) null else it.getLong(4))
    }

    private fun children(tree: Uri, parent: Uri, limit: Int): List<Document> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        return resolver.query(children, columns, null, null, null)?.use { cursor ->
            buildList { while (size < limit && cursor.moveToNext()) add(Document(cursor.getString(0),
                cursor.getString(1), cursor.getString(2), cursor.getInt(3),
                if (cursor.isNull(4)) null else cursor.getLong(4))) }
        } ?: fail("UNAVAILABLE")
    }

    private fun findChild(tree: Uri, parent: Uri, name: String): Document? {
        val childUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent))
        var found: Document? = null
        resolver.query(childUri, columns, null, null, null)?.use { cursor ->
            var visited = 0
            while (cursor.moveToNext()) {
                if (++visited > 4096) fail("DIRECTORY_LIMIT")
                if (cursor.getString(1) == name) {
                    if (found != null) fail("AMBIGUOUS_NAME")
                    found = Document(cursor.getString(0), cursor.getString(1), cursor.getString(2),
                        cursor.getInt(3), if (cursor.isNull(4)) null else cursor.getLong(4))
                }
            }
        } ?: fail("UNAVAILABLE")
        return found
    }

    private fun resolve(tree: Uri, segments: List<String>): Uri {
        var current = rootDocument(tree)
        for (segment in segments) {
            if (!WorkspaceService.safeSegment(segment)) fail("INVALID_PATH")
            if (metadata(current)?.mime != DocumentsContract.Document.MIME_TYPE_DIR) fail("NOT_DIRECTORY")
            // Every component must be an actual child returned by the authorized parent query.
            val child = findChild(tree, current, segment) ?: fail("NOT_FOUND")
            current = DocumentsContract.buildDocumentUriUsingTree(tree, child.id)
        }
        return current
    }

    private fun tree(value: String): Uri = Uri.parse(value).also {
        if (it.scheme != "content" || !DocumentsContract.isTreeUri(it)) fail("INVALID_SELECTION")
    }
    private fun rootDocument(tree: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(tree,
        DocumentsContract.getTreeDocumentId(tree))
    private fun bounded(input: InputStream, maxBytes: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = input.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
            if (count < 0) break
            if (count == 0) fail("UNAVAILABLE")
            output.write(buffer, 0, count)
            if (output.size() > maxBytes) fail("FILE_TOO_LARGE")
        }
        return output.toByteArray()
    }
    private fun fail(code: String): Nothing = throw WorkspaceFailure(code)
}
