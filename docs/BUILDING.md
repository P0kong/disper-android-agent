# 构建指南

## 环境要求

- JDK 21
- Android SDK：compileSdk 37（Android 13 及以上设备可运行，minSdk 33）
- Gradle：使用仓库自带 Wrapper，无需单独安装

设置环境变量（路径按本机实际安装位置调整）：

```bash
export JAVA_HOME="<path-to-jdk-21>"
export ANDROID_HOME="<path-to-android-sdk>"
```

Windows PowerShell：

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-21"
$env:ANDROID_HOME = "C:\path\to\android-sdk"
```

## 常用命令

```bash
# Debug APK
./gradlew :app:assembleDebug

# JVM 单元测试
./gradlew :app:testDebugUnitTest

# Lint
./gradlew :app:lintDebug

# Release（必须显式指定发布包名）
./gradlew :app:assembleRelease -PdisperApplicationId=com.example.aiuseagent.design
```

Windows 使用 `gradlew.bat` 替代 `./gradlew`。

## Python 与 Chaquopy

应用内置 Python 运行时用于固定的诊断脚本，构建配置见 `app/build.gradle.kts` 的 `chaquopy` 块：

- `defaultConfig.version = "3.13"`：应用侧 Python 主次版本为 3.13，实际运行时为 CPython 3.13.9。
- 构建机需要提供 **Python 3.13**（主次版本必须与配置一致），通过 Chaquopy 的 `buildPython` 指向该解释器；未显式配置时需确保构建使用的默认 `python` / `python3` 也是 3.13。
- 当前 `pip {}` 为空，没有第三方 pip 依赖；新增依赖必须在 `pip` 块中声明。
- Python 运行时只打包 `arm64-v8a` 与 `x86_64` 两种 ABI。

## Release 构建与签名

- Release 构建必须通过 `-PdisperApplicationId=` 指定包名，否则 Gradle 会拒绝执行。
- 仓库不包含签名密钥；默认输出未签名 APK。
- 发布者应在本地使用自己的长期密钥，先 `zipalign` 再 `apksigner sign`，随后用 `apksigner verify` 校验。
- 不要把密钥、口令或 `keystore.properties` 提交到仓库。

## 常见问题

**找不到 JDK / JAVA_HOME 未设置**

确认 `JAVA_HOME` 指向 JDK 21，并在同一终端会话中执行构建。

**SDK 路径错误**

设置 `ANDROID_HOME`（或 `local.properties` 中的 `sdk.dir`）；`local.properties` 属于本机配置，不应提交。

**Chaquopy / Python 相关失败**

项目仅打包 `arm64-v8a` 与 `x86_64` 两种 ABI；确认构建未修改 `ndk.abiFilters`。

**首次构建下载依赖失败**

检查网络与代理；依赖下载完成后可加 `--offline` 复用本地缓存。
