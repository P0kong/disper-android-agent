package com.example.aiuseagent.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SavedWorkFolder(val uri: String, val name: String) {
    override fun toString(): String = "SavedWorkFolder(fields=omitted)"
}

interface WorkFolderStore {
    fun read(): SavedWorkFolder?
    fun save(folder: SavedWorkFolder?)
}

data class FolderGrant(val read: Boolean, val write: Boolean)

/** 只允许授权和所选目录自身的名称元数据；无列举子项或读写内容接口。 */
interface DirectoryAccess {
    fun take(uri: String, flags: Int)
    fun grant(uri: String): FolderGrant?
    fun displayName(uri: String): String?
    fun release(uri: String)
}

enum class FolderStatus { NONE, CHECKING, AUTHORIZED, RESELECT }
data class WorkFolderState(
    val name: String? = null,
    val status: FolderStatus = FolderStatus.NONE,
    val writable: Boolean = false,
    val notice: String? = null,
)

/** 由 ViewModel 在 IO 调度器串行调用；错误只返回固定提示，不回显 URI 或异常正文。 */
class WorkFolderManager(private val store: WorkFolderStore, private val access: DirectoryAccess) {
    private var selected = store.read()
    private val mutableState = MutableStateFlow(
        WorkFolderState(selected?.name, if (selected == null) FolderStatus.NONE else FolderStatus.CHECKING),
    )
    val state: StateFlow<WorkFolderState> = mutableState.asStateFlow()

    fun refresh() {
        val folder = selected ?: return
        val grant = runCatching { access.grant(folder.uri) }.getOrNull()
        val exists = grant?.read == true && runCatching { access.displayName(folder.uri) }.getOrNull() != null
        mutableState.value = WorkFolderState(
            folder.name, if (exists) FolderStatus.AUTHORIZED else FolderStatus.RESELECT,
            writable = exists && grant?.write == true,
        )
    }

    fun select(uri: String?, flags: Int) {
        if (uri == null) return // 系统选择器取消，不改变已有状态和授权。
        val previous = selected
        val alreadyGranted = runCatching { access.grant(uri) }.getOrNull() != null
        try {
            access.take(uri, flags)
            val grant = access.grant(uri)
            check(grant?.read == true)
            val name = access.displayName(uri)?.takeIf { it.isNotBlank() }?.take(80)
                ?: error("Missing folder metadata")
            if (previous != null && previous.uri != uri) access.release(previous.uri)
            val next = SavedWorkFolder(uri, name)
            store.save(next)
            selected = next
            mutableState.value = WorkFolderState(name, FolderStatus.AUTHORIZED, grant.write)
        } catch (_: Exception) {
            if (!alreadyGranted && previous?.uri != uri) runCatching { access.release(uri) }
            refresh()
            mutableState.value = mutableState.value.copy(notice = "未能保存目录授权，请重新选择。")
        }
    }

    fun remove() {
        val previous = selected ?: return
        try {
            access.release(previous.uri)
            store.save(null)
            selected = null
            mutableState.value = WorkFolderState()
        } catch (_: Exception) {
            refresh()
            mutableState.value = mutableState.value.copy(notice = "暂时无法移除授权，请重试。")
        }
    }
}
