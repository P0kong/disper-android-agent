package com.example.aiuseagent.domain

import com.example.aiuseagent.ui.i18n.tr

/**
 * 功能可用性的**唯一**表达方式。任何页面都不得用固定图标暗示某个功能可用。
 */
enum class FeatureState {
    /** 本阶段真的实现了，并且当前可以工作。 */
    IMPLEMENTED,

    /** 需求存在但本阶段**尚未实现**。 */
    NOT_IMPLEMENTED,

    /** 已实现，但缺少运行时条件（权限 / 服务 / 外部系统）。 */
    BLOCKED_BY_PERMISSION,

    /** 界面已就绪，但还没有接入真实后端（例如模型供应商）。 */
    NOT_CONNECTED,

    /** 当前无法确定（例如没有安全的检测手段）。 */
    UNKNOWN,
}

data class FeatureEntry(
    val name: String,
    val state: FeatureState,
    val detail: String,
)

/** 供状态徽标使用的短标签。 */
fun FeatureState.label(): String = when (this) {
    FeatureState.IMPLEMENTED -> tr("已实现", "Implemented")
    FeatureState.NOT_IMPLEMENTED -> tr("尚未实现", "Not implemented")
    FeatureState.BLOCKED_BY_PERMISSION -> tr("缺权限", "Missing permission")
    FeatureState.NOT_CONNECTED -> tr("未连接", "Not connected")
    FeatureState.UNKNOWN -> tr("未验证", "Unverified")
}
