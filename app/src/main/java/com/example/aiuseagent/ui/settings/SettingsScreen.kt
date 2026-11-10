package com.example.aiuseagent.ui.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.aiuseagent.R
import com.example.aiuseagent.data.FolderStatus
import com.example.aiuseagent.data.WorkFolderState
import com.example.aiuseagent.ui.AppDestination
import com.example.aiuseagent.ui.components.*
import com.example.aiuseagent.ui.i18n.LanguagePreference
import com.example.aiuseagent.ui.i18n.tr
import com.example.aiuseagent.ui.theme.LocalDisper
import com.example.aiuseagent.ui.theme.Spacing
import com.example.aiuseagent.ui.theme.ThemePreference

@Composable
private fun SettingsBody(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        content = content,
    )
}

/** 分组卡片：浅色白底、深色炭黑面板，统一发丝线，无投影。 */
@Composable
private fun SettingsGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalDisper.current
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = palette.surface,
        border = BorderStroke(1.dp, palette.hairline),
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}

/** 设置行：左标题 + 说明，右侧箭头；组内用发丝线分隔。 */
@Composable
private fun SettingsRow(
    title: String,
    detail: String,
    onClick: () -> Unit,
    last: Boolean = false,
) {
    val palette = LocalDisper.current
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = palette.ink)
                if (detail.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(detail, style = MaterialTheme.typography.bodySmall, color = palette.inkSubtle)
                }
            }
            Spacer(Modifier.width(12.dp))
            Text("›", style = MaterialTheme.typography.titleLarge, color = palette.inkSubtle)
        }
        if (!last) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 18.dp),
                thickness = 1.dp,
                color = palette.hairline,
            )
        }
    }
}

/** 单条入口卡（保留给关于页等需要独立强调的场景）。 */
@Composable
private fun Entry(title: String, detail: String, onClick: () -> Unit) {
    val palette = LocalDisper.current
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = palette.surface,
        border = BorderStroke(1.dp, palette.hairline),
    ) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = palette.ink)
                Spacer(Modifier.height(2.dp))
                Text(detail, style = MaterialTheme.typography.bodySmall, color = palette.inkSubtle)
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = palette.inkSubtle)
        }
    }
}

@Composable
fun SettingsHome(onOpen: (AppDestination) -> Unit) = SettingsBody {
    SettingsGroup {
        SettingsRow(tr("模型配置", "Model configuration"), tr("API接口配置", "API configuration"), onClick = { onOpen(AppDestination.MODEL) })
        SettingsRow(tr("通用设置", "General settings"), tr("主题与启动时的对话行为", "Theme and chat behavior at startup"), onClick = { onOpen(AppDestination.GENERAL) })
        SettingsRow(tr("工具", "Tools"), tr("Skills、MCP、工作文件夹及权限与能力", "Skills, MCP, working folder, permissions and capabilities"), onClick = { onOpen(AppDestination.TOOLS) })
        SettingsRow(tr("关于", "About"), tr("应用信息与诊断", "App info and diagnostics"), onClick = { onOpen(AppDestination.ABOUT) }, last = true)
    }
}

@Composable
fun SettingsScreen(settingsViewModel: SettingsViewModel, modifier: Modifier = Modifier) {
    val palette = LocalDisper.current
    val form by settingsViewModel.providerForm.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState())
        .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Text(tr("当前供应商", "Current provider"), style = MaterialTheme.typography.labelMedium, color = palette.inkSubtle)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            listOf("DEEPSEEK" to "DeepSeek", "OPENAI_COMPATIBLE" to "OpenAI",
                "ANTHROPIC" to "Claude", "MOCK" to "Mock").forEach { (type, label) ->
                Surface(
                    onClick = { settingsViewModel.onProviderTypeChange(type) },
                    shape = CircleShape,
                    color = if (form.providerType.equals(type, ignoreCase = true)) palette.ink else palette.canvasAlt,
                    contentColor = if (form.providerType.equals(type, ignoreCase = true)) palette.canvas else palette.inkMuted,
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp), maxLines = 1)
                }
            }
        }
        form.appliedLabel?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = palette.accent)
        }
        Text(when {
            form.credentialUnreadable -> tr("已保存的 API Key 无法解密（设备密钥可能已失效），请重新输入；原密文不会被自动删除。", "The saved API Key can't be decrypted (the device key may have expired). Enter it again; the old ciphertext isn't deleted automatically.")
            form.credentialConfigured -> tr("API Key 已加密保存在本机；这不表示已连接成功。", "The API Key is stored encrypted on this device; this doesn't mean the connection succeeded.")
            else -> tr("尚未配置 API Key；Mock 不需要 API Key", "No API Key configured yet; Mock doesn't need an API Key")
        }, style = MaterialTheme.typography.bodySmall,
            color = if (form.credentialUnreadable) MaterialTheme.colorScheme.error else palette.inkMuted)
        if (form.credentialConfigured) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = palette.canvasAlt,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tr("已保存 · ", "Saved · ") + (form.credentialMask ?: "****"),
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.inkMuted,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = settingsViewModel::clearCredential,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                        Text(tr("清除", "Clear"), style = MaterialTheme.typography.labelMedium, color = palette.inkSubtle)
                    }
                }
            }
        }
        OutlinedTextField(value = form.baseUrl, onValueChange = settingsViewModel::onBaseUrlChange,
            label = { Text("Base URL") }, placeholder = { Text("https://api.deepseek.com") }, singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().semantics {contentDescription=tr("连接地址", "Endpoint")})
        OutlinedTextField(value = form.apiKey, onValueChange = settingsViewModel::onApiKeyChange,
            label = { Text(tr("API Key（保存后加密）", "API Key (encrypted on save)")) }, singleLine = true,
            placeholder = { Text(if (form.credentialConfigured) tr("输入新 Key 以替换", "Enter a new Key to replace") else tr("输入 API Key", "Enter API Key")) },
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().semantics {contentDescription=tr("模型凭据输入", "Model credentials input")})
        OutlinedTextField(value = form.model, onValueChange = settingsViewModel::onModelChange,
            label = { Text(tr("模型名称", "Model name")) }, singleLine = true, shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth().semantics {contentDescription=tr("模型名称输入", "Model name input")})
        if(form.providerType=="ANTHROPIC") OutlinedTextField(value=form.apiVersion,onValueChange=settingsViewModel::onApiVersionChange,
            label={Text(tr("API 版本", "API version"))},singleLine=true,shape = MaterialTheme.shapes.medium,modifier=Modifier.fillMaxWidth())
        Text(form.imageCapabilityLabel,style=MaterialTheme.typography.bodySmall, color = palette.inkSubtle)
        Button(onClick = settingsViewModel::saveProviderConfig, shape = CircleShape,
            modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(tr("保存并应用配置", "Save and apply"))
        }
        OutlinedButton(onClick = settingsViewModel::checkProviderFormLocally, shape = CircleShape,
            modifier = Modifier.fillMaxWidth().height(48.dp)) {
            Text(tr("仅检查格式（不联网）", "Check format only (offline)"))
        }
        Row(Modifier.fillMaxWidth()) {
            TextButton(onClick = settingsViewModel::clearCredential, modifier = Modifier.weight(1f)) {
                Text(tr("清除本机凭据", "Clear local credentials"), color = palette.inkMuted)
            }
            TextButton(onClick = settingsViewModel::clearProviderForm, modifier = Modifier.weight(1f)) {
                Text(tr("清空表单", "Clear form"), color = palette.inkMuted)
            }
        }
        form.notice?.let { NoticeBanner(it, NoticeTone.INFO) }
    }
}

@Composable
fun GeneralSettingsScreen(settings: SettingsViewModel) {
    val palette = LocalDisper.current
    val theme by settings.theme.collectAsStateWithLifecycle()
    val language by settings.language.collectAsStateWithLifecycle()
    val launch by settings.chatLaunch.collectAsStateWithLifecycle()
    SettingsBody {
        SectionCard(title = tr("界面语言 / Language", "Language / 界面语言")) {
            LanguagePreference.entries.forEach { preference ->
                Row(Modifier.fillMaxWidth().selectable(language == preference, role = Role.RadioButton,
                    onClick = { settings.setLanguage(preference) }).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = language == preference, onClick = null)
                    Spacer(Modifier.width(6.dp))
                    Text(tr(preference.labelZh, preference.labelEn), style = MaterialTheme.typography.bodyLarge, color = palette.ink)
                }
            }
        }
        SectionCard(title = tr("界面主题", "Theme")) {
            ThemePreference.entries.forEach { preference ->
                Row(Modifier.fillMaxWidth().selectable(theme == preference, role = Role.RadioButton,
                    onClick = { settings.setTheme(preference) }).padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = theme == preference, onClick = null)
                    Spacer(Modifier.width(6.dp))
                    Text(preference.label, style = MaterialTheme.typography.bodyLarge, color = palette.ink)
                }
            }
        }
        SectionCard(title = tr("对话", "Chat")) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("启动时开始新对话", "Start a new chat on launch"), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(checked = launch?.startNewChat ?: true, onCheckedChange = settings::setStartNewChat)
            }
        }
    }
}

fun folderStatusText(state: WorkFolderState): String = when (state.status) {
    FolderStatus.NONE -> tr("尚未选择目录", "No folder selected")
    FolderStatus.CHECKING -> tr("正在确认授权", "Confirming permission")
    FolderStatus.AUTHORIZED -> if (state.writable) tr("已保存读取与写入授权", "Read and write permission saved") else tr("已保存只读授权", "Read-only permission saved")
    FolderStatus.RESELECT -> tr("授权已失效或目录不可用，请重新选择", "Permission expired or folder unavailable. Select again.")
}

@Composable
fun WorkFolderSummary(state: WorkFolderState, onClick: () -> Unit) {
    Entry(tr("工作文件夹 · ", "Working folder · ") + (state.name ?: tr("未选择", "Not selected")), folderStatusText(state), onClick)
}

@Composable
fun ToolsScreen(folder: WorkFolderState, onOpen: (AppDestination) -> Unit) = SettingsBody {
    SettingsGroup {
        SettingsRow("Skills", tr("已有能力说明 · 尚未支持安装或执行", "Capability info only · Install and execution not supported yet"), onClick = { onOpen(AppDestination.SKILLS) })
        SettingsRow("MCP", tr("尚未接入", "Not integrated yet"), onClick = { onOpen(AppDestination.MCP) })
        SettingsRow(tr("工作文件夹 · ", "Working folder · ") + (folder.name ?: tr("未选择", "Not selected")), folderStatusText(folder), onClick = { onOpen(AppDestination.FOLDER) })
        SettingsRow(tr("权限与能力", "Permissions & capabilities"), tr("查看授权状态和当前功能范围", "View permission status and current feature scope"), onClick = { onOpen(AppDestination.CAPABILITIES) }, last = true)
    }
}

@Composable
fun ToolsScreen(workspaceName: String, onOpen: (AppDestination) -> Unit) = SettingsBody {
    SettingsGroup {
        SettingsRow("Skills", tr("已有能力说明 · 尚未支持安装或执行", "Capability info only · Install and execution not supported yet"), onClick = { onOpen(AppDestination.SKILLS) })
        SettingsRow("MCP", tr("尚未接入", "Not integrated yet"), onClick = { onOpen(AppDestination.MCP) })
        SettingsRow(tr("工作区 · ", "Workspace · ") + workspaceName, tr("当前会话绑定、自定义目录及 TXT/md 导入导出", "Current session binding, custom folders, and TXT/md import and export"), onClick = { onOpen(AppDestination.FOLDER) })
        SettingsRow(tr("权限与能力", "Permissions & capabilities"), tr("查看授权状态和当前功能范围", "View permission status and current feature scope"), onClick = { onOpen(AppDestination.CAPABILITIES) }, last = true)
    }
}

@Composable
fun WorkFolderScreen(state: WorkFolderState, onChoose: () -> Unit, onRemove: () -> Unit) = SettingsBody {
    SectionCard(title = state.name ?: tr("尚未选择工作文件夹", "No working folder selected yet")) {
        Text(folderStatusText(state))
        VerticalGap(Spacing.sm)
        Text(tr("当前只保存目录授权和名称，尚未启用自动读写与执行，不会扫描或读取目录内容。", "Only the folder permission and name are saved; automatic read/write and execution aren't enabled, and folder contents are never scanned or read."),
            style = MaterialTheme.typography.bodyMedium)
    }
    Button(onClick = onChoose, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(48.dp)) {
        Text(if (state.name == null) tr("选择工作文件夹", "Select working folder") else tr("重新选择工作文件夹", "Reselect working folder"))
    }
    if (state.name != null) OutlinedButton(onClick = onRemove, shape = CircleShape,
        modifier = Modifier.fillMaxWidth().height(48.dp)) { Text(tr("移除目录授权", "Remove folder permission")) }
    state.notice?.let { NoticeBanner(it, NoticeTone.WARN) }
}

@Composable
fun McpScreen() = SettingsBody {
    SectionCard(title = tr("MCP 尚未接入", "MCP not integrated yet")) {
        Text(tr("当前不能连接或执行 MCP 服务，无需填写服务地址或凭据。", "MCP servers can't be connected or run yet, so no server address or credentials are needed."))
    }
}

@Composable
fun AboutScreen(
    name: String,
    version: String,
    updateViewModel: com.example.aiuseagent.ui.update.UpdateViewModel,
    onOpen: (AppDestination) -> Unit,
) = SettingsBody {
    val palette = LocalDisper.current
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFF09101F),
                border = BorderStroke(1.dp, palette.hairline),
                modifier = Modifier.size(60.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Image(
                        painter = painterResource(R.mipmap.ic_launcher_foreground),
                        contentDescription = null,
                        modifier = Modifier.size(50.dp),
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(name, style = MaterialTheme.typography.titleLarge, color = palette.ink)
                Text(tr("版本 ", "Version ") + version, style = MaterialTheme.typography.bodySmall, color = palette.inkSubtle)
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            tr("通用 Android Agent", "A general-purpose Android agent"),
            style = MaterialTheme.typography.bodySmall,
            color = palette.inkSubtle,
        )
    }
    com.example.aiuseagent.ui.update.UpdateCheckButton(updateViewModel)
    Entry(tr("诊断", "Diagnostics"), tr("数据库状态、固定 Python 诊断与刷新", "Database status, fixed Python diagnostics, and refresh")) { onOpen(AppDestination.DIAGNOSTICS) }
}
