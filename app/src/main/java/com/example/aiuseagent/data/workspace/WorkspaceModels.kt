package com.example.aiuseagent.data.workspace

enum class WorkspaceKind { DEFAULT, SAF }
enum class WorkspaceStatus { AVAILABLE, RESELECT }
enum class WorkspaceRemoval { REMOVED, REQUIRES_REPLACEMENT, BUSY, FAILED }
data class WorkspaceSummary(val id: String, val name: String, val kind: WorkspaceKind,
    val status: WorkspaceStatus, val referencedSessions: Int = 0, val writable: Boolean = true)
data class WorkspaceState(val workspaces: List<WorkspaceSummary> = listOf(DEFAULT_SUMMARY),
    val notice: String? = null)
val DEFAULT_SUMMARY = WorkspaceSummary("default", "默认工作区", WorkspaceKind.DEFAULT, WorkspaceStatus.AVAILABLE)

data class WorkspaceToolResult(val success: Boolean, val content: String, val bytes: Int = 0,
    val errorCode: String? = null) {
    override fun toString(): String = "WorkspaceToolResult(success=$success,bytes=$bytes,errorCode=$errorCode)"
}

/** Per-generation aggregate, shared across all model tool rounds. */
class ToolBudget(private val limit: Int = 16 * 1024) {
    private var used = 0
    @Synchronized fun consume(bytes: Int): Boolean {
        if (bytes < 0 || bytes > limit - used) return false
        used += bytes
        return true
    }
    @Synchronized fun remaining(): Int = limit - used
}

data class WorkspaceGrant(val name: String, val readable: Boolean, val writable: Boolean) {
    override fun toString(): String = "WorkspaceGrant(readable=$readable,writable=$writable)"
}
data class WorkspaceEntry(val name: String, val directory: Boolean)
class WorkspaceFailure(val code: String) : Exception(code)

/** URI handles never leave this platform boundary and are never accepted as tool paths. */
interface WorkspaceIo {
    fun defaultAvailable(): Boolean
    fun takeTree(uri: String, flags: Int): WorkspaceGrant
    fun grant(uri: String): WorkspaceGrant?
    fun release(uri: String)
    fun list(treeUri: String?, segments: List<String>): List<WorkspaceEntry>
    fun read(treeUri: String?, segments: List<String>, maxBytes: Int): ByteArray
    fun readSelected(uri: String, maxBytes: Int): ByteArray
    fun createUserFile(treeUri: String?, fileName: String, bytes: ByteArray)
    fun writeSelectedEmpty(uri: String, bytes: ByteArray)
}
