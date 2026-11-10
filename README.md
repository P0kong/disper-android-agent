# Disper — Android AI Agent

本仓库是 Disper（Android AI Agent）的公开源码快照，版本 `v0.1.0-alpha.1`（2026-11-10）。

## 当前版本（2026-11-10）

- 中文 / English 界面切换（设置 → 通用设置 → 界面语言），默认中文。
- 关于页提供唯一“检查更新”入口：启动时 24 小时低频检查，发现新版本时提醒一次；可“稍后”忽略该版本；Private 仓库阶段匿名查询返回不可用，仓库公开后生效。
- 更新检查只读取公开 GitHub Releases 元数据，不下载/安装 APK、不申请通知权限、不上传聊天/附件/配置，不使用嵌入 Token。
- 最终 APK `Disper-v0.1.0-alpha.1.apk`，SHA-256 `f7a84a02bc4f4c64c3710c9ba6c76f391d951cd04cd091a11b7893cdf85a7a57`；签名证书 SHA-256 `c871635037c501c71bfa8f63aee56cac92670e8ecca9aa29cb65e75626f60e39`。
- JVM 598/598、lint 0 error / 36 warning（更新模块完成时）；之后仅有界面文案精简，未改逻辑。
- 已知限制：真实模型服务端到端、公开仓库的更新成功路径、API 33 / arm64 设备未在本版验证。

## 历史实现与验证（2026-10-19）

已接入 DeepSeek 文本流式对话、本地 Mock、停止/重试与消息终态；Mock 回复明确标记。设置保留 Base URL、API Key、模型字段，显式保存和本地格式检查都不会发请求。只有非秘密白名单配置可持久化，Key 仅进程内存、应用后清空输入，进程结束后需重新填写；已配置不代表连接成功。

构建模式仅提供 `list_directory`、`read_text_file` 两个只读工具，最多 3 轮 / 4 次调用；只读当前会话绑定工作区的 UTF-8 TXT/md，单文件 8 KiB、工具结果总量 16 KiB。交流模式不提供文件工具。模型不能写入、覆盖、删除文件或执行代码。

工作区包含应用私有默认目录 `default`，以及用户通过系统选择器授予 SAF 权限并登记的自定义目录；每个会话独立绑定。用户可明确导入/导出 TXT/md；切换和授权检查不自动扫描。Room 已升至 v2，使用显式 1→2 迁移，保存工作区绑定、提供方和生成/工具状态。

最终 `validation-20261019T143252481Z-a468bb61`：36 类 / 358 项 JVM 测试，失败、错误、跳过均 0；lint 0 错误 / 10 警告（旧 6 + `ModifierParameter` 1 + `UseKtx` 3），62 项任务全部执行。最终 APK 67,127,658 B，SHA-256 `755B33D6B5B2F9B2DE71EBC0360E299CF4F69E80217199E384DF791FAB04E2D1`；CLI `emulator-20261019T143838696Z-aa411cfb` 复用设备，120 秒稳定检查、安装/启动冒烟通过，设备包哈希一致。

最终安装后的 5 项 Android 操作检查，以及 A01/A02/A03/A04/A05/A06/A07 真实 DeepSeek 设备验收均通过。本轮真实 HTTP 尝试 10 次 / 上限 12 次（含工具续轮），没有 probe、重试或额外停止尝试；5 次 `COMPLETED`、1 次用户 `CANCELLED`，4 次实际工具调用成功。Mock 两轮、冷开历史/SAF、实际导入导出与先前大字体消息阅读/滚动记录分别保留。

A04 默认工作区和 A05 自定义 SAF 工作区的同名文件具有不同 marker，提示词未提供答案，两条工具链均成功；A06 回复 `CANNOT_READ` 且没有工具调用。A03 生成中停止后保留部分正文，随后稳定无迟到。A05 真实回复和两个工具状态在 1.5 倍字体 + 构建模式 + 长工作区名 + 721 px 软键盘下完整可读，见 `real-tool-font15-keyboard.png`。

A07 结束带 Key 的进程后凭据消失、无自动 HTTP，16 个会话及 default/custom 绑定、停止部分保留；缺 Key 发送在落库/发网前拒绝。最终已显式清除凭据并结束应用进程，未通过 ADB 传 Key。应用数据库、偏好及默认工作区根对实际 Key 的内存精确匹配扫描均 0 命中；外部原计划值清理和最终材料扫描的结果见正式报告及收尾JSON，供应商撤销必须由用户完成，当前不宣称已撤销。完整证据与截图位置见 [DeepSeek 实测与工作区执行报告](docs/DeepSeek实测与工作区执行报告-20261019.md)。

任意 AgentLoop、模型写文件、任意 Python、无障碍/跨应用控制、Skills/MCP 执行与附件/拍照仍未实现。实现边界见 [架构说明](docs/ARCHITECTURE.md) 和 [Provider 当前范围](docs/PROVIDER-DESIGN.md)。

下列阶段记录保留原始历史；其中“P2-B 与 R1-A（当前）”“Provider 未接入 UI”“P2-C 尚未开始”等措辞按当时阶段理解，当前实现以本节为准。


- **阶段 0**：交付一个可真实构建出 Debug APK 的最小工程。
- **工程加固阶段**：`minSdk` 修正为 33（Android 13+）、补齐 Gradle Wrapper、固化构建基线、新增一键验证脚本、复核开源许可并建立 `NOTICE`。见 `docs/阶段1报告.md`。
- **原生应用骨架与 UI 阶段**：Compose + Material 3 的 5 个页面、ViewModel + StateFlow 状态管理、
  Room 会话/消息持久化、真实权限与能力状态页、诊断页，以及不再 NO-SOURCE 的 JVM 单元测试。
  见 `docs/ARCHITECTURE.md` 与 `docs/骨架与UI阶段报告.md`。
- **模型 Provider 与流式聊天 · 阶段 A（P2-A）**：Provider 契约层（供应商类型 / 协议 / 配置 / 流式事件 / 错误分类 / 凭据边界）、
  本地 Mock Provider、内存凭据存储与调度入口，加上 9 个 JVM 测试类（其中一个把「还没做的事」写成断言，称为边界守卫）。
  该阶段不联网、不接界面；后续 P2-B 已实现 HTTP/SSE 并声明 INTERNET。
  见 `docs/PROVIDER-DESIGN.md` 与 `docs/P2A报告.md`。

- **P2-B 与 R1-A（当前）**：HTTP/SSE、OpenAI-compatible（DeepSeek 复用）及 Anthropic 适配器已实现；
  R1-A 补强重定向、秘密出口、SSE 资源上限、协议终态/JSON、Retry-After、实际重试计数和阻塞取消。
  **Provider 仍未接入 UI，P2-C 尚未开始**。本轮证据与限制见 `docs/R1A报告.md`。

- **R1-B 基础缺陷修复（2026-10-17）**：仅处理会话绑定、配置重建页面恢复、状态栏可读性和事实文案；保留既有成果与冻结依赖。修复、验证及内存警告核查结果见 [独立执行报告](docs/R1B基础验收修复报告-20261017.md)，不代表 P2-C 或整体项目获批。

> JVM 测试和构建结果不能替代 Android 运行验收。R1-A 不安装 APK、不调用真实供应商；设备连接状态应在 R1-B 重新检测，
> 不能沿用历史空设备列表断言当前没有手机。设备验收清单见 `docs/MANUAL_TEST_PLAN.md`。

## 1. 环境要求（本机实测）

| 组件 | 版本 | 位置 / 说明 |
|---|---|---|
| JDK（构建基线） | Oracle JDK 21.0.3+7-LTS-152 | 已安装的 JDK 21（必须设置 `JAVA_HOME`） |
| JDK（可选试验） | 26 | **本机未安装**；阶段 1 已按指令记录为"未测"，详见 `docs/阶段1报告.md` |
| Android SDK | cmdline-tools 23.0.0 / platform-tools 37.0.1 / platforms;android-36 + android-37.0 / build-tools 36.0.0 | `D:\SDK`（`local.properties` 里写 **`sdk.dir=D\:/SDK`**，冒号必须转义；该文件不进版本库） |
| Gradle | **Wrapper 固定 9.4.1**（`gradle-wrapper.properties` 已写入官方 `distributionSha256Sum`） | `.\gradlew.bat`；本机也有免下载的发行包 `D:\AndroidToolchain\gradle\gradle-9.4.1` 作为备用 |
| 解释器（构建期） | Python 3.13.14（Windows Store CPython） | 由 Chaquopy 自动探测（`py -3.13`），不在工程里写死路径 |

### 1.1 本机（沙箱环境）必须设置的 `GRADLE_USER_HOME` 与 `ANDROID_USER_HOME`

本机的文件沙箱**不允许写** `%USERPROFILE%\.gradle`（实测报错：`对路径…的访问被拒绝`），
用默认的 Gradle 主目录会直接失败：

```
Could not initialize native services.
> Failed to load native library 'native-platform.dll' for Windows 11 amd64.
```

因此本机所有 Gradle 调用都**必须**把 `GRADLE_USER_HOME` 指到仓库内目录（已被 `.gitignore` 忽略）：

```powershell
$env:GRADLE_USER_HOME  = (Join-Path (Get-Location) '.gradle-user-home')
$env:ANDROID_USER_HOME = (Join-Path (Get-Location) '.android-user-home')
```

### 1.2 `local.properties` 里的 Windows 盘符必须转义

`.properties` 文件里冒号是键值分隔符，未转义的 `sdk.dir=D:/SDK` 会让 `:app:lintDebug` 以 **error** 失败：

```
local.properties:2: Error: Windows file separators (\) and drive letter separators (':')
must be escaped (\) in property files; use D\:/SDK [PropertyEscape]
```

正确写法（Gradle/AGP 读取时会还原成 `D:/SDK`）：

```properties
sdk.dir=D\:/SDK
```

### 1.3 第二个变量（`ANDROID_USER_HOME`）的原因相同

Android 构建工具要在 `%USERPROFILE%\.android` 下写 `debug.keystore.lock`，
沙箱同样拒绝（实测原文：`java.nio.file.AccessDeniedException: %USERPROFILE%\.android\debug.keystore.lock`），
表现为 `:app:validateSigningDebug` 失败。处理方式是把**已存在的** `debug.keystore`（2618 B）、`adbkey`、`adbkey.pub`
逐字节复制到仓库内 `.android-user-home`（`debug.keystore` 的 SHA-256 与原文件一致，**签名身份不变**），
再把 `ANDROID_USER_HOME` 指过去；**没有**修改签名配置、`applicationId` 或任何版本号。

> 这两个变量都是**本机沙箱限制**导致的用例，不是工程本身的要求。在普通开发机上不设置即可走默认的
> `%USERPROFILE%\.gradle` 与 `%USERPROFILE%\.android`。两个目录**都可以随时删除**
> （删除后首次构建需重新联网下载依赖；`.android-user-home` 被删后会重新生成一把**新的**调试密钥）。

## 2. 构建

**推荐：用仓库自带的 Wrapper（不再依赖本机 Gradle 安装）**

```powershell
$env:JAVA_HOME         = 'C:\path\to\jdk-21'
$env:ANDROID_HOME      = 'D:\SDK'
$env:GRADLE_USER_HOME  = (Join-Path (Get-Location) '.gradle-user-home')    # 仅本机沙箱需要
$env:ANDROID_USER_HOME = (Join-Path (Get-Location) '.android-user-home')   # 仅本机沙箱需要
Set-Location '<path-to-repo>'

.\gradlew.bat --version --console=plain
.\gradlew.bat :app:testDebugUnitTest --console=plain   # JVM 单元测试（不依赖设备）
.\gradlew.bat :app:lintDebug --console=plain           # Android Lint
.\gradlew.bat :app:assembleDebug --console=plain
```

**备用：直接用本机 Gradle 发行包**（wrapper 不可用时的退路）

```powershell
& 'D:\AndroidToolchain\gradle\gradle-9.4.1\bin\gradle.bat' :app:assembleDebug --console=plain
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。

### 2.1 一键验证脚本

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\verify-stage1.ps1
```

脚本会依次做：检查 JDK、Wrapper 文件与 wrapper JAR 的官方 SHA-256 → 设置进程内
`GRADLE_USER_HOME` / `ANDROID_USER_HOME` → `gradlew.bat --version` → `:app:testDebugUnitTest`
（并解析 `app/build/test-results` 下的 XML，打印真实用例数 / 失败数；0 个用例只给 WARN，**不算通过**）→
`:app:lintDebug` → `:app:assembleDebug` → 检查 APK 存在且大小 > 0 并打印 SHA-256 →
用 `aapt2` / `apkanalyzer` 打印包名 / minSdk / targetSdk / ABI 并断言 minSdk ≥ 33。
**任何一项失败都会返回非零退出码，不会吞错**；脚本只在本进程内设置环境变量，不装任何系统组件、不删任何用户目录。

> 若直接 `& .\scripts\verify-stage1.ps1` 报「在此系统上禁止运行脚本」，请先执行
> `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass -Force`（**只影响当前进程**，不改系统策略），
> 或改用上面那条 `powershell -ExecutionPolicy Bypass -File ...` 命令。

> **Windows PowerShell 5.1 编码要求（实测踩坑）**：`scripts/verify-stage1.ps1` 含中文，文件**必须**是
> **UTF-8 带 BOM**。若被另存为「UTF-8 无 BOM」，PowerShell 5.1 会按 GBK 解码并报出
> `表达式或语句中包含意外的标记“）”` 之类的语法错误（本机已实测）。
> `pwsh`（PowerShell 7）无此要求。本仓库当前以 Windows PowerShell 5.1（`5.1.26100`）验证通过。

### 2.2 静态核验（无需设备）

```powershell
& 'D:\SDK\build-tools\36.0.0\apksigner.bat' verify --verbose --print-certs app\build\outputs\apk\debug\app-debug.apk
& 'D:\SDK\build-tools\36.0.0\aapt2.exe' dump badging app\build\outputs\apk\debug\app-debug.apk
```

### 2.3 Provider 层测试（不需要设备，也不需要真实 API Key）

```powershell
# 契约层（P2-A）+ 真实适配层与边界守卫（P2-B）
.\gradlew.bat :app:testDebugUnitTest --console=plain --tests "com.example.aiuseagent.domain.provider.*" --tests "com.example.aiuseagent.data.provider.*" --tests "com.example.aiuseagent.P2BBoundaryGuardsTest"
```

这些测试全部在 JVM 上运行：不需要真实 Key、不需要设备。
P2-B 的适配层测试用 MockWebServer 起**本机 127.0.0.1 的假服务器**，凭据一律是虚构串 `test-key-not-secret`，
因此不会访问任何真实供应商，也不会产生费用。真实传输层的请求组装、分块读取、协程取消与 `Call.cancel()` 联动、
Retry-After 单位换算、错误正文上限，都在 `OkHttpTransportTest` 里用真实 socket 验证。

`P2BBoundaryGuardsTest` 是**边界守卫**：它断言「清单里只新增了 INTERNET 一个权限、网络调用只出现在
`data/provider` 适配层、适配层没有接进 AppContainer / 设置界面 / 聊天界面、MockWebServer 只出现在测试配置、
重试上限是结构性的、诊断类型无法承载 Authorization、凭据类没有持久化入口、能力表与设计文档不谎报」。
P2-C 接线时应当**故意修改**这些断言并在此处说明，而不是把测试删掉。
2026-10-19 已按授权更新原 11 项守卫，未删减测试数量：`AppContainer` 只允许一个统一仓库与生成协调器；UI 仍不得直接使用 HTTP/DAO；请求级重试上限只能收紧策略，且重试与退避各只有一个决策点。权限、日志、凭据不持久化及密钥扫描等覆盖保留。上段“适配层没有接进 AppContainer”的说明是原 P2-B 守卫历史；更新后的守卫随本轮 36 类 / 358 项全量 JVM 验证通过；A01–A07 真实设备验收也已通过，真实 HTTP 尝试计数为 10 / 12。

完整验收不带 `--tests` 过滤，并强制重跑测试、lint 与 APK 的任务图：

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --rerun-tasks --continue --console=plain
```

缓存完整时可加 `--offline`，报告须注明。`--rerun` 是单任务选项；`--rerun-tasks` 用于整张任务图。
`verify-stage1.ps1` 只作辅助，不能以脚本退出 0 或旧 XML 替代本轮实际测试证据。

## 3. 版本为什么这么钉（改之前先读）

- **AGP 9.2.1**：Chaquopy 17 官方只承诺支持到 AGP 9.2.x；且 9.2.1 修掉了 9.2.0 的
  `java.lang.ClassNotFoundException: Didn't find class "com.android.tools.r8.RecordTag"`。**不要**升到 9.3/9.4（Chaquopy 未测）。
- **Gradle 9.4.1 暂固定 + 提交 Wrapper**：AGP 9.2 要求 Gradle ≥ 9.4.1。阶段 1 已提交
  `gradlew / gradlew.bat / gradle/wrapper/*`，`gradle-wrapper.properties` 使用**官方发行地址 + 官方 SHA-256**（无镜像、无可疑脚本）。
  官方发行包 `gradle-9.4.1-bin.zip` 的 SHA-256 = `2ab2958f2a1e51120c326cad6f385153bb11ee93b3c216c5fccebfdfbb7ec6cb`。
- **AGP 9 自带 Kotlin（built-in Kotlin）**：实测 AGP 9.2.1 的构建类路径解析到 `org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.10`。
  因此 `app/build.gradle.kts` **刻意不应用** `org.jetbrains.kotlin.android`（AGP 9 下会冲突），
  只应用 `org.jetbrains.kotlin.plugin.compose`，**版本必须与 2.2.10 严格一致**。
- **compileSdk = 37**：Compose 1.12.x 的 AAR 元数据硬性要求 `compileSdk >= 37`；
  而 AGP 9.2 支持的最高 API level 恰好是 37.0（`platforms;android-37.0`）。二者刚好卡在一起。
- **minSdk = 33（阶段 1 从 24 修正）**：**产品需求是 Android 13+（API 33）**，这才是取值依据。
  Chaquopy 17 的官方下限是 **24**，只是"允许的最低值"，**不是**本工程应该取的 minSdk；
  直接用 24 会让应用被安装到 Android 7–12 设备上而产品并不支持这些系统。**不要**把 minSdk 调回 24。
- **targetSdk = 36**：与 compileSdk 解耦，先保持稳定行为，后续阶段再单独评估升级。
- **abiFilters = arm64-v8a, x86_64**：Chaquopy 要求显式声明 ABI；Python > 3.11 只有这两个 ABI 有运行时
  （arm64-v8a 真机、x86_64 模拟器）。**首版保持这两个 ABI，真机验收以 ARM64 为重点**。
- **JDK 21 为基线**：构建使用 Oracle JDK 21.0.3。Gradle 9.4.1 自身声明支持 Java 26
  （`gradlew --version` 横幅含 `- Java 26 support`），但**这不等于 AGP 9.2.1 / Chaquopy 17 已兼容 Java 26**。
  阶段 1 未在本机安装 JDK 26，JDK 26 试验按指令记录为"未执行"。

## 4. 目录结构

```
android-ai-use-agent/
├─ gradlew / gradlew.bat               Gradle Wrapper（阶段 1 新增）
├─ gradle/wrapper/                     wrapper JAR + properties（官方地址 + 官方 SHA-256）
├─ scripts/verify-stage1.ps1           一键验证脚本（阶段 1 新增）
├─ NOTICE                              第三方组件许可汇总（阶段 1 新增）
├─ settings.gradle.kts                 仓库源（google / mavenCentral / chaquo.com/maven）
├─ build.gradle.kts
├─ gradle.properties
├─ gradle/libs.versions.toml           所有版本的唯一事实来源
├─ local.properties                    sdk.dir（不进版本库）
├─ app/
│  ├─ build.gradle.kts                 android{} / chaquopy{} / ksp{} / dependencies{}（minSdk = 33）
│  ├─ schemas/                         Room 导出的 schema JSON（版本 1，已入库）
│  └─ src/
│     ├─ main/
│     │  ├─ AndroidManifest.xml        application android:name=".AgentApplication"
│     │  ├─ java/com/example/aiuseagent/
│     │  │  ├─ AgentApplication.kt     : PyApplication()（Chaquopy 要求）+ AppContainer
│     │  │  ├─ AppContainer.kt         手工依赖容器（无 DI 框架）
│     │  │  ├─ MainActivity.kt         单 Activity 入口 + 阶段 0 的 Python 冒烟日志
│     │  │  ├─ data/                   SessionRepository / SettingsRepository / local(Room)
│     │  │  │                          provider/(InMemoryCredentialStore, ProviderRepository, HttpProviders)
│     │  │  │                          provider/http/(HttpTransport, OkHttpTransport, HttpStreamingProvider,
│     │  │  │                                          RetryPolicy, SseStreamDecoder, ErrorBodyProbe, ProviderDiagnostics)
│     │  │  │                          provider/sse/(SseParser)  provider/mock/(MockProvider)
│     │  │  │                          provider/openai/(OpenAiCompatibleProvider)
│     │  │  │                          provider/anthropic/(AnthropicMessagesProvider)
│     │  │  ├─ domain/                 FeatureState / CapabilityCatalog
│     │  │  │                          provider/(ProviderType, ProviderConfig, ProviderEvent, ProviderError, ModelProvider …)
│     │  │  ├─ platform/               AndroidStatusInspector（只读真实系统状态）
│     │  │  └─ ui/                     AppRoot / theme / components / chat / settings /
│     │  │                             capabilities / skills / diagnostics
│     │  ├─ python/hello.py            greet(name) / interpreter_info() / interpreter_summary()
│     │  └─ res/                       strings / themes / 图标
│     └─ test/java/com/example/aiuseagent/
│        ├─ P2BBoundaryGuardsTest.kt   边界守卫（只新增 INTERNET / 网络代码只在适配层 / 不接 UI / 无真实密钥）
│        ├─ data/                      SessionRepositoryTest / RoomSchemaContractTest / FakeDaoStore
│        │                             provider/(MockProviderTest, InMemoryCredentialStoreTest, ProviderRepositoryTest,
│        │                                      HttpProvidersTest, sse/SseParserTest, http/RetryPolicyTest,
│        │                                      http/OkHttpTransportTest, http/HttpStreamingProviderTest,
│        │                                      openai/OpenAiCompatibleProviderTest,
│        │                                      anthropic/AnthropicMessagesProviderTest)
│        ├─ domain/provider/           ProviderConfigTest / ChatRequestTest / ProviderCredentialTest /
│        │                             ProviderErrorTest / ProviderEventContractTest
│        └─ ui/chat, ui/theme          ChatViewModelTest / ThemePreferenceTest
└─ docs/                               ARCHITECTURE / MANUAL_TEST_PLAN / CHANGELOG / PROVIDER-DESIGN /
                                       技术栈 / 开源审查 / 依赖许可表 / 阶段 0、1、骨架与 UI、P2-A 报告
```

## 5. 再次构建时的注意事项

- **首次构建需要联网**：从官方地址下载 Gradle 9.4.1 发行包（约 138 MB，仅在 `.gradle-user-home` 缺失时），
  以及从 Google Maven / Maven Central / chaquo.com/maven 拉取依赖。
- **本机实测：Wrapper 首次下载发行包会失败，需要预置**。`services.gradle.org` 会 307 跳转到
  `downloads.gradle.org`，而 wrapper 内部的 `HttpURLConnection` 连不上跳转目标，报错原文：
  `Exception in thread “main” java.net.ConnectException: Connection timed out: getsockopt`
  （`gradle-wrapper.properties` 的 `networkTimeout` 已从 10000 上调到 120000，仍然失败；
  同时刻用 HTTP/2 客户端访问同一 URL 返回 200，说明是本机网络路径问题，不是地址写错）。
  绕开方式**不修改** `distributionUrl`（仍是官方地址 + 官方 SHA-256），只把**已核对过官方 SHA-256** 的
  `gradle-9.4.1-bin.zip` 预置进 wrapper 的解包目录后再跑 `gradlew.bat`：

```powershell
$dir = Join-Path $env:GRADLE_USER_HOME 'wrapper\dists\gradle-9.4.1-bin\arn2x92ynaizyzdaamcbpbhtj'
New-Item -ItemType Directory -Force -Path $dir | Out-Null
Copy-Item 'D:\AndroidToolchain\dl\gradle-9.4.1-bin.zip' (Join-Path $dir 'gradle-9.4.1-bin.zip')
Expand-Archive -LiteralPath (Join-Path $dir 'gradle-9.4.1-bin.zip') -DestinationPath $dir -Force
New-Item -ItemType File -Force -Path (Join-Path $dir 'gradle-9.4.1-bin.zip.ok') | Out-Null
```

  该目录名（`arn2x92ynaizyzdaamcbpbhtj`）是 Gradle 依据 `distributionUrl` 计算的哈希，换地址即变；
  `.ok` 标记文件表示「已解包完成」，缺了它 wrapper 会重新下载。
  **在能正常访问 `downloads.gradle.org` 的机器上无需以上步骤**，`gradlew.bat` 会自动下载并按
  `distributionSha256Sum` 校验。
- **构建期的 Python 解释器**：Chaquopy 需要本机有与 `python.defaultVersion` 主次版本一致的 Python（本机为 3.13），
  且与打包进 APK 的 Python 版本一致。这不是"应用运行时依赖"，只影响构建。
- **不要**把 `local.properties`、`.gradle-user-home/`、`.android-user-home/`、含个人路径的日志提交进仓库（均已在 `.gitignore` 中）。
- **调试签名**：`.android-user-home/debug.keystore` 是**复制**自用户目录的现有调试密钥（SHA-256 与原件一致），
  只用于让沙箱内的构建能产出 APK；它**不是**发布密钥，也未提交进版本库。

## 6. 已知限制与历史环境记录

- **阶段 1 的模拟器检查**：`emulator -accel-check` 返回
  `Android Emulator hypervisor driver is not installed on this machine`，
  且 `(Get-CimInstance Win32_ComputerSystem).HypervisorPresent = False`。
- **阶段 1 未连接真机**：当时 `adb devices -l` 输出为空列表（`List of devices attached` 之后无设备）。
  → 因此阶段 1 **没有**做"装到设备上跑起来"的运行时冒烟，**构建成功不等于真机运行成功**。
  手工测试步骤见 `docs/阶段1报告.md` 的"设备运行时测试"一节。
- **界面与 Room 的设备行为仍未验收**：`:app:testDebugUnitTest` 现在有真实用例
  （会话 CRUD、重命名校验、删除确认、状态恢复、Room schema 契约），但它们跑在 JVM 上，
  用的是**实现同一 DAO 接口的内存替身**，不是真实 SQLite；Compose 界面也没有仪器测试。
  → 所以「测试通过」只代表**数据层逻辑与 ViewModel 状态转换**通过，**不代表**应用在设备上跑通。
- **真实 HTTP 层存在但没有接入界面**：`domain/provider` 与 `data/provider` 已有契约、Mock、HTTP/SSE 和两类协议适配器，
  R1-A 对它们补强；`AppContainer` 和聊天界面依然不调度 Provider。真实供应商端到端未验证。
- **API Key 只在内存**：`InMemoryCredentialStore` 不接受 Context、也没有任何序列化方法，进程结束即丢失；
  P2-C 界面必须原样显示「仅保存在内存：应用进程结束后需要重新输入」，**不得**做成看起来已永久保存。
  当前占位表单只存内存；R1-A 仅隐藏其 `toString()` 中的自由文本，没有实现配置/Key 接线。
- **仍未做**：AgentLoop、无障碍自动操作、文件修改、Python 任意代码执行、Skills 执行。
  界面上这些能力一律显示为「未连接 / 尚未实现 / 未验证」，不使用固定绿勾。

## 7. Windows 命令行调试（R1-CLI）

环境检查、完整构建验证和指定 AVD 安装启动分别由 `scripts/check-env.ps1`、
`scripts/verify-stage1.ps1 -FullValidation`、`scripts/run-emulator.ps1` 提供。
使用方法、参数优先级、唯一结果记录与故障处理见 [ANDROID_CLI_WORKFLOW.md](docs/ANDROID_CLI_WORKFLOW.md)。
本轮真实证据和未完成项见 [R1CLI报告.md](docs/R1CLI报告.md)。
工具脚本不改变应用功能；构建成功、模拟器启动冒烟和功能验收分别记录。

- **UI粗版与功能迁移（2026-10-18）**：顶部会话记录/交流与构建/设置，底部消息输入；设置收纳模型配置、通用、工具、关于。新对话首次发送才建库，默认进程冷启动为空白，可关闭并恢复历史选择；工作文件夹仅选择和持久授权，不读取内容。当前仍未接入真实模型或自动执行。实际验证和截图见 `docs/UI粗版布局与功能迁移报告-20261018.md`。
