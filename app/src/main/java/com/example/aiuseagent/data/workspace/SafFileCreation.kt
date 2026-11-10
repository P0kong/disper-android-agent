package com.example.aiuseagent.data.workspace

/** Metadata-only boundary; implementations never expose handles to the model. */
data class NewDocument(val id: String, val name: String, val size: Long?)

interface NewDocumentStore {
    fun writable(): Boolean
    fun children(): List<NewDocument>
    fun create(mimeType: String, name: String): String
    fun metadata(id: String): NewDocument?
    fun writeEmpty(id: String, bytes: ByteArray)
    fun delete(id: String): Boolean
}

/** Create-only transfer: never write or clean an identity which existed before this call. */
class SafFileCreation(private val store: NewDocumentStore) {
    fun create(name: String, bytes: ByteArray): String {
        WorkspaceService.checkTextName(name)
        if (!store.writable()) throw WorkspaceFailure("READ_ONLY")
        val before = store.children()
        if (before.any { it.name == name }) throw WorkspaceFailure("NAME_CONFLICT")
        val id = try { store.create(textMimeType(name), name) }
            catch (_: Exception) { throw WorkspaceFailure("CREATE_FAILED_RESIDUAL") }
        if (before.any { it.id == id }) throw WorkspaceFailure("UNTRUSTED_CREATE_RESULT")
        try {
            val info = store.metadata(id) ?: throw WorkspaceFailure("CREATE_FAILED_RESIDUAL")
            if (info.id != id || store.children().none { it.id == id && it.name == info.name })
                throw WorkspaceFailure("UNTRUSTED_CREATE_RESULT")
            // A provider may race or return an alias. Nonempty/unknown files are never ours to truncate.
            if (info.size != 0L) throw WorkspaceFailure("CREATE_FAILED_RESIDUAL")
            if (info.name != name) throw WorkspaceFailure("PROVIDER_RENAMED")
            store.writeEmpty(id, bytes)
            return info.name
        } catch (_: Exception) {
            // SAF has no atomic compare-and-delete. Even an unchanged ID/name/size cannot prove
            // exclusive ownership after a failed write or query; leave uncertain content intact.
            throw WorkspaceFailure("CREATE_FAILED_RESIDUAL")
        }
    }
}

fun textMimeType(name: String): String {
    WorkspaceService.checkTextName(name)
    return if (name.endsWith(".md", ignoreCase = true)) "text/markdown" else "text/plain"
}
