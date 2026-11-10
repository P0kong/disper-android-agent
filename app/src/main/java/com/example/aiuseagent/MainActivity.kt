package com.example.aiuseagent

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chaquo.python.Python
import com.example.aiuseagent.ui.AppRoot
import com.example.aiuseagent.ui.theme.AiUseAgentTheme

/**
 * 单 Activity 入口。阶段 1 起界面由 [AppRoot] 提供：
 * Compose + Material 3 + ViewModel + StateFlow，数据来自 Room。
 *
 * 仍然保留阶段 0 的 Python 冒烟日志（TAG = Stage0，key = pythonGreeting），
 * 这样在无界面环境下仍可用 adb logcat -s Stage0 核验 Chaquopy 链路。
 */
class MainActivity : ComponentActivity() {
    private companion object {
        const val TAG = "Stage0"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pythonGreeting = runCatching {
            Python.getInstance()
                .getModule("hello")
                .callAttr("greet", "Android AI Use Agent")
                .toString()
        }.getOrElse { "Python call failed: " + (it.message ?: it.toString()) }

        Log.i(TAG, "pythonGreeting=" + pythonGreeting)

        val container = (application as AgentApplication).container

        setContent {
            val theme by container.settingsRepository.theme.collectAsStateWithLifecycle()
            AiUseAgentTheme(preference = theme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppRoot(container = container)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            (application as AgentApplication).container.generationCoordinator.stop("应用进入后台，已停止")
        }
    }

    override fun onDestroy() {
        if (isFinishing) (application as AgentApplication).container.generationCoordinator.stop("任务已关闭")
        super.onDestroy()
    }
}
