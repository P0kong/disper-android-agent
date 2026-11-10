package com.example.aiuseagent.data.workspace

import android.content.Context
import com.example.aiuseagent.data.local.AppDatabase
import com.example.aiuseagent.data.local.WorkspaceEntity
import com.example.aiuseagent.platform.AndroidWorkspaceIo
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Explicit, bounded operations only. Refresh and workspace switching never list or open files. */
class WorkspaceService(
    private val registry: WorkspaceRegistry,
    private val io: WorkspaceIo,
    private val containsSecret: (String) -> Boolean = { false },
) {
    constructor(context: Context, database: AppDatabase, containsSecret: (String) -> Boolean = { false }) :
        this(RoomWorkspaceRegistry(database), AndroidWorkspaceIo(context.applicationContext), containsSecret)

    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(WorkspaceState())
    val state: StateFlow<WorkspaceState> = mutableState.asStateFlow()
    private var rows = emptyList<WorkspaceEntity>()
    private val revisions = mutableMapOf<String, Long>()

    fun name(id: String?): String = state.value.workspaces.firstOrNull { it.id == (id ?: DEFAULT_ID) }?.name
        ?: "工作区不可用"

    suspend fun version(id: String): Long = mutex.withLock {
        refreshLocked()
        revisions[id] ?: 0L
    }

    suspend fun refresh() = mutex.withLock { refreshLocked() }

    private suspend fun refreshLocked(notice: String? = null) {
        rows = registry.list()
        val summaries = listOf(DEFAULT_SUMMARY.copy(status = if (io.defaultAvailable())
            WorkspaceStatus.AVAILABLE else WorkspaceStatus.RESELECT)) + rows.map { row ->
            val sensitive = containsSecret(row.treeUri) || containsSecret(row.name)
            val grant = if (sensitive) null else runCatching { io.grant(row.treeUri) }.getOrNull()
            WorkspaceSummary(row.id, if (sensitive) "工作区不可用" else row.name, WorkspaceKind.SAF,
                if (grant?.readable == true) WorkspaceStatus.AVAILABLE else WorkspaceStatus.RESELECT,
                registry.references(row.id), grant?.writable == true)
        }
        val previous = state.value.workspaces.associateBy { it.id }
        for (summary in summaries) {
            val old = previous[summary.id]
            if (old == null || old.status != summary.status || old.writable != summary.writable)
                revisions[summary.id] = (revisions[summary.id] ?: 0) + 1
        }
        (previous.keys - summaries.map { it.id }.toSet()).forEach { revisions[it] = (revisions[it] ?: 0) + 1 }
        mutableState.value = WorkspaceState(summaries, notice)
    }

    suspend fun register(uri: String, flags: Int): String? = mutex.withLock {
        if (containsSecret(uri)) {
            mutableState.value = state.value.copy(notice = "所选目录包含已知凭据，未保存授权登记。")
            return@withLock null
        }
        val existing = registry.list().firstOrNull { it.treeUri == uri }
        val alreadyGranted = runCatching { io.grant(uri)?.readable == true }.getOrDefault(false)
        try {
            val grant = io.takeTree(uri, flags)
            check(grant.readable)
            if (containsSecret(grant.name)) fail("SENSITIVE_CONTENT")
            val safeName = grant.name.filterNot { it.isISOControl() }.take(80).ifBlank { "自定义工作区" }
            val id = existing?.id ?: UUID.randomUUID().toString()
            if (existing == null) registry.add(WorkspaceEntity(id, safeName, uri, System.currentTimeMillis()))
            revisions[id] = (revisions[id] ?: 0) + 1
            refreshLocked()
            id
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            if (!alreadyGranted && existing == null) runCatching { io.release(uri) }
            refreshLocked("无法保存工作区授权，请重新选择。")
            null
        }
    }

    suspend fun bind(sessionId: Long, id: String) = mutex.withLock {
        requireAvailable(id)
        check(registry.sessionExists(sessionId))
        registry.bind(sessionId, id)
        refreshLocked()
    }

    suspend fun remove(id: String, replacementId: String? = null): WorkspaceRemoval = mutex.withLock {
        if (id == DEFAULT_ID) return@withLock WorkspaceRemoval.FAILED
        rows = registry.list()
        val row = rows.firstOrNull { it.id == id } ?: return@withLock WorkspaceRemoval.FAILED
        if (registry.activeReferences(id) != 0) return@withLock WorkspaceRemoval.BUSY
        if (registry.references(id) != 0 && replacementId == null)
            return@withLock WorkspaceRemoval.REQUIRES_REPLACEMENT
        val replacement = replacementId ?: DEFAULT_ID
        if (replacement == id) return@withLock WorkspaceRemoval.FAILED
        try {
            requireAvailable(replacement)
            registry.removeReplacing(id, replacement)
            // Other registry entries sharing a URI retain the grant.
            if (registry.list().none { it.treeUri == row.treeUri }) io.release(row.treeUri)
            revisions[id] = (revisions[id] ?: 0) + 1
            refreshLocked()
            WorkspaceRemoval.REMOVED
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            refreshLocked("无法移除工作区，请重试。")
            WorkspaceRemoval.FAILED
        }
    }

    suspend fun execute(workspaceId: String, name: String, arguments: String,
        budget: ToolBudget): WorkspaceToolResult = mutex.withLock {
        try {
            if (arguments.toByteArray(Charsets.UTF_8).size > 2048) fail("INVALID_ARGUMENTS")
            if (!ARGUMENT_SHAPE.matches(arguments)) fail("INVALID_ARGUMENTS")
            val args = try { Json.parseToJsonElement(arguments) as? JsonObject }
                catch (_: Exception) { fail("INVALID_ARGUMENTS") }
                ?: fail("INVALID_ARGUMENTS")
            if (args.keys != setOf("path")) fail("INVALID_ARGUMENTS")
            val primitive = args["path"] as? JsonPrimitive ?: fail("INVALID_ARGUMENTS")
            if (!primitive.isString) fail("INVALID_ARGUMENTS")
            val path = primitive.content
            val segments = pathSegments(path, name == "list_directory")
            val uri = requireAvailable(workspaceId)
            val result = when (name) {
                "list_directory" -> {
                    val entries = io.list(uri, segments).take(MAX_ENTRIES)
                    if (entries.any { !safeSegment(it.name) || containsSecret(it.name) }) fail("INVALID_NAME")
                    buildJsonObject {
                        put("entries", buildJsonArray {
                            entries.forEach { entry ->
                                add(buildJsonObject {
                                    put("name", entry.name)
                                    put("type", if (entry.directory) "directory" else "file")
                                })
                            }
                        })
                        put("limit", MAX_ENTRIES)
                    }.toString()
                }
                "read_text_file" -> {
                    checkTextName(segments.last())
                    val body = decodeText(io.read(uri, segments, MAX_FILE_BYTES))
                    if (containsSecret(body)) fail("SENSITIVE_CONTENT")
                    body
                }
                else -> fail("UNKNOWN_TOOL")
            }
            val bytes = result.toByteArray(Charsets.UTF_8).size
            if (!budget.consume(bytes)) fail("AGGREGATE_LIMIT")
            WorkspaceToolResult(true, result, bytes)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val code = (failure as? WorkspaceFailure)?.code ?: "UNAVAILABLE"
            WorkspaceToolResult(false, "{\"error\":\"$code\"}", errorCode = code)
        }
    }

    suspend fun importText(workspaceId: String, sourceUri: String, fileName: String): Boolean = mutex.withLock {
        userOperation {
            checkTextName(fileName)
            if (!safeSegment(fileName) || containsSecret(fileName)) fail("INVALID_NAME")
            val bytes = io.readSelected(sourceUri, MAX_FILE_BYTES)
            val text = decodeText(bytes)
            if (containsSecret(text)) fail("SENSITIVE_CONTENT")
            val destination = requireAvailable(workspaceId)
            try {
                io.createUserFile(destination, fileName, bytes)
            } finally {
                // A provider may create a partial file before throwing. Conservatively invalidate
                // every attempted creation, while pre-write validation failures leave the version alone.
                revisions[workspaceId] = (revisions[workspaceId] ?: 0L) + 1L
            }
        }
    }

    suspend fun exportText(workspaceId: String, path: String, destinationUri: String): Boolean = mutex.withLock {
        userOperation {
            val segments = pathSegments(path, false)
            checkTextName(segments.last())
            val bytes = io.read(requireAvailable(workspaceId), segments, MAX_FILE_BYTES)
            if (containsSecret(decodeText(bytes))) fail("SENSITIVE_CONTENT")
            try {
                io.writeSelectedEmpty(destinationUri, bytes)
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                // NAME_CONFLICT is a pre-write refusal. Other destination failures can occur
                // after partial output or while closing it; the picker does not prove ownership.
                if ((failure as? WorkspaceFailure)?.code == "NAME_CONFLICT") throw failure
                fail("EXPORT_FAILED_RESIDUAL")
            }
        }
    }

    private suspend fun userOperation(block: suspend () -> Unit): Boolean = try {
        block(); mutableState.value = state.value.copy(notice = "操作完成。")
        true
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        val notice = when ((failure as? WorkspaceFailure)?.code) {
            "CREATE_FAILED_CLEANED" -> "导入未完成：本次新建文件已清理，已有文件未被覆盖。"
            "CREATE_FAILED_RESIDUAL" -> "导入未完成：目录可能残留本次新建的不完整文件，请检查后再重试。未删除归属不确定的文件。"
            "EXPORT_FAILED_RESIDUAL" -> "导出未完成：所选目标可能残留不完整内容，请检查后再重试。未自动删除所选文件。"
            "UNTRUSTED_CREATE_RESULT" -> "导入未完成：文件提供者返回了无法确认的新文件，未写入或删除该文件，请检查目录。"
            "READ_ONLY", "PERMISSION_REVOKED" -> "操作未完成：目录为只读或授权已失效，请重新选择并授予所需权限。"
            "NAME_CONFLICT" -> "操作未完成：文件名已被使用或目标不为空，已有文件未被覆盖。"
            else -> "操作未完成：请选择有效的 UTF-8 TXT/md 文件（最多 8 KiB），并确认文件名未被使用及目录授权有效。"
        }
        mutableState.value = state.value.copy(notice = notice)
        false
    }

    private suspend fun requireAvailable(id: String): String? {
        if (id == DEFAULT_ID) {
            if (!io.defaultAvailable()) fail("UNAVAILABLE")
            return null
        }
        val row = registry.list().firstOrNull { it.id == id } ?: fail("UNAVAILABLE")
        if (containsSecret(row.treeUri) || containsSecret(row.name)) fail("SENSITIVE_CONTENT")
        if (io.grant(row.treeUri)?.readable != true) fail("PERMISSION_REVOKED")
        return row.treeUri
    }

    companion object {
        const val DEFAULT_ID = "default"
        const val MAX_FILE_BYTES = 8 * 1024
        const val MAX_ENTRIES = 20
        private val ARGUMENT_SHAPE = Regex("""^\s*\{\s*"path"\s*:\s*"(?:[^"\\]|\\.)*"\s*\}\s*$""")
        fun pathSegments(path: String, allowRoot: Boolean): List<String> {
            if ((path.isEmpty() || path == ".") && allowRoot) return emptyList()
            if (path.length > 1024 || path.startsWith('/') || path.startsWith('\\') ||
                path.contains('\\') || path.contains(':') || path.contains('%')) fail("INVALID_PATH")
            val segments = path.split('/')
            if (segments.any { !safeSegment(it) }) fail("INVALID_PATH")
            return segments
        }
        fun safeSegment(name: String): Boolean = name.isNotBlank() && name != "." && name != ".." &&
            name.length <= 180 && name.none { it == '/' || it == '\\' || it == ':' || it == '%' || it.isISOControl() }
        fun checkTextName(name: String) {
            if (!safeSegment(name) || (!name.endsWith(".txt", true) && !name.endsWith(".md", true))) fail("TEXT_ONLY")
        }
        fun decodeText(bytes: ByteArray): String {
            if (bytes.size > MAX_FILE_BYTES) fail("FILE_TOO_LARGE")
            val text = try { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            catch (_: Exception) { fail("INVALID_UTF8") }
            if (text.any { it == '\u0000' || (it.isISOControl() && it !in "\t\r\n") }) fail("BINARY_FILE")
            return text
        }
        private fun fail(code: String): Nothing = throw WorkspaceFailure(code)
    }
}
