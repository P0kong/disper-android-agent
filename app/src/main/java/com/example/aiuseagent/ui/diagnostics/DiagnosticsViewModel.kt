package com.example.aiuseagent.ui.diagnostics

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.aiuseagent.domain.CapabilityCatalog
import com.example.aiuseagent.domain.FeatureEntry
import com.example.aiuseagent.domain.FeatureState
import com.example.aiuseagent.platform.AndroidStatusInspector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.aiuseagent.ui.i18n.tr

/** Python 运行时的真实检测结果。 */
sealed interface PythonStatus {
    /** 尚未检测（不显示为成功）。 */
    data object Checking : PythonStatus

    /** 检测成功，summary 来自 Python 返回的字符串。 */
    data class Ready(val summary: String) : PythonStatus

    /** 检测失败，message 是原始异常文本。 */
    data class Failed(val message: String) : PythonStatus
}

data class DiagnosticsState(
    val refreshedAt: Long = 0L,
    val appVersion: String = "",
    val appVersionCode: Long = 0L,
    val androidVersion: String = "",
    val deviceModel: String = "",
    val abis: String = "",
    val database: List<Pair<String, String>> = emptyList(),
    val python: PythonStatus = PythonStatus.Checking,
    val permissions: List<FeatureEntry> = emptyList(),
    val features: List<FeatureEntry> = emptyList(),
)

/**
 * 诊断页状态。所有字段都来自真实调用；
 * 没有做过的检测（例如 root / 调试器检测）一律标为 UNKNOWN（显示「未验证」）。
 */
class DiagnosticsViewModel(
    private val inspector: AndroidStatusInspector,
    private val databasePath: String,
) : ViewModel() {

    private val _state = MutableStateFlow(DiagnosticsState())
    val state: StateFlow<DiagnosticsState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val version = inspector.appVersion()
        _state.value = DiagnosticsState(
            refreshedAt = System.currentTimeMillis(),
            appVersion = version.first,
            appVersionCode = version.second,
            androidVersion = android.os.Build.VERSION.RELEASE + " (API " + android.os.Build.VERSION.SDK_INT + ")",
            deviceModel = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL,
            abis = android.os.Build.SUPPORTED_ABIS.joinToString(", "),
            database = inspector.databaseFileSummary(databasePath),
            python = PythonStatus.Checking,
            permissions = inspector.permissionEntries(),
            features = CapabilityCatalog.planned() + envChecks(),
        )

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { checkPython() }
            _state.value = _state.value.copy(python = result)
        }
    }

    private fun checkPython(): PythonStatus = runCatching {
        val module = com.chaquo.python.Python.getInstance().getModule("hello")
        val summary = module.callAttr("interpreter_summary").toString()
        PythonStatus.Ready(summary)
    }.getOrElse { error ->
        PythonStatus.Failed(error::class.java.simpleName + ": " + (error.message ?: "no message"))
    }

    /** 明确列出「没做过」的检测，避免读者误以为已经验证。 */
    private fun envChecks(): List<FeatureEntry> = listOf(
        FeatureEntry(
            name = tr("安全检测（root / 模拟器 / 调试器）", "Security checks (root / emulator / debugger)"),
            state = FeatureState.UNKNOWN,
            detail = tr("应用内没有实现任何安全检测，因此这里标记为未验证，而不是「通过」。", "The app implements no security checks, so this is marked unverified rather than \"passed\"."),
        ),
        FeatureEntry(
            name = tr("真机运行验证", "Real-device run verification"),
            state = FeatureState.UNKNOWN,
            detail = tr("是否在 Android 13+ 真机/模拟器上运行过，需要在设备上实测；构建成功不代表运行成功。", "Whether it has run on an Android 13+ device or emulator requires on-device testing; a successful build doesn't mean it runs."),
        ),
    )
}
