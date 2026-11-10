package com.example.aiuseagent.data.provider

import com.example.aiuseagent.data.provider.anthropic.AnthropicMessagesProvider
import com.example.aiuseagent.data.provider.http.OkHttpTransport
import com.example.aiuseagent.data.provider.http.RetryPolicy
import com.example.aiuseagent.data.provider.mock.MockProvider
import com.example.aiuseagent.data.provider.openai.OpenAiCompatibleProvider
import com.example.aiuseagent.domain.provider.ChatRequest
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderEventContract
import com.example.aiuseagent.domain.provider.ProviderType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Headers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 单测里一律使用虚构凭据：真实 API Key 绝不进入仓库。 */
private const val FAKE_API_KEY = "test-key-not-secret"

/**
 * P2-B 的装配点：注册表里同时有 Mock 与三个真实供应商类型，且 DeepSeek 复用
 * OpenAI 兼容实现（不重复写一份协议逻辑）。
 */
class HttpProvidersTest {

    private fun registry() = HttpProviders.registry(
        transport = OkHttpTransport(),
        retryPolicy = RetryPolicy(maxAttempts = 1),
    )

    @Test
    fun registryCoversMockAndAllRealProviderTypes() {
        val registry = registry()

        assertEquals(
            setOf(
                ProviderType.MOCK,
                ProviderType.DEEPSEEK,
                ProviderType.OPENAI_COMPATIBLE,
                ProviderType.ANTHROPIC,
            ),
            registry.registeredTypes(),
        )
        assertEquals(emptySet<ProviderType>(), registry.missingTypes())
        assertTrue(registry.isImplemented(ProviderType.ANTHROPIC))
        assertTrue(registry.isImplemented(ProviderType.DEEPSEEK))
    }

    @Test
    fun deepSeekReusesTheOpenAiCompatibleImplementation() {
        val registry = registry()

        val deepSeek = registry.providerFor(ProviderType.DEEPSEEK)
        val generic = registry.providerFor(ProviderType.OPENAI_COMPATIBLE)

        assertTrue(deepSeek is OpenAiCompatibleProvider)
        assertTrue(generic is OpenAiCompatibleProvider)
        assertEquals(
            "DeepSeek 与通用 OpenAI 兼容服务必须共用同一个实现类",
            OpenAiCompatibleProvider::class,
            deepSeek!!::class,
        )
        assertEquals(OpenAiCompatibleProvider::class, generic!!::class)
        assertTrue(registry.providerFor(ProviderType.ANTHROPIC) is AnthropicMessagesProvider)
        assertTrue(registry.providerFor(ProviderType.MOCK) is MockProvider)
    }

    @Test
    fun everyRegisteredProviderReportsItsOwnType() {
        val registry = registry()

        for (type in ProviderType.entries) {
            val provider = registry.providerFor(type) ?: continue
            assertEquals(type, provider.type)
            assertTrue("未注册的类型不得回退成 Mock", type in registry.registeredTypes())
        }
        assertEquals(4, registry.registeredTypes().size)
    }

    @Test
    fun repositoryStreamsThroughTheRealAdapterEndToEnd() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(
                MockResponse(
                    200,
                    Headers.headersOf("Content-Type", "text/event-stream"),
                    "data: {\"choices\":[{\"delta\":{\"content\":\"端到端\"}}]}\n\ndata: [DONE]\n\n",
                )
            )

            val repository = HttpProviders.repository(retryPolicy = RetryPolicy(maxAttempts = 1))
            repository.selectActive(ProviderType.DEEPSEEK)
            repository.updateConfig(
                ProviderConfig.forType(ProviderType.DEEPSEEK).copy(
                    baseUrl = server.url("/v1").toString(),
                    model = "deepseek-chat",
                    allowInsecureHttp = true,
                    connectTimeoutMillis = 5_000,
                    readTimeoutMillis = 5_000,
                    totalTimeoutMillis = 15_000,
                )
            )
            repository.putCredential(ProviderType.DEEPSEEK, FAKE_API_KEY)

            assertTrue("配置齐全时应当就绪", repository.availability(ProviderType.DEEPSEEK).isReady)

            val events = repository.stream(ChatRequest.ofUserText("你好")).toList()

            assertEquals("端到端", ProviderEventContract.textOf(events))
            assertEquals(emptyList<String>(), ProviderEventContract.validate(events))
            assertEquals(1, server.requestCount)
            assertEquals(
                "Bearer " + FAKE_API_KEY,
                server.takeRequest().headers["Authorization"],
            )
        } finally {
            server.close()
        }
    }
}
