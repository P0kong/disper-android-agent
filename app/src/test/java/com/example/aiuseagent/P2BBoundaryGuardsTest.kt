package com.example.aiuseagent

import com.example.aiuseagent.domain.CapabilityCatalog
import com.example.aiuseagent.domain.FeatureState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2-B 的边界守卫。
 *
 * P2-A 的守卫把「还没有网络能力」写成了断言；P2-B 真的要发请求了，所以那批断言被**故意**改写（不是删掉）：
 * 守的边界从「不许有网络」变成「网络只能以受控方式存在」。现在守的是：
 * 1. 权限只新增了 INTERNET 一个，其余敏感权限仍然禁止；
 * 2. 网络调用只允许出现在 data/provider 适配层，UI / ViewModel / AppContainer / 凭据类里一个都不许有；
 * 3. 授权接线只允许 AppContainer 唯一装配仓库和进程协调器，UI 不持有 HTTP 或 DAO；
 * 4. 网络库只允许 okhttp（运行期），MockWebServer 只能出现在测试配置里，retrofit / ktor / volley 依然禁止；
 * 5. 重试上限是结构性的：客户端自动重试关闭、次数由 RetryPolicy 的唯一判定点决定；
 * 6. 诊断类型在**类型上**就无法承载 Authorization / x-api-key / 完整正文；
 * 7. 凭据类仍然没有持久化入口，仓库里仍然扫不到真实密钥；
 * 8. 能力表与设计文档准确区分已接线范围、运行时条件和未实现的自动化能力。
 */
class P2BBoundaryGuardsTest {

    private fun findUpwards(relative: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val candidate = File(dir, relative)
            if (candidate.exists()) return candidate
            val nested = File(dir, "android-ai-use-agent/" + relative)
            if (nested.exists()) return nested
            dir = dir.parentFile
        }
        throw AssertionError("找不到 " + relative + "，当前工作目录 " + File(".").absolutePath)
    }

    private fun mainDir(): File = findUpwards("app/src/main")

    /** 仓库根目录。注意 findUpwards 返回的是命中的那个路径本身（此处是文件），所以要取父目录。 */
    private fun repoRoot(): File = findUpwards("settings.gradle.kts").parentFile

    /** 只看代码行：注释里出现的词不算证据。 */
    private fun codeOnly(text: String): String = text.lines()
        .filterNot { line ->
            val trimmed = line.trim()
            trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
        }
        .joinToString(separator = "\n")

    private fun kotlinSources(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** 相对主源码根目录的路径，统一用 / 分隔，便于判断「在不在适配层里」。 */
    private fun relativeSourcePath(file: File): String =
        file.relativeTo(File(mainDir(), "java")).path.replace(File.separatorChar, '/')

    private fun isProviderLayer(relativePath: String): Boolean =
        relativePath.startsWith("com/example/aiuseagent/data/provider/")

    @Test
    fun manifestDeclaresOnlyInternetPermission() {
        val manifest = File(mainDir(), "AndroidManifest.xml")
        assertTrue("找不到 AndroidManifest.xml", manifest.isFile)
        val text = manifest.readText()
        assertTrue(
            "P2-B 的适配层要发真实请求，必须声明 INTERNET 权限",
            text.contains("android:name=\"android.permission.INTERNET\""),
        )

        val declared = Regex("android:name=\"(android\\.permission\\.[A-Z_]+)\"")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()
        assertEquals("P2-B 只允许新增 INTERNET 一个权限，实际：" + declared, setOf("android.permission.INTERNET"), declared)

        val forbidden = listOf(
            "ACCESSIBILITY_SERVICE",
            "SYSTEM_ALERT_WINDOW",
            "MANAGE_EXTERNAL_STORAGE",
            "READ_EXTERNAL_STORAGE",
            "WRITE_EXTERNAL_STORAGE",
            "POST_NOTIFICATIONS",
            "READ_CONTACTS",
            "READ_SMS",
            "SEND_SMS",
            "RECORD_AUDIO",
            "CAMERA",
            "ACCESS_FINE_LOCATION",
            "QUERY_ALL_PACKAGES",
            "REQUEST_INSTALL_PACKAGES",
            "PACKAGE_USAGE_STATS",
            "FOREGROUND_SERVICE",
            "BIND_DEVICE_ADMIN",
            "SCHEDULE_EXACT_ALARM",
        )
        for (token in forbidden) {
            assertFalse("P2-B 不得声明 " + token, text.contains(token))
        }
    }

    @Test
    fun networkingCodeLivesOnlyInProviderAdapters() {
        val patterns = listOf("HttpURLConnection", "okhttp3", "java.net.http", "java.net.URL", "Socket(")
        val sources = kotlinSources(File(mainDir(), "java"))
        assertTrue("主源码扫描数量异常（" + sources.size + "），路径判断可能失效", sources.size >= 10)

        val offenders = mutableListOf<String>()
        var hitsInAdapterLayer = 0
        for (file in sources) {
            val relative = relativeSourcePath(file)
            val code = codeOnly(file.readText())
            for (pattern in patterns) {
                if (!code.contains(pattern)) continue
                if (isProviderLayer(relative)) {
                    hitsInAdapterLayer++
                } else {
                    offenders += relative + " 命中 " + pattern
                }
            }
        }
        assertTrue("网络调用只允许出现在 data/provider 适配层，实际：" + offenders, offenders.isEmpty())
        assertTrue("适配层里必须真的有网络调用代码，否则这条断言在空转", hitsInAdapterLayer > 0)
    }

    @Test
    fun adaptersAreWiredOnlyByTheSingleAppContainerAssembly() {
        val wiringTokens = listOf(
            "HttpProviders",
            "okHttpTransport",
            "OkHttpTransport",
            "HttpTransport",
            "HttpStreamingProvider",
            "ProviderHttpRequest",
            "OpenAiCompatibleProvider",
            "AnthropicMessagesProvider",
        )
        val sources = kotlinSources(File(mainDir(), "java"))
        val offenders = mutableListOf<String>()
        for (file in sources) {
            val relative = relativeSourcePath(file)
            if (isProviderLayer(relative)) continue
            val code = codeOnly(file.readText())
            for (token in wiringTokens) {
                // AppContainer 是唯一装配点：允许统一入口，也允许为设备测试注入的 transport 默认值。
                // 除这两类 token 外，适配器实现与流式 Provider 仍不得出现在 AppContainer。
                if (relative == "com/example/aiuseagent/AppContainer.kt" &&
                    token in setOf("HttpProviders", "HttpTransport", "OkHttpTransport")
                ) continue
                if (code.contains(token)) offenders += relative + " 命中 " + token
            }
            if (relative.startsWith("com/example/aiuseagent/ui/")) {
                for (token in listOf("AppDatabase", "SessionDao", "MessageDao", "GenerationDao", "WorkspaceDao", "androidx.room")) {
                    if (code.contains(token)) offenders += relative + " 直接持有 DAO/数据库 " + token
                }
                if (Regex("\\bGenerationCoordinator\\s*\\(").containsMatchIn(code))
                    offenders += relative + " 重复构造 GenerationCoordinator"
            }
        }
        assertTrue(
            "接线只允许 AppContainer 使用 HttpProviders 统一入口；UI 不得构造网络协调器或持有 DAO，实际：" + offenders,
            offenders.isEmpty(),
        )

        val container = File(mainDir(), "java/com/example/aiuseagent/AppContainer.kt")
        assertTrue("找不到 AppContainer.kt", container.isFile)
        val containerCode = codeOnly(container.readText())
        assertEquals("AppContainer 必须只装配一个已注册的 ProviderRepository", 1,
            Regex("HttpProviders\\.repository\\s*\\(").findAll(containerCode).count())
        assertEquals("AppContainer 必须只装配一个进程生成协调器", 1,
            Regex("\\bGenerationCoordinator\\s*\\(").findAll(containerCode).count())
        assertFalse("不得额外裸构造 ProviderRepository 绕过统一注册入口",
            Regex("\\bProviderRepository\\s*\\(").containsMatchIn(containerCode))
        assertFalse("不得绕过 repository 额外创建 HTTP 注册表",
            Regex("HttpProviders\\.(registry|registryWithOkHttp)\\s*\\(").containsMatchIn(containerCode))
    }

    @Test
    fun uiAndCredentialStoresStayOffTheNetwork() {
        val networkTokens = listOf(
            "okhttp3",
            "HttpURLConnection",
            "java.net.http",
            "java.net.URL",
            "Socket(",
            "openConnection",
        )
        val targets = mutableListOf<File>()
        val uiDir = File(mainDir(), "java/com/example/aiuseagent/ui")
        assertTrue("找不到 ui 源码目录，路径判断可能失效", uiDir.isDirectory)
        targets += kotlinSources(uiDir)
        targets += listOf(
            File(mainDir(), "java/com/example/aiuseagent/data/provider/InMemoryCredentialStore.kt"),
            File(mainDir(), "java/com/example/aiuseagent/domain/provider/ProviderCredential.kt"),
        )
        assertTrue("待扫描文件过少（" + targets.size + "），路径判断可能失效", targets.size >= 8)

        val offenders = mutableListOf<String>()
        for (file in targets) {
            val code = codeOnly(file.readText())
            for (token in networkTokens) {
                if (code.contains(token)) offenders += file.name + " 命中 " + token
            }
        }
        assertTrue("UI 与凭据类不得自己发 HTTP，必须经由 ModelProvider，实际：" + offenders, offenders.isEmpty())
    }

    @Test
    fun buildFilesDeclareOnlyApprovedNetworkingLibraries() {
        val candidates = listOf("app/build.gradle.kts", "build.gradle.kts", "gradle/libs.versions.toml")
            .map { File(repoRoot(), it) }
            .filter { it.isFile }
        assertTrue("没有找到任何 Gradle 构建文件，路径判断可能失效", candidates.isNotEmpty())

        for (file in candidates) {
            val text = codeOnly(file.readText()).lowercase()
            for (token in listOf("retrofit", "ktor", "volley")) {
                assertFalse(file.name + " 不得引入 " + token, text.contains(token))
            }
        }

        val appLines = File(repoRoot(), "app/build.gradle.kts").readText().lines()
        val mockWebServerLines = appLines.filter { it.contains("mockwebserver") }
        assertTrue("JVM 测试需要 MockWebServer（mockwebserver3）", mockWebServerLines.isNotEmpty())
        for (line in mockWebServerLines) {
            assertTrue("MockWebServer 只能出现在测试配置里，实际：" + line.trim(), line.trim().startsWith("testImplementation"))
        }
        val okhttpLines = appLines.filter { it.contains("libs.okhttp") }
        assertTrue("运行期需要一个 HTTP 客户端（okhttp）", okhttpLines.isNotEmpty())
        for (line in okhttpLines) {
            assertTrue("okhttp 只能作为实现依赖引入，实际：" + line.trim(), line.trim().startsWith("implementation"))
        }
    }

    @Test
    fun retryIsBoundedByASingleDecisionPoint() {
        val providerRoot = File(mainDir(), "java/com/example/aiuseagent/data/provider")
        val sources = kotlinSources(providerRoot)
        assertTrue("适配层源码数量异常（" + sources.size + "），路径判断可能失效", sources.size >= 8)

        val retry = File(providerRoot, "http/RetryPolicy.kt")
        assertTrue("缺少 RetryPolicy.kt", retry.isFile)
        val retryCode = codeOnly(retry.readText())
        assertTrue("重试次数必须有结构性上限常量", retryCode.contains("MAX_ALLOWED_ATTEMPTS"))
        assertTrue(
            "上限必须被 require 强制住，不能只靠默认值",
            retryCode.contains("require(maxAttempts in 1..MAX_ALLOWED_ATTEMPTS)"),
        )

        val streaming = codeOnly(File(providerRoot, "http/HttpStreamingProvider.kt").readText())
        val effectivePolicy = Regex(
            "val\\s+(\\w+)\\s*=\\s*request\\.maxAttempts\\?\\.let\\s*\\{\\s*" +
                "retryPolicy\\.copy\\(maxAttempts\\s*=\\s*minOf\\(it,\\s*retryPolicy\\.maxAttempts\\)\\)\\s*" +
                "}\\s*\\?:\\s*retryPolicy",
        ).find(streaming)
        assertTrue("请求级上限只能缩小 RetryPolicy 上限；未提供时必须沿用原策略", effectivePolicy != null)
        val effectivePolicyName = effectivePolicy!!.groupValues[1]
        val retryDecisions = Regex("\\b(\\w+)\\.shouldRetry\\s*\\(").findAll(streaming).toList()
        assertEquals("是否重试必须只有一个 RetryPolicy.shouldRetry 判定点", 1, retryDecisions.size)
        assertEquals("单一判定点必须使用已受请求级上限约束的同一策略", effectivePolicyName,
            retryDecisions.single().groupValues[1])
        val backoffDecisions = Regex("\\b(\\w+)\\.delayMillisFor\\s*\\(").findAll(streaming).toList()
        assertEquals("退避时长必须只有一个 RetryPolicy.delayMillisFor 来源", 1, backoffDecisions.size)
        assertEquals("退避必须使用与重试判定相同的受限策略", effectivePolicyName,
            backoffDecisions.single().groupValues[1])

        val offenders = mutableListOf<String>()
        for (file in sources) {
            if (codeOnly(file.readText()).contains("retryOnConnectionFailure(true)")) offenders += file.name
        }
        assertTrue("不得开启 OkHttp 连接层自动重试（否则重试次数无法计数），实际：" + offenders, offenders.isEmpty())
    }

    @Test
    fun diagnosticsCannotCarrySecrets() {
        val diagnostics = File(mainDir(), "java/com/example/aiuseagent/data/provider/http/ProviderDiagnostics.kt")
        assertTrue("缺少 ProviderDiagnostics.kt", diagnostics.isFile)
        val code = codeOnly(diagnostics.readText())
        for (token in listOf("Authorization", "x-api-key", "headers", "RequestBody", "ResponseBody")) {
            assertFalse("诊断记录不得承载 " + token + "（日志白名单字段之外的都不许进）", code.contains(token))
        }
        assertTrue("写进诊断的文本必须先经过脱敏", code.contains("SecretRedaction.scrub"))
    }

    @Test
    fun credentialClassesHaveNoPersistenceSurface() {
        val files = listOf(
            File(mainDir(), "java/com/example/aiuseagent/data/provider/InMemoryCredentialStore.kt"),
            File(mainDir(), "java/com/example/aiuseagent/domain/provider/ProviderCredential.kt"),
        )
        val forbidden = listOf(
            "android.content.Context",
            "SharedPreferences",
            "DataStore",
            "androidx.room",
            "Serializable",
            "Parcelable",
            "FileOutputStream",
            "writeText",
            "writeBytes",
            "Intent",
            "Bundle",
        )
        for (file in files) {
            assertTrue("缺少文件 " + file.path, file.isFile)
            val code = codeOnly(file.readText())
            for (token in forbidden) {
                assertFalse(file.name + " 的代码里不得出现 " + token, code.contains(token))
            }
        }
    }

    @Test
    fun capabilityCatalogDescribesOnlyTheAuthorizedImplementedScope() {
        val entries = CapabilityCatalog.planned()
        assertTrue("能力表条目过少，可能被误删", entries.size >= 7)

        val modelEntry = entries.first { it.name.startsWith("模型接口") }
        assertEquals(FeatureState.IMPLEMENTED, modelEntry.state)
        for (token in listOf("DeepSeek", "Mock", "不联网", "停止", "重试", "配置成功不代表请求成功")) {
            assertTrue("模型能力必须说明 " + token, modelEntry.detail.contains(token))
        }
        assertTrue("能力表必须说明 INTERNET 权限的现状", modelEntry.detail.contains("INTERNET"))
        val files = entries.first { it.name.startsWith("文件读取") }
        assertEquals(FeatureState.IMPLEMENTED, files.state)
        for (token in listOf("工作区", "每会话", "导入", "导出", "8 KiB", "16 KiB", "不能修改或删除", "不自动扫描")) {
            assertTrue("文件能力必须明确限定 " + token, files.detail.contains(token))
        }
        val loop = entries.first { it.name.startsWith("AgentLoop") }
        assertEquals(FeatureState.NOT_IMPLEMENTED, loop.state)
        for (token in listOf("list_directory", "read_text_file", "3 轮", "4 次", "不支持")) {
            assertTrue("有限工具轮次不得冒充通用 AgentLoop，缺少 " + token, loop.detail.contains(token))
        }
        val implemented = entries.filter { it.state == FeatureState.IMPLEMENTED }.map { it.name }.toSet()
        assertEquals("只能标记本轮授权的模型和受限文件能力", setOf(modelEntry.name, files.name), implemented)
        val others = entries.filterNot { it == modelEntry || it == files }
        val wrong = others.filter { it.state != FeatureState.NOT_IMPLEMENTED }.map { it.name }
        assertTrue(
            "通用 AgentLoop / 无障碍 / 任意 Python / Skills / 手机控制仍未实现，实际：" + wrong,
            wrong.isEmpty(),
        )
        assertTrue(entries.all { it.name.isNotBlank() && it.detail.isNotBlank() })
    }

    @Test
    fun repositoryContainsNoRealLookingApiKeys() {
        val root = repoRoot()
        val patterns = listOf(
            Regex("sk-[A-Za-z0-9]{20,}"),
            Regex("sk-ant-[A-Za-z0-9_-]{20,}"),
            Regex("AIza[0-9A-Za-z_-]{30,}"),
            Regex("ghp_[A-Za-z0-9]{30,}"),
            Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"),
        )
        val extensions = setOf("kt", "kts", "md", "xml", "properties", "toml", "json", "ps1", "txt", "py")

        val roots = listOf("app/src", "docs", "scripts")
            .map { File(root, it) }
            .filter { it.exists() } +
            listOf(
                "README.md",
                "NOTICE",
                "settings.gradle.kts",
                "build.gradle.kts",
                "gradle.properties",
                "app/build.gradle.kts",
            ).map { File(root, it) }.filter { it.isFile }

        val inspected = mutableListOf<File>()
        for (target in roots) {
            val files = if (target.isDirectory) {
                target.walkTopDown()
                    .filter { it.isFile && it.extension in extensions && !it.path.contains(File.separator + "build" + File.separator) }
                    .toList()
            } else {
                listOf(target)
            }
            for (file in files) {
                inspected += file
                val text = file.readText()
                for (pattern in patterns) {
                    val match = pattern.find(text)
                    assertFalse(
                        "疑似真实密钥出现在 " + file.path + "（匹配内容已隐藏）",
                        match != null,
                    )
                }
            }
        }
        assertTrue("扫描到的文件过少（" + inspected.size + "），路径判断可能失效", inspected.size >= 20)
    }

    @Test
    fun providerDesignDocSeparatesCurrentAuthorizedWiringFromHistoricalPlans() {
        val doc = File(repoRoot(), "docs/PROVIDER-DESIGN.md")
        assertTrue("缺少 docs/PROVIDER-DESIGN.md", doc.isFile)
        val text = doc.readText()
        for (token in listOf("Mock", "P2-B", "P2-C", "SSE", "INTERNET")) {
            assertTrue("设计文档必须提到 " + token, text.contains(token))
        }
        assertFalse(
            "P2-B 已实现真实 HTTP/SSE 适配层，文档不得再宣称不发任何真实网络请求",
            text.contains("本阶段不发起任何真实网络请求"),
        )
        val current = text.substringAfter("## 0. 当前批次：2026-10-19", missingDelimiterValue = "")
            .substringBefore("## 历史设计正文（保留）")
        assertTrue("必须以当前批次覆盖历史 P2-C 待接线口径", current.isNotBlank())
        for (token in listOf("HttpProviders.repository", "GenerationCoordinator", "DEEPSEEK", "MOCK",
            "不发请求", "凭据仅进程内存", "list_directory", "read_text_file", "最终构建", "仍待本轮正式报告")) {
            assertTrue("当前接线文档缺少明确边界 " + token, current.contains(token))
        }
        val p2c = File(repoRoot(), "docs/P2C接线设计.md")
        assertTrue("必须保留 P2-C 历史方案并更新已批准范围", p2c.isFile)
        val p2cCurrent = p2c.readText().substringAfter("## 0. 当前批次：2026-10-19", missingDelimiterValue = "")
            .substringBefore("## 历史方案正文（保留）")
        for (token in listOf("用户已批准", "viewModelScope", "不再适用", "WHP", "历史", "待最终报告")) {
            assertTrue("P2-C 当前前提缺少 " + token, p2cCurrent.contains(token))
        }
    }
}
