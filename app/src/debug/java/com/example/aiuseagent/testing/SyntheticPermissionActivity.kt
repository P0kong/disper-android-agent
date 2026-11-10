package com.example.aiuseagent.testing

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.example.aiuseagent.data.execution.PermissionLevel
import com.example.aiuseagent.ui.chat.PermissionDialog
import com.example.aiuseagent.ui.chat.OperationRecoveryDialog
import com.example.aiuseagent.data.execution.*

/** Debug-only fixture: production dialog, no repository, history or persisted permission. */
class SyntheticPermissionActivity : ComponentActivity() {
    var selected = PermissionLevel.VIEW_ONLY
        private set
    var callbacks = 0
        private set
    var imeObserved = false
        private set

    @OptIn(ExperimentalLayoutApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme {
                    val visible = WindowInsets.isImeVisible
                    SideEffect { if (visible) imeObserved = true }
                    var text by remember { mutableStateOf("") }
                    var show by remember { mutableStateOf(false) }
                    var review by remember { mutableStateOf(false) }
                    Column(Modifier.safeDrawingPadding().imePadding()) {
                        Text("合成权限测试")
                        Text(if (visible) "合成键盘已显示" else "合成键盘未显示")
                        OutlinedTextField(text, { text = it }, label = { Text("合成输入") },
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false))
                        TextButton(onClick = { show = true }) { Text("打开权限") }
                        TextButton(onClick = { review = true }) { Text("合成操作核查") }
                        if(review) OperationRecoveryDialog(listOf(OperationRecoveryReview("synthetic-operation","UNKNOWN",null,"OBSERVED",
                            FileRecoveryInspection(FileRecoveryTarget("a.txt","MISSING","abc"),"abc",true,true,null,null))),onDismiss={review=false})
                        if (show) PermissionDialog(selected,
                            onSelect = { level, _ -> selected = level; callbacks++; show = false },
                            onDismiss = { show = false })
                    }
                }
            }
        }
    }
}
