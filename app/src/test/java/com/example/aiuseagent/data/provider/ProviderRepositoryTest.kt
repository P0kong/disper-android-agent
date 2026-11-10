package com.example.aiuseagent.data.provider

import com.example.aiuseagent.data.provider.mock.MockProvider
import com.example.aiuseagent.data.provider.mock.MockScript
import com.example.aiuseagent.domain.provider.ChatRequest
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderErrorKind
import com.example.aiuseagent.domain.provider.ProviderEvent
import com.example.aiuseagent.domain.provider.ProviderEventContract
import com.example.aiuseagent.domain.provider.ProviderRegistry
import com.example.aiuseagent.domain.provider.ProviderType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 调度入口的诚实性：未实现的供应商只能得到 NOT_IMPLEMENTED 失败流，
 * 绝不允许退化成 Mock、也不允许生成假的「已连接」状态。
 */
class ProviderRepositoryTest {

    private val defaultRegistry = ProviderRegistry(mapOf(ProviderType.MOCK to MockProvider()))
    private val request = ChatRequest.ofUserText("你好")

    @Test
    fun defaultActiveTypeIsMockAndItIsReady() {
        val repository = ProviderRepository(registry = defaultRegistry)
        assertEquals(ProviderType.MOCK, repository.activeType())

        val availability = repository.availability()
        assertTrue(availability.isRegistered)
        assertTrue("Mock 配置必须开箱即用", availability.isReady)
        assertEquals("本地 Mock（不联网）", availability.statusLabel)
        assertFalse(availability.type.isRealNetworkProvider)
    }

    @Test
    fun registeredTypesAreOnlyMockInP2A() {
        val repository = ProviderRepository(registry = defaultRegistry)
        assertEquals(setOf(ProviderType.MOCK), repository.registeredTypes())
        assertTrue(repository.missingTypes().contains(ProviderType.DEEPSEEK))
        assertTrue(repository.missingTypes().contains(ProviderType.ANTHROPIC))
        assertNull("未注册的类型不得返回 Provider", repository.providerFor(ProviderType.DEEPSEEK))
    }

    @Test
    fun realProviderWithoutKeyIsNotReadyAndNotImplemented() {
        val repository = ProviderRepository(registry = defaultRegistry)
        val availability = repository.availability(ProviderType.DEEPSEEK)

        assertFalse(availability.isRegistered)
        assertFalse(availability.isReady)
        assertEquals("尚未实现", availability.statusLabel)
        assertTrue("必须解释清楚为什么不可用", availability.detail.contains("P2-B"))
    }

    @Test
    fun realProviderWithKeyIsStillNotImplemented() {
        val repository = ProviderRepository(registry = defaultRegistry)
        repository.updateConfig(
            ProviderConfig(
                type = ProviderType.DEEPSEEK,
                baseUrl = "https://api.deepseek.com/v1",
                model = "some-model",
            ),
        )
        repository.putCredential(ProviderType.DEEPSEEK, "test-key-not-secret")

        val availability = repository.availability(ProviderType.DEEPSEEK)
        assertTrue("凭据存在应如实反映", availability.hasCredential)
        assertTrue("配置合法应如实反映", availability.configIssues.isEmpty())
        assertFalse("没有真实实现，就绝不能报就绪", availability.isReady)
        assertEquals("尚未实现", availability.statusLabel)
        assertFalse("凭据提示不得包含明文", availability.credentialHint!!.contains("test-key-not-secret"))
    }

    @Test
    fun streamForMockProducesFullContractSequence() = runTest {
        val repository = ProviderRepository(registry = defaultRegistry)
        val events = repository.stream(request).toList()

        assertTrue(events.first() is ProviderEvent.Started)
        assertTrue(events.last() is ProviderEvent.Completed)
        assertTrue(ProviderEventContract.validate(events).isEmpty())
    }

    @Test
    fun streamForUnimplementedProviderFailsWithoutPretendingSuccess() = runTest {
        val repository = ProviderRepository(registry = defaultRegistry)
        val events = repository.stream(request, ProviderType.ANTHROPIC).toList()

        assertEquals(2, events.size)
        assertTrue(events.first() is ProviderEvent.Started)
        val failed = events.last() as ProviderEvent.Failed
        assertEquals(ProviderErrorKind.NOT_IMPLEMENTED, failed.error.kind)
        assertTrue("事件契约对失败流同样成立", ProviderEventContract.validate(events).isEmpty())
        assertTrue("不得出现任何文本", events.none { it is ProviderEvent.TextDelta })
    }

    @Test
    fun unregisteredProviderErrorListsWhatIsActuallyRegistered() {
        val repository = ProviderRepository(registry = defaultRegistry)
        val error = repository.unavailableError(ProviderType.DEEPSEEK)
        assertEquals(ProviderErrorKind.NOT_IMPLEMENTED, error.kind)
        assertTrue("文案里的已注册集合必须来自注册表", error.detail!!.contains("MOCK"))
    }

    @Test
    fun streamSurfacesPreflightFailureForProviderThatRequiresKey() = runTest {
        val registry = ProviderRegistry(
            mapOf(ProviderType.MOCK to MockProvider(MockScript.normal(listOf("A")).copy(requiresKey = true))),
        )
        val repository = ProviderRepository(registry = registry)
        val events = repository.stream(request).toList()

        assertEquals(ProviderErrorKind.MISSING_API_KEY, (events.last() as ProviderEvent.Failed).error.kind)
    }

    @Test
    fun invalidConfigIsReportedAsNotReadyWithIssues() {
        val repository = ProviderRepository(registry = defaultRegistry)
        repository.updateConfig(
            ProviderConfig(type = ProviderType.MOCK, baseUrl = "https://api.example.com/v1", model = ""),
        )
        val availability = repository.availability(ProviderType.MOCK)

        assertFalse(availability.isReady)
        assertTrue("必须把配置问题逐条暴露出来", availability.configIssues.isNotEmpty())
        assertEquals("未配置完成", availability.statusLabel)
        assertEquals("https://api.example.com/v1", availability.config.baseUrl)
    }

    @Test
    fun selectingActiveProviderOnlyAffectsFutureRequests() {
        val repository = ProviderRepository(registry = defaultRegistry)
        repository.selectActive(ProviderType.DEEPSEEK)

        assertEquals(ProviderType.DEEPSEEK, repository.activeType())
        assertEquals("各供应商配置互不影响", "mock://local", repository.configFor(ProviderType.MOCK).baseUrl)
    }

    @Test
    fun credentialPersistenceLabelIsExposedForTheSettingsScreen() {
        val repository = ProviderRepository(registry = defaultRegistry)
        assertEquals(
            "界面必须能原样显示这段文案",
            "仅保存在内存：应用进程结束后需要重新输入",
            repository.availability(ProviderType.MOCK).credentialPersistenceLabel,
        )
    }
}
