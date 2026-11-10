package com.example.aiuseagent.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.example.aiuseagent.domain.FeatureEntry
import com.example.aiuseagent.domain.FeatureState
import com.example.aiuseagent.ui.i18n.tr

/**
 * 读取**真实**的 Android 授权与服务状态。
 * 任何读不到 / 没实现的状态都如实写出来，不用固定图标表示可用。
 */
class AndroidStatusInspector(private val context: Context) {

    fun permissionEntries(): List<FeatureEntry> = listOf(
        notificationPermission(),
        accessibilityService(),
        overlayPermission(),
        internetPermission(),
    )

    private fun notificationPermission(): FeatureEntry {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        return FeatureEntry(
            name = tr("通知权限（POST_NOTIFICATIONS）", "Notification permission (POST_NOTIFICATIONS)"),
            state = if (granted) FeatureState.IMPLEMENTED else FeatureState.BLOCKED_BY_PERMISSION,
            detail = if (granted) {
                tr("系统返回：已授予。本阶段没有任何发送通知的代码。", "System: granted. This stage has no code that posts notifications.")
            } else {
                tr("系统返回：未授予。本阶段不请求该权限（没有通知功能）。", "System: not granted. This stage doesn't request it (no notification feature).")
            },
        )
    }

    private fun accessibilityService(): FeatureEntry {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        )
        val mentioned = enabled?.contains(context.packageName) == true
        return FeatureEntry(
            name = tr("无障碍服务（自动操作）", "Accessibility service (automation)"),
            state = FeatureState.NOT_IMPLEMENTED,
            detail = if (mentioned) {
                tr("系统列表中出现了本应用包名，但工程内没有任何 AccessibilityService 实现，功能仍为未实现。", "The app appears in the system list, but the project has no AccessibilityService implementation, so the feature is still unimplemented.")
            } else {
                tr("未启用，且工程内不存在 AccessibilityService 实现。", "Not enabled, and no AccessibilityService implementation exists.")
            },
        )
    }

    private fun overlayPermission(): FeatureEntry {
        val canDraw = Settings.canDrawOverlays(context)
        return FeatureEntry(
            name = tr("悬浮窗（SYSTEM_ALERT_WINDOW）", "Overlay (SYSTEM_ALERT_WINDOW)"),
            state = FeatureState.NOT_IMPLEMENTED,
            detail = "canDrawOverlays = " + canDraw +
                tr("；工程未声明该权限，也没有悬浮窗实现。", "; the app doesn't declare this permission and has no overlay implementation."),
        )
    }

    private fun internetPermission(): FeatureEntry {
        val declared = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
                ?.contains(Manifest.permission.INTERNET) == true
        }.getOrDefault(false)
        return FeatureEntry(
            name = tr("网络权限（INTERNET）", "Network permission (INTERNET)"),
            state = if (declared) FeatureState.IMPLEMENTED else FeatureState.NOT_IMPLEMENTED,
            detail = if (declared) {
                tr("清单中已声明。已实现模型网络适配层，当前界面尚未接入模型调用。", "Declared in the manifest. The model network layer exists, but this UI doesn't call models yet.")
            } else {
                tr("清单中未声明，网络访问受限。已实现模型网络适配层，当前界面尚未接入模型调用。", "Not declared, so network access is restricted. The model network layer exists, but this UI doesn't call models yet.")
            },
        )
    }

    /** 应用版本：读 PackageManager，不写死常量。 */
    fun appVersion(): Pair<String, Long> {
        val info = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val name = info?.versionName ?: "unknown"
        // minSdk 33 ≥ API 28，longVersionCode 一定可用；不要写会把分支写死的 SDK_INT 判断。
        val code = info?.longVersionCode ?: 0L
        return name to code
    }

    /** 诊断页展示数据库文件的真实状态。 */
    fun databaseFileSummary(path: String): List<Pair<String, String>> {
        val file = java.io.File(path)
        val exists = file.exists()
        return listOf(
            tr("数据库文件", "Database file") to path,
            tr("文件存在", "File exists") to exists.toString(),
            tr("文件大小", "File size") to if (exists) (file.length().toString() + tr(" 字节", " bytes")) else tr("（尚未创建）", "(not created yet)"),
        )
    }

    fun deviceSummary(): List<Pair<String, String>> = listOf(
        tr("Android 版本", "Android version") to (Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")"),
        tr("设备型号", "Device model") to (Build.MANUFACTURER + " " + Build.MODEL),
        "ABI" to Build.SUPPORTED_ABIS.joinToString(", "),
    )
}
