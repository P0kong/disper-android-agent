package com.example.aiuseagent

import com.chaquo.python.android.PyApplication

/**
 * 应用级 Application：必须继承 PyApplication（或自行在其 onCreate 中调用 Python.start）。
 * Chaquopy 依赖这个入口把 Python 运行时初始化到应用的私有目录。
 *
 * 阶段 1 起这里同时作为依赖容器（AppContainer）的唯一持有者，
 * 由 MainActivity 取出后交给 Compose 界面。刻意不引入 DI 框架。
 */
class AgentApplication : PyApplication() {

    val container: AppContainer get() = ApplicationContainerFactory.get(this)
}
