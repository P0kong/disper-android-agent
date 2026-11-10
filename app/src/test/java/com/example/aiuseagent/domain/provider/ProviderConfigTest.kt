package com.example.aiuseagent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ProviderConfig 的语义：Base URL 是**完整前缀**，只拼接一次，且默认拒绝明文 HTTP。 */
class ProviderConfigTest {

    private fun openAiConfig(baseUrl: String, model: String = "some-model"): ProviderConfig =
        ProviderConfig(type = ProviderType.OPENAI_COMPATIBLE, baseUrl = baseUrl, model = model)

    @Test
    fun resolvePath_appendsExactlyOneSlash() {
        val config = openAiConfig("https://api.example.com/v1")
        assertEquals("https://api.example.com/v1/chat/completions", config.resolvePath("chat/completions"))
        assertEquals("https://api.example.com/v1/chat/completions", config.resolvePath("/chat/completions"))
    }

    @Test
    fun resolvePath_neverDuplicatesPrefixOrSlash() {
        val resolved = openAiConfig("https://api.example.com/v1/").resolvePath("chat/completions")
        assertEquals("https://api.example.com/v1/chat/completions", resolved)
        assertFalse("不能自动补第二段 /v1", resolved.removePrefix("https://").contains("/v1/v1"))
        assertFalse("拼接后不能出现双斜杠", resolved.removePrefix("https://").contains("//"))
    }

    @Test
    fun resolvePath_returnsBaseForBlankRelativePath() {
        assertEquals("https://api.example.com/v1", openAiConfig("https://api.example.com/v1/").resolvePath(""))
    }

    @Test
    fun resolvePath_prefixesSlashWhenBaseUrlEmpty() {
        val config = ProviderConfig(type = ProviderType.OPENAI_COMPATIBLE, baseUrl = "", model = "m")
        assertEquals("/chat/completions", config.resolvePath("chat/completions"))
    }

    @Test
    fun validation_acceptsValidHttpsConfig() {
        assertTrue(openAiConfig("https://api.example.com/v1").isReady())
    }

    @Test
    fun validation_rejectsBlankUrl() {
        val issues = openAiConfig("").validationIssues()
        assertTrue("空 Base URL 必须被拒绝", issues.contains(ConfigIssue.BLANK_BASE_URL))
    }

    @Test
    fun validation_rejectsUrlWithoutScheme() {
        val issues = openAiConfig("api.example.com/v1").validationIssues()
        assertTrue("缺少 scheme 的地址必须被拒绝，实际：" + issues, issues.contains(ConfigIssue.MALFORMED_URL))
    }

    @Test
    fun validation_rejectsPlainHttpByDefaultAndAllowsItOnlyWhenOptedIn() {
        val strict = openAiConfig("http://10.0.2.2:8080/v1")
        assertTrue(strict.validationIssues().contains(ConfigIssue.INSECURE_HTTP_NOT_ALLOWED))
        assertFalse("默认配置不得就绪", strict.isReady())

        val optedIn = strict.copy(allowInsecureHttp = true)
        assertTrue("显式打开开关后应放行", optedIn.isReady())
    }

    @Test
    fun validation_rejectsUnsupportedScheme() {
        val issues = openAiConfig("ftp://api.example.com/v1").validationIssues()
        assertTrue("非 http(s) 协议必须被拒绝", issues.contains(ConfigIssue.UNSUPPORTED_SCHEME))
    }

    @Test
    fun validation_rejectsEndpointPastedIntoBaseUrl() {
        val openAi = openAiConfig("https://api.example.com/v1/chat/completions").validationIssues()
        assertTrue("把 chat/completions 粘进 Base URL 必须被拒绝", openAi.contains(ConfigIssue.ENDPOINT_IN_BASE_URL))

        val anthropic = ProviderConfig(
            type = ProviderType.ANTHROPIC,
            baseUrl = "https://api.anthropic.com/v1/messages",
            model = "m",
        ).validationIssues()
        assertTrue("把 /messages 粘进 Base URL 必须被拒绝", anthropic.contains(ConfigIssue.ENDPOINT_IN_BASE_URL))
    }

    @Test
    fun validation_requiresModel() {
        val issues = openAiConfig("https://api.example.com/v1", model = "   ").validationIssues()
        assertTrue("空模型名必须被拒绝", issues.contains(ConfigIssue.BLANK_MODEL))
    }

    @Test
    fun validation_rejectsTimeoutsOutOfRange() {
        val tooShort = openAiConfig("https://api.example.com/v1").copy(connectTimeoutMillis = 500)
        assertTrue(tooShort.validationIssues().contains(ConfigIssue.TIMEOUT_OUT_OF_RANGE))

        val tooLong = openAiConfig("https://api.example.com/v1").copy(readTimeoutMillis = 999_999)
        assertTrue(tooLong.validationIssues().contains(ConfigIssue.TIMEOUT_OUT_OF_RANGE))
    }

    @Test
    fun validation_invalidResultCarriesMessages() {
        val validation = openAiConfig("").validation()
        assertFalse(validation.isValid)
        assertTrue(validation is ConfigValidation.Invalid)
        assertTrue(
            "校验失败必须给出可直接展示的文案",
            (validation as ConfigValidation.Invalid).messages().all { it.isNotBlank() },
        )
    }

    @Test
    fun mockConfig_isReadyWithoutKeyAndUsesMockScheme() {
        val config = ProviderConfig.forType(ProviderType.MOCK)
        assertEquals("mock://local", config.baseUrl)
        assertEquals("mock-echo", config.model)
        assertTrue("Mock 配置必须自带可用默认值，实际：" + config.validationIssues(), config.isReady())
        assertFalse("Mock 不得被当作真实网络供应商", ProviderType.MOCK.isRealNetworkProvider)
    }

    @Test
    fun forType_leavesRealProviderModelBlank() {
        val anthropic = ProviderConfig.forType(ProviderType.ANTHROPIC)
        assertEquals("https://api.anthropic.com/v1", anthropic.baseUrl)
        assertTrue("真实供应商不得预填模型名", anthropic.model.isBlank())
        assertTrue("模型名为空时必须报 BLANK_MODEL", anthropic.validationIssues().contains(ConfigIssue.BLANK_MODEL))
    }

    @Test
    fun forType_deepSeekPresetIsHttpsOpenAiCompatible() {
        val deepSeek = ProviderConfig.forType(ProviderType.DEEPSEEK)
        assertEquals(ProviderProtocol.OPENAI_COMPATIBLE, ProviderType.DEEPSEEK.protocol)
        assertTrue(deepSeek.baseUrl.startsWith("https://"))
        assertFalse("预填地址不得包含具体消息路径", deepSeek.validationIssues().contains(ConfigIssue.ENDPOINT_IN_BASE_URL))
    }
}
