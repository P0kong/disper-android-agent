# 架构说明

本文只描述当前源码（`v0.1.0-alpha.1`）能够支持的事实。

## 技术栈

- Kotlin 2.2.10，Jetpack Compose（Material 3）
- AGP 9.2.1 / Gradle 9.4.1，compileSdk 37，minSdk 33，targetSdk 36
- Room 6（本地数据库，含显式迁移）
- Chaquopy 17（Python 3.13.9，仅固定诊断脚本）
- OkHttp 5.5（模型 HTTP/SSE 与更新检查）
- kotlinx.serialization（JSON）

## 应用结构

单一 `app` 模块，按职责分包：

```text
com.example.aiuseagent
├── ui/            Compose 界面：聊天、设置、主题、i18n、更新
├── data/          Room、Provider、附件、工作区、生成协调、更新仓库
├── domain/        领域模型、版本比较、能力目录
├── platform/      Android 适配：SAF、附件选择、状态检查
└── AppContainer   依赖装配与应用生命周期
```

`AppContainer` 统一创建数据库、Provider 仓库、生成协调器、工作区服务、设置仓库与更新仓库；UI 通过 ViewModel 使用这些依赖。

## Provider 与模型连接

- `ProviderType`：`DEEPSEEK`、`OPENAI_COMPATIBLE`、`ANTHROPIC`、`MOCK`。
- 真实网络请求经 `HttpProviders` → `OkHttpTransport`，仅在用户点击发送时发起。
- `ProviderRepository` 负责配置、连接身份与已准备（prepared）Provider；生成协调器只通过它取用 Provider。
- API Key 由 Android Keystore 加密后保存在应用私有存储；界面只显示掩码。
- `MOCK` 不联网，回复单独标记。

## 聊天、会话与本地存储

- 会话与消息保存在 Room；附件元数据与内容保存在应用私有目录。
- 支持交流 / 构建两种模式；草稿按会话隔离。
- 生成支持流式回复、停止、重试与终态保存。

## 工具调用与执行边界

构建模式提供两个只读工具：

- `list_directory`
- `read_text_file`

限制：最多 3 轮模型请求、4 次工具调用；单文件最多 8 KiB，工具结果总量最多 16 KiB；只读取当前会话绑定工作区的 UTF-8 TXT/md。

模型不能写入、覆盖、删除文件，也不能执行任意代码。

## 文件与工作区

- 默认工作区为应用私有目录；自定义目录通过系统 SAF 选择器授权并登记。
- 每个会话独立绑定工作区；支持用户明确触发的 TXT/md 导入与导出。
- 工具调用遵循工作区边界与授权状态，授权失效时需重新选择。

## 附件

- 支持文件、照片、拍照；发送前预览与确认。
- 历史附件可本地查看，不额外请求模型。
- 附件处理失败会显示状态，可移除后重选。

## 更新检查

- 仅调用 GitHub Releases 列表接口，读取公开元数据。
- 使用 SemVer 比较（alpha < beta < rc < stable），跳过 draft、无效 tag 与非本仓库 URL。
- 启动时最多每 24 小时自动检查一次；关于页提供手动检查；进程内单飞。
- 不下载或安装 APK，不申请通知权限，不使用 Token。

## 尚未实现

任意 AgentLoop、模型写文件、任意 Python、无障碍 / 跨应用控制、Skills / MCP 执行、自动下载或安装更新、Root 能力。
