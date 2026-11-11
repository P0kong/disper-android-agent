**English** | [简体中文](#chinese)

<a id="english"></a>
# Disper

**A lightweight, easy-to-use, extensible open-source Android AI agent.**

Disper aims to make AI agents more than chat tools: a mobile assistant that understands requests, works with files, calls tools, and helps complete real tasks.

We want people new to AI agents to get started without friction, while giving developers room to explore and extend.

> **Current version: v0.1.0-alpha.1 (Alpha Preview)**
>
> The project is still in early development. Some features are not available yet, and existing ones may be unstable or incomplete.

## ✨ Highlights

**Simple to use**

Designed around clear, minimal interactions so new users can start without unnecessary setup.

**English and Simplified Chinese**

The interface supports English and Simplified Chinese, switchable in Settings. Internationalization will continue to improve.

**Local-first and privacy-conscious**

Chat history, attachments, and app configuration are stored locally by default. Disper does not rely on a self-hosted cloud account or chat sync service.

API keys are protected with Android Keystore-backed encryption. When you use an online model, the content required for your request is sent to the model provider you configured.

**From chat to agent**

Beyond conversation, Disper is building the core agent capabilities: task execution, tool calls, workspace management, and explicit operation approval — so AI can move from answering questions to helping complete tasks.

**Practical file handling**

Select and use files in conversations, with a more coherent experience around reading, attachment management, and workspace operations. Supported formats and actions depend on the current release.

**Built to extend**

A modular approach leaves room for more tools, execution environments, and multi-agent collaboration.

## 🚀 Roadmap

Disper plans to explore the following directions:

| Area | Plan |
|---|---|
| 🧠 Global memory | Manage and use long-term memory across sessions so the agent better understands ongoing work |
| 🔓 Root-based extended permissions | Explore advanced device operations within explicit user authorization and controlled boundaries |
| 🐍 Constrained Python runtime | Provide a sandboxed Python environment for data processing and automation |
| 🤝 Multi-agent workspaces | Let multiple AI agents collaborate on complex tasks |
| ⏰ Scheduled tasks | Support predefined tasks and scheduled triggers to reduce repetitive work |
| 🌐 Remote connections | Explore secure connections to other devices or services to extend the agent's reach |
| 💬 Proactive clarification | Let the agent ask, confirm, or remind at the right moment instead of always waiting for instructions |

These are **planned directions only**. They are not implemented, and they are not release commitments. Designs and priorities will change as development progresses. See [docs/ROADMAP.md](docs/ROADMAP.md).

## 📱 Current release

**v0.1.0-alpha.1**

The project has an Android native foundation with AI chat, model configuration, basic tool calls, file and workspace management, session history, and English/Chinese UI.

Agent capabilities remain intentionally limited. Disper is not a fully autonomous, system-level phone automation agent. Some features are still in development or testing.

Requires Android 13 (API 33) or later. Real-device compatibility is still being validated.

## 🔐 Privacy and security

Disper follows local-first storage, explicit authorization, and visible capability boundaries.

- Sessions, attachments, and related app data are stored locally.
- Disper does not provide a self-hosted chat cloud sync service.
- Sensitive credentials are protected with Android Keystore-backed mechanisms.
- File access and tool operations follow the relevant permissions and workspace boundaries.
- Online model providers receive the data required to fulfill your request; their privacy policies govern how they handle it.

See [docs/SECURITY.md](docs/SECURITY.md) for details.

## 📦 Download and usage

Test APKs will be published on [GitHub Releases](https://github.com/P0kong/disper-android-agent/releases) after the repository becomes public. **No Release has been published yet**, so this Private repository does not currently offer a download link.

For a first run, start with basic chat and the local Mock provider. Connecting to an online model requires configuring that provider yourself.

**Note:** Alpha software can have defects. Back up important files. Do not use this release for highly reliable or unattended sensitive operations.

## 🛠️ Open source and contributing

Disper is licensed under the **Apache License 2.0**. See [LICENSE](LICENSE) and [NOTICE](NOTICE). Source code is licensed under Apache-2.0; the original Disper app icon artwork is separately reserved. Personal, non-commercial local builds are allowed; public distribution requires separate permission — see [Artwork & Icon Licensing](ASSETS-LICENSE.md).

Issues with questions, feature suggestions, and improvement ideas are welcome, as are code contributions.

The project will continue improving the usability, privacy, and extensibility of the Android agent.

## 📚 Developer documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Building](docs/BUILDING.md)
- [Security](docs/SECURITY.md)
- [Testing](docs/TESTING.md)
- [Roadmap](docs/ROADMAP.md)

---

**Disper — from conversation to action.**

<a id="chinese"></a>
# Disper

**轻量、易用、可扩展的开源 Android AI Agent。**

[English](#english)

Disper 致力于让 AI Agent 不再只是聊天工具，而是能够理解需求、处理文件、调用工具并协助完成任务的移动端智能助手。

我们希望让初次接触 AI Agent 的用户也能轻松上手，同时为开发者保留自由探索和扩展的空间。

> **当前版本：v0.1.0-alpha.1（测试版）**
>
> 项目仍处于早期开发阶段，部分功能尚未上线，现有功能也可能存在不稳定或不完善的情况。

## ✨ 项目特色

**简单易用**

以直观、简洁的交互为设计目标，减少不必要的配置与操作，让新用户也能快速开始使用。

**中英双语**

目前支持简体中文和 English 界面，可在设置中切换。未来将逐步完善国际化支持。

**本地优先，注重隐私**

聊天记录、附件和应用配置以本地存储为主，不依赖 Disper 自建云端账户或聊天同步服务。

API Key 使用 Android Keystore 相关机制进行加密保护。使用在线模型时，请求所需内容会发送至用户配置的模型服务商。

**从聊天到 Agent**

不仅提供 AI 对话，还在逐步构建任务执行、工具调用、工作区管理及操作授权等 Agent 核心能力，让 AI 从回答问题走向协助完成任务。

**便捷的文件处理**

支持在对话中选择和使用文件，并围绕文件阅读、附件管理和工作区操作建立更连贯的使用体验。具体格式和可执行操作以当前版本支持情况为准。

**面向未来的扩展能力**

采用模块化思路持续开发，为更多工具、执行环境和多智能体协作提供扩展空间。

## 🚀 后续开发方向

Disper 计划逐步探索以下能力：

| 方向 | 规划内容 |
|---|---|
| 🧠 全局记忆 | 跨会话管理与利用长期记忆，让 AI 更好地理解持续性任务 |
| 🔓 Root 扩展权限 | 在用户明确授权和可控边界内，探索更高级的设备操作能力 |
| 🐍 有限 Python 执行环境 | 提供受约束的 Python 代码运行能力，辅助数据处理和任务自动化 |
| 🤝 多 Bot 协作空间 | 让多个 AI Agent 分工协作，共同处理复杂任务 |
| ⏰ 定时任务 | 支持预设任务与定时触发，减少重复操作 |
| 🌐 远程连接 | 探索与其他设备或服务建立安全连接，扩展 Agent 的工作范围 |
| 💬 主动问询 | 在适当场景下由 Agent 主动询问、确认或提醒，而非始终等待指令 |

以上均属于**规划方向，不代表已经实现，也不构成发布时间承诺**。具体设计与优先级会根据开发进展调整。详见 [docs/ROADMAP.md](docs/ROADMAP.md)。

## 📱 当前版本

**v0.1.0-alpha.1**

现阶段已建立 Android 原生应用基础，包含 AI 对话、模型配置、基础工具调用、文件与工作区管理、会话历史和中英文切换等功能。

当前 Agent 的执行能力仍受到明确限制，并非完整的系统级手机自动操作智能体。部分功能仍处于开发或测试阶段。

支持 Android 13（API 33）及以上版本。实际设备兼容性仍在持续验证中。

## 🔐 隐私与安全

Disper 坚持本地优先、明确授权和能力边界可见的设计原则。

- 本地保存会话、附件和相关应用数据。
- 不提供 Disper 自建聊天云同步服务。
- 敏感凭据通过 Android Keystore 相关机制保护。
- 文件访问与工具操作遵循对应的权限和工作区约束。
- 在线模型服务会接收完成用户请求所必需的数据，其隐私政策由相应服务商决定。

更多说明见 [docs/SECURITY.md](docs/SECURITY.md)。

## 📦 下载与使用

仓库公开后，测试版 APK 将发布在 [GitHub Releases](https://github.com/P0kong/disper-android-agent/releases)。**当前尚未创建任何 Release**，因此这个 Private 仓库暂不提供下载链接。

首次使用建议从基础对话和本地 Mock 模式开始体验。连接在线模型需要自行配置相应服务商的连接信息。

**注意：** 测试版可能存在缺陷，重要文件应自行备份。请勿将当前版本用于要求高度可靠性或无人监督的敏感操作。

## 🛠️ 开源与贡献

Disper 以 **Apache License 2.0** 开源，见 [LICENSE](LICENSE) 与 [NOTICE](NOTICE)。项目源码采用 Apache-2.0；Disper 原创应用图标素材保留独立权利，允许个人非商业本地编译，公开分发需另行授权，详见[图标与素材许可](ASSETS-LICENSE.md)。

欢迎通过 Issues 提出问题、功能建议及改进意见，也欢迎开发者参与代码贡献。

项目将持续完善 Android Agent 的可用性、隐私保护和扩展能力。

## 📚 开发文档

- [架构说明](docs/ARCHITECTURE.md)
- [构建指南](docs/BUILDING.md)
- [安全设计](docs/SECURITY.md)
- [测试说明](docs/TESTING.md)
- [路线图](docs/ROADMAP.md)

---

**Disper — 让 AI 从对话走向行动。**
