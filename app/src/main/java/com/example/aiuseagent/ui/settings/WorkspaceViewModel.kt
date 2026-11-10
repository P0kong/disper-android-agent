package com.example.aiuseagent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiuseagent.data.workspace.WorkspaceRemoval
import com.example.aiuseagent.data.workspace.WorkspaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.example.aiuseagent.ui.i18n.tr

/** ActivityResult 回调只提交明确选择，IO 和登记操作在进程内串行执行。 */
class WorkspaceViewModel(
    private val service: WorkspaceService,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val operationMutex = Mutex()
    private val operationNotice = MutableStateFlow<String?>(null)
    private val mutableBusy = MutableStateFlow(false)
    private var pendingOperations = 0
    val busy = mutableBusy.asStateFlow()
    val state = combine(service.state, operationNotice) { current, notice ->
        current.copy(notice = notice ?: current.notice)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, service.state.value)

    init { refresh() }

    fun refresh() {
        if (!mutableBusy.value) operation { withContext(ioDispatcher) { service.refresh() } }
    }

    fun register(uri: String, flags: Int, onRegistered: (String) -> Unit = {}) = operation {
        val registered = withContext(ioDispatcher) { service.register(uri, flags) }
        if (registered != null) onRegistered(registered)
    }

    fun remove(id: String, replacementId: String? = null, onRemoved: () -> Unit = {}) = operation {
        when (withContext(ioDispatcher) { service.remove(id, replacementId) }) {
            WorkspaceRemoval.REMOVED -> { operationNotice.value = tr("工作区登记已移除，目录内文件保留。", "Workspace entry removed; files in the folder are kept."); onRemoved() }
            WorkspaceRemoval.REQUIRES_REPLACEMENT -> operationNotice.value = tr("此工作区仍被会话使用，请选择替代工作区后重新确认。", "This workspace is still used by sessions. Choose a replacement workspace and confirm again.")
            WorkspaceRemoval.BUSY -> operationNotice.value = tr("此工作区正在用于生成，请先停止回复再移除。", "This workspace is generating a reply. Stop it before removing.")
            WorkspaceRemoval.FAILED -> operationNotice.value = tr("未能移除工作区，请确认授权和替代工作区后重试。", "Couldn't remove the workspace. Confirm the permission and replacement workspace, then try again.")
        }
    }

    fun importText(workspaceId: String, sourceUri: String, fileName: String) = operation {
        withContext(ioDispatcher) { service.importText(workspaceId, sourceUri, fileName) }
    }

    fun exportText(workspaceId: String, path: String, destinationUri: String) = operation {
        withContext(ioDispatcher) { service.exportText(workspaceId, path, destinationUri) }
    }

    private fun operation(action: suspend () -> Unit) {
        // Accepted picker callbacks queue behind resume refresh; they must not be silently dropped.
        pendingOperations++
        mutableBusy.value = true
        viewModelScope.launch {
            try {
                operationMutex.withLock { operationNotice.value = null; action() }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                operationNotice.value = tr("操作未完成，请确认目录授权后重试。", "The operation didn't finish. Confirm folder permission and try again.")
            } finally {
                pendingOperations--
                mutableBusy.value = pendingOperations > 0
            }
        }
    }
}
