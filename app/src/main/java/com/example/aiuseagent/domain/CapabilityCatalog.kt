package com.example.aiuseagent.domain

import com.example.aiuseagent.ui.i18n.tr

/**
 * 功能能力表。它是「哪些功能尚未实现」的**单一事实来源**，
 * 每个条目都必须能被代码或运行时状态证伪，禁止用装饰性图标暗示可用。
 */
object CapabilityCatalog {

    /** 实现范围；是否有凭据、授权或本次请求成功由运行时状态单独显示。 */
    fun planned(): List<FeatureEntry> = listOf(
        FeatureEntry(
            name = tr("模型接口（供应商 API）", "Model API (provider)"),
            state = FeatureState.IMPLEMENTED,
            detail = tr("聊天已接入 DeepSeek 与本地 Mock 的流式回复、停止、重试和消息状态保存。", "Chat is wired to DeepSeek and the local Mock for streaming replies, stop, retry, and message status persistence.") +
                tr("DeepSeek 需要用户在当前进程配置凭据并明确发送；保存配置和本地检查不联网，配置成功不代表请求成功。", "DeepSeek requires credentials in the current process and an explicit send; saving config and local checks make no network calls, and a saved config doesn't prove a request will succeed.") +
                tr("Mock 全程不联网，回复单独标记；其他协议适配器仍不在界面供应商选项中。", "Mock never uses the network and replies are marked; other protocol adapters still aren't offered in the provider list.") +
                tr("P2-B 的 HTTP/SSE 适配层继续使用唯一的 INTERNET 网络权限。", "The HTTP/SSE adapter layer still uses the single INTERNET permission."),
        ),
        FeatureEntry(
            name = tr("AgentLoop（多步自主执行）", "AgentLoop (multi-step autonomy)"),
            state = FeatureState.NOT_IMPLEMENTED,
            detail = tr("仅实现构建模式的有限工具轮次：DeepSeek 可请求 list_directory 和 read_text_file。", "Only limited tool rounds in Build mode: DeepSeek can request list_directory and read_text_file.") +
                tr("最多 3 轮模型请求、4 次工具调用；不支持任意工具选择、写文件或任意任务自主执行。", "Up to 3 model rounds and 4 tool calls; arbitrary tool choice, file writes, or autonomous execution aren't supported."),
        ),
        FeatureEntry(
            name = tr("无障碍自动操作", "Accessibility automation"),
            state = FeatureState.NOT_IMPLEMENTED,
            detail = tr("工程内没有 AccessibilityService 实现，也没有任何点击注入代码。", "The project has no AccessibilityService implementation and no click-injection code."),
        ),
        FeatureEntry(
            name = tr("文件读取 / 修改", "File read / modify"),
            state = FeatureState.IMPLEMENTED,
            detail = tr("支持默认工作区、自定义系统目录登记、每会话绑定，以及用户明确触发的 TXT/md 导入和导出。", "Supports the default workspace, registered custom folders, per-session binding, and TXT/md import/export only when you trigger it.") +
                tr("构建模式只能列举当前工作区目录和读取 UTF-8 TXT/md；单文件最多 8 KiB，工具结果总量最多 16 KiB。", "Build mode can list the current workspace and read UTF-8 TXT/md; max 8 KiB per file and 16 KiB of tool results in total.") +
                tr("读取结果仅发送给当前模型；模型不能修改或删除文件，不自动扫描目录。授权失效时需重新选择。", "Read results go only to the current model; the model can't modify or delete files and folders aren't scanned automatically. Reselect if permission expires."),
        ),
        FeatureEntry(
            name = tr("Python 任意代码执行", "Arbitrary Python execution"),
            state = FeatureState.NOT_IMPLEMENTED,
            detail = tr("目前仅支持固定问候与解释器诊断功能，不支持任意 Python 代码执行。", "Only a fixed greeting and interpreter diagnostics are supported; arbitrary Python isn't.") +
                tr("只调用工程内置 hello.py 的 greet() 与 interpreter_summary()；没有 eval / exec / 动态导入用户代码的入口。", "Only greet() and interpreter_summary() in the built-in hello.py are called; there is no eval/exec or dynamic import of user code."),
        ),
        FeatureEntry(
            name = tr("Skills 执行", "Skills execution"),
            state = FeatureState.NOT_IMPLEMENTED,
            detail = tr("Skills 页面是空状态占位，不加载、不执行任何外部脚本或技能包。", "The Skills page is an empty placeholder; it loads and runs no external scripts or skill packs."),
        ),
        FeatureEntry(
            name = tr("手机控制（拨号 / 短信 / 应用启动）", "Phone control (dial / SMS / launch apps)"),
            state = FeatureState.NOT_IMPLEMENTED,
            detail = tr("没有声明相关权限，也没有对应的 Intent 调用。", "No related permissions are declared and no such intents are called."),
        ),
    )
}
