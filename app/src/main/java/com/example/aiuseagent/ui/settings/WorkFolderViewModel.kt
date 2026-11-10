package com.example.aiuseagent.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiuseagent.data.WorkFolderManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class WorkFolderViewModel(private val manager: WorkFolderManager) : ViewModel() {
    val state = manager.state
    private val mutex = Mutex()
    init { refresh() }
    fun refresh() = run { manager.refresh() }
    fun select(uri: String?, flags: Int) = run { manager.select(uri, flags) }
    fun remove() = run { manager.remove() }
    private fun run(action: () -> Unit) {
        viewModelScope.launch { mutex.withLock { withContext(Dispatchers.IO) { action() } } }
    }
}
