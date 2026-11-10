# 测试说明

## 测试分层

- **JVM 单元测试**：运行在开发机 JVM，不依赖真实 Android Keystore、SAF 或设备。
- **Android instrumentation 测试**：运行在设备/模拟器，覆盖 Keystore、Room 迁移、附件、历史记录与 UI 流程。
- **设备冒烟**：对签名 Release 包做安装、启动与关键页面检查。

## JVM 单元测试

```bash
./gradlew :app:testDebugUnitTest
```

2026-11-10 在公开快照中执行（`--rerun-tasks`）：

- 78 个测试类
- 598 个用例
- 失败 0，错误 0

覆盖范围：

- 版本解析与 Release 筛选、更新节流与提醒状态
- Provider 配置、连接身份与能力解析
- 设置持久化（以测试替身模拟存储，不使用真实 Keystore）
- 附件状态机、历史快照、工作区边界与会话生成
- 安全边界守卫（权限、依赖、敏感模式扫描）

**不覆盖**：真实 Android Keystore 加解密、真实 SAF 授权、真实数据库迁移与设备 UI。

## Android 设备 / 模拟器测试

以下测试属于 instrumentation（`app/src/androidTest`），需要设备或模拟器：

- `KeystoreCredentialStoreTest`：真实 Android Keystore 的加密保存、读取、掩码与清除。
- `Migration1To2Test` 等 Room 迁移测试：真实 SQLite 上的表结构与数据迁移。
- 附件与历史：`AttachmentCatalogDeviceTest`、`AttachmentPipelineDeviceTest`、`HistoryAttachmentDeviceTest` 等。
- 工作区与 SAF：`SafImportContractTest`、`WorkspaceServiceRuntimeTest`、`DefaultWorkspaceBoundaryTest` 等。
- UI 流程：`MainActivityAttachmentFlowTest`、`MainActivityRestartFlowTest`、`PermissionModeUiTest` 等。

这些用例的通过记录来自更早的设备会话；**本轮文档修改未重新执行 instrumentation 测试**，不能把历史结果当作本次重跑结果。

## 设备冒烟（签名 Release）

已验证环境：API 37 / x86_64 模拟器。

- 签名 Release 包：覆盖安装、启动、关于页版本与“检查更新”按钮。
- 同源码 debug 预览包：中文 / English 切换、语言偏好持久化、Private 仓库更新检查错误提示。
- 历史证据（同一源码 debug 构建）：附件 6/6、历史记录 5/5。

## Lint

```bash
./gradlew :app:lintDebug
```

更新模块完成时的记录为 0 error / 36 warning；之后仅有文案精简，未改逻辑。

## 已知未验证项

- API 33 真机与 arm64 设备。
- 当前版本真实在线模型服务的端到端。
- 公开仓库（非 Private）下的真实更新成功路径与“前往 GitHub”跳转。
- 相机确认 / 取消与连续点击压力的完整回归。

## 说明

- 不同测试层次的结果来自不同时间点，本文档不表示本次文档提交重新执行了全部设备测试。
- 未在本文档中编造或用历史数据替代当前版本结果。
