package com.example.aiuseagent.ui.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiuseagent.data.provider.update.RemoteRelease
import com.example.aiuseagent.data.provider.update.UpdateOutcome
import com.example.aiuseagent.data.update.UpdateRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data object Checking : UpdateUiState
    data object UpToDate : UpdateUiState
    data class Available(val release: RemoteRelease) : UpdateUiState
    data class Failed(val reason: UpdateOutcome.Reason) : UpdateUiState
}

/**
 * 设置/关于页与启动提醒共用的唯一更新状态机。
 * 自动检查静默执行，只在发现新版本且未被忽略时写入 [autoReminder]；
 * 手动检查的结果写入 [state] 供关于页按钮展示。
 */
class UpdateViewModel(
    private val repository: UpdateRepository,
    private val dismissedTag: () -> String? = { null },
    private val onDismissTag: (String) -> Unit = {},
) : ViewModel() {
    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _autoReminder = MutableStateFlow<RemoteRelease?>(null)
    val autoReminder: StateFlow<RemoteRelease?> = _autoReminder.asStateFlow()

    private var job: Job? = null

    /** 冷启动调用；24h 节流 + 单飞，失败完全静默。 */
    fun autoCheck() {
        if (!repository.shouldAutoCheck()) return
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            val outcome = repository.check(manual = false)
            if (outcome is UpdateOutcome.Available && outcome.release.tag != dismissedTag()) {
                _autoReminder.value = outcome.release
            }
        }
    }

    /** 手动检查不受节流限制，但同一请求未结束时忽略重复点击。 */
    fun manualCheck() {
        if (job?.isActive == true || _state.value == UpdateUiState.Checking) return
        job = viewModelScope.launch {
            _state.value = UpdateUiState.Checking
            _state.value = when (val outcome = repository.check(manual = true)) {
                UpdateOutcome.UpToDate -> UpdateUiState.UpToDate
                is UpdateOutcome.Available -> UpdateUiState.Available(outcome.release)
                is UpdateOutcome.Unavailable -> UpdateUiState.Failed(outcome.reason)
            }
        }
    }

    /** 自动提醒点“稍后”：记录 tag，之后同一版本不再自动弹出。 */
    fun dismissAutoReminder() {
        _autoReminder.value?.let { onDismissTag(it.tag) }
        _autoReminder.value = null
    }

    /** 关于页手动弹窗点“稍后”。 */
    fun dismissAvailable() {
        (_state.value as? UpdateUiState.Available)?.let { onDismissTag(it.release.tag) }
        _state.value = UpdateUiState.UpToDate
    }

    fun clearFailure() {
        if (_state.value is UpdateUiState.Failed) _state.value = UpdateUiState.Idle
    }
}
