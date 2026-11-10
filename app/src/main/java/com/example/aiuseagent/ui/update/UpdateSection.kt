package com.example.aiuseagent.ui.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiuseagent.data.provider.update.UpdateOutcome
import com.example.aiuseagent.ui.i18n.tr

/**
 * 关于页只保留一个“检查更新”按钮：
 * 点击后检查；结果通过轻量对话框反馈（发现新版时可前往 GitHub）。
 * 自动检查保持静默，不弹出任何界面。
 */
@Composable
fun UpdateCheckButton(viewModel: UpdateViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var manualRequested by remember { mutableStateOf(false) }
    var openFailed by remember { mutableStateOf(false) }

    Button(
        onClick = {
            manualRequested = true
            openFailed = false
            viewModel.manualCheck()
        },
        enabled = state != UpdateUiState.Checking,
        shape = CircleShape,
        modifier = modifier.fillMaxWidth().height(48.dp),
    ) {
        Text(
            if (state == UpdateUiState.Checking) tr("检查中…", "Checking…")
            else tr("检查更新", "Check for updates"),
        )
    }

    val available = state as? UpdateUiState.Available
    if (manualRequested && available != null) {
        AlertDialog(
            onDismissRequest = {
                manualRequested = false
                viewModel.dismissAvailable()
            },
            title = { Text(tr("发现新版本", "Update available")) },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text(
                        available.release.version.display() + " · " + available.release.name,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (available.release.body.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(available.release.body, style = MaterialTheme.typography.bodySmall)
                    }
                    if (openFailed) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            tr("无法打开浏览器，请手动访问 GitHub Releases。", "Couldn't open a browser. Visit GitHub Releases manually."),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    openFailed = !openRelease(context, available.release.htmlUrl)
                }) {
                    Text(tr("前往 GitHub", "View on GitHub"))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    manualRequested = false
                    viewModel.dismissAvailable()
                }) {
                    Text(tr("稍后", "Later"))
                }
            },
        )
    } else if (manualRequested && state is UpdateUiState.UpToDate) {
        AlertDialog(
            onDismissRequest = { manualRequested = false },
            title = { Text(tr("检查更新", "Check for updates")) },
            text = { Text(tr("已是最新版本。", "You're on the latest version.")) },
            confirmButton = { TextButton(onClick = { manualRequested = false }) { Text(tr("好", "OK")) } },
        )
    } else if (manualRequested && state is UpdateUiState.Failed) {
        val reason = (state as UpdateUiState.Failed).reason
        AlertDialog(
            onDismissRequest = { manualRequested = false },
            title = { Text(tr("检查更新", "Check for updates")) },
            text = { Text(failureText(reason)) },
            confirmButton = { TextButton(onClick = { manualRequested = false }) { Text(tr("好", "OK")) } },
        )
    }
}

private fun openRelease(context: android.content.Context, url: String): Boolean = runCatching {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}.isSuccess

@Composable
private fun failureText(reason: UpdateOutcome.Reason): String = when (reason) {
    UpdateOutcome.Reason.PRIVATE_OR_NOT_FOUND ->
        tr("暂时无法获取更新信息（仓库可能尚未公开）。", "Update info isn't available yet (the repository may still be private).")
    UpdateOutcome.Reason.RATE_LIMITED ->
        tr("GitHub 暂时限制了检查频率，请稍后再试。", "GitHub is rate-limiting checks. Try again later.")
    UpdateOutcome.Reason.SERVER_ERROR ->
        tr("更新服务暂时不可用，请稍后再试。", "The update service is temporarily unavailable. Try again later.")
    UpdateOutcome.Reason.OFFLINE ->
        tr("网络不可用，请检查连接后重试。", "No network connection. Check your connection and try again.")
    UpdateOutcome.Reason.INVALID_RESPONSE ->
        tr("无法解析更新信息，请稍后再试。", "Couldn't read update info. Try again later.")
}

/** 启动时自动检查发现新版本的一次性提醒；关闭后同一 tag 不再自动弹出。 */
@Composable
fun UpdateReminderHost(viewModel: UpdateViewModel) {
    val reminder by viewModel.autoReminder.collectAsStateWithLifecycle()
    val release = reminder ?: return
    val context = LocalContext.current
    var openFailed by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = viewModel::dismissAutoReminder,
        title = { Text(tr("发现新版本", "Update available")) },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                Text(
                    release.version.display() + " · " + release.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (release.body.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(release.body, style = MaterialTheme.typography.bodySmall)
                }
                if (openFailed) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        tr("无法打开浏览器，请手动访问 GitHub Releases。", "Couldn't open a browser. Visit GitHub Releases manually."),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { openFailed = !openRelease(context, release.htmlUrl) }) {
                Text(tr("前往 GitHub", "View on GitHub"))
            }
        },
        dismissButton = {
            TextButton(onClick = viewModel::dismissAutoReminder) { Text(tr("稍后", "Later")) }
        },
    )
}
