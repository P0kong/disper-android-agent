package com.example.aiuseagent.ui.settings

import android.content.SharedPreferences
import com.example.aiuseagent.data.FakeDaoStore
import com.example.aiuseagent.data.SessionRepository
import com.example.aiuseagent.data.SettingsRepository
import com.example.aiuseagent.data.generation.GenerationCoordinator
import com.example.aiuseagent.data.generation.GenerationLimits
import com.example.aiuseagent.data.generation.GenerationSubmission
import com.example.aiuseagent.data.provider.HttpProviders
import com.example.aiuseagent.data.provider.ProviderRepository
import com.example.aiuseagent.data.provider.http.HttpTimeouts
import com.example.aiuseagent.data.provider.http.HttpTransport
import com.example.aiuseagent.data.provider.http.ProviderHttpRequest
import com.example.aiuseagent.data.provider.http.StreamingHttpResponse
import com.example.aiuseagent.data.workspace.WorkspaceToolResult
import com.example.aiuseagent.domain.provider.*
import java.lang.reflect.Proxy
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.*
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Runs the real settings/repository boundaries with synthetic credentials and a network tripwire. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsModelConfigurationTest {
    @Test fun savedProductConfigurationSendsImageBytesOnlyAfterCredentialsAndRejectsChangedIdentity()=runTest {
        for(type in listOf(ProviderType.OPENAI_COMPATIBLE,ProviderType.ANTHROPIC)) {
            MockWebServer().use { server ->
                server.start()
                val official=if(type==ProviderType.ANTHROPIC) "https://api.anthropic.com/v1" else "https://api.openai.com/v1"
                val model=if(type==ProviderType.ANTHROPIC) "claude-sonnet-5-5" else "gpt-4.1-mini"
                val expectedPath=if(type==ProviderType.ANTHROPIC) "/v1/messages" else "/v1/chat/completions"
                // Only this test redirects the fixed official request to an ephemeral loopback server.
                // The production profile/resolver and all payload serialization stay unchanged.
                val transport=object:HttpTransport {
                    override suspend fun open(request:ProviderHttpRequest,timeouts:HttpTimeouts):StreamingHttpResponse {
                        assertEquals(official.substringBefore("/v1")+expectedPath,request.url)
                        return com.example.aiuseagent.data.provider.http.OkHttpTransport().open(
                            request.copy(url=server.url(expectedPath).toString(),allowInsecureHttp=true),timeouts)
                    }
                }
                val prefs=Preferences();val settings=SettingsRepository(prefs.prefs)
                val first=HttpProviders.repository(transport=transport)
                val vm=SettingsViewModel(settings,first)
                vm.onProviderTypeChange(type.name);vm.onBaseUrlChange(official);vm.onModelChange(model)
                vm.onApiKeyChange(SECRET);vm.saveProviderConfig();runCurrent()
                assertEquals(0,server.requestCount)
                val restored=HttpProviders.repository(transport=transport)
                assertTrue(settings.restoreProviderConfiguration(restored))
                val bytes=java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADElEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC")
                val digest=java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it)}
                val image=ProviderContentPart.Image(java.util.UUID.randomUUID().toString(),"image/png",digest)
                val request=ChatRequest(listOf(ProviderMessage(ChatRole.USER,"",parts=listOf(image))),
                    attachmentPayloads=ProviderAttachmentPayloads(mapOf(image to bytes)))
                val absent=runBlocking {restored.prepare().stream(request).toList().last() as ProviderEvent.Failed}
                assertEquals(ProviderErrorKind.MISSING_API_KEY,absent.error.kind);assertEquals(0,server.requestCount)
                restored.putCredential(type,SECRET)
                val body=if(type==ProviderType.ANTHROPIC)
                    "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"ok\"}}\n\nevent: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
                    else "data: {\"choices\":[{\"delta\":{\"content\":\"ok\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
                server.enqueue(MockResponse.Builder().code(200).body(body).build())
                val terminal=runBlocking {restored.prepare().stream(request).toList().last()}
                assertTrue("$type terminal=$terminal",terminal is ProviderEvent.Completed)
                val sent=server.takeRequest(5,java.util.concurrent.TimeUnit.SECONDS)!!
                val part=Json.parseToJsonElement(sent.body!!.utf8()).jsonObject["messages"]!!.jsonArray.single()
                    .jsonObject["content"]!!.jsonArray.single().jsonObject
                val encoded=if(type==ProviderType.ANTHROPIC) part["source"]!!.jsonObject["data"]!!.jsonPrimitive.content
                    else part["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content.substringAfter(',')
                assertArrayEquals(bytes,java.util.Base64.getDecoder().decode(encoded))
                if(type==ProviderType.ANTHROPIC) assertEquals("2023-06-01",sent.headers["anthropic-version"])
                val profile=requireNotNull(restored.prepare().config.connectionProfile)
                restored.useConnectionProfile(profile.copy(modelId="unverified-model",revision=profile.revision+1))
                assertTrue(runBlocking {restored.prepare().stream(request).toList().last()} is ProviderEvent.Failed)
                assertEquals(1,server.requestCount);assertNoSecret(prefs)
            }
        }
    }
    @Test fun editingAnAppliedVisualIdentityWithdrawsTheOldCapabilityLabel()=runTest {
        val h=SettingsHarness()
        h.vm.onProviderTypeChange("OPENAI_COMPATIBLE")
        h.vm.onBaseUrlChange("https://api.openai.com/v1");h.vm.onModelChange("gpt-4.1-mini")
        h.vm.saveProviderConfig();advanceUntilIdle()
        assertTrue(h.vm.providerForm.value.imageCapabilityLabel.contains("文档支持"))
        h.vm.onModelChange("unknown")
        assertFalse(h.vm.providerForm.value.imageCapabilityLabel.contains("文档支持"))
        h.vm.onModelChange("gpt-4.1-mini");h.vm.saveProviderConfig();advanceUntilIdle()
        h.vm.onBaseUrlChange("https://unknown.invalid/v1")
        assertFalse(h.vm.providerForm.value.imageCapabilityLabel.contains("文档支持"))
        assertEquals(0,h.transport.opens)
    }
    @Test fun failedDurableSaveIsReportedWithoutClaimingSavedOrKeepingCredentialDraft()=runTest {
        val h=SettingsHarness();h.fill();h.memory.commitSucceeds=false
        h.vm.saveProviderConfig();advanceUntilIdle()
        assertTrue(h.vm.providerForm.value.notice.orEmpty().contains("保存失败"))
        assertEquals("",h.vm.providerForm.value.apiKey)
        assertTrue(h.memory.values.isEmpty())
        assertEquals(0,h.transport.opens)
    }
    @Test fun visualSettingsSaveAndRestoreTheSameProfileWithoutRestoringCredentials()=runTest {
        for((type,base,model) in listOf(
            Triple("OPENAI_COMPATIBLE","https://api.openai.com/v1","gpt-4.1-mini-2025-04-14"),
            Triple("ANTHROPIC","https://api.anthropic.com/v1","claude-sonnet-5-5"))) {
            val h=SettingsHarness()
            h.vm.onProviderTypeChange(type);h.vm.onBaseUrlChange(base);h.vm.onModelChange(model)
            h.vm.onApiKeyChange(SECRET);h.vm.saveProviderConfig();advanceUntilIdle()
            assertEquals(type,h.providers.activeType().name)
            val saved=requireNotNull(h.settings.savedConnectionProfile())
            assertEquals(base,saved.baseUrl);assertEquals(model,saved.modelId)
            assertEquals(if(type=="ANTHROPIC") ProviderBrand.CLAUDE else ProviderBrand.OPENAI,saved.brand)
            assertEquals(if(type=="ANTHROPIC") "2023-06-01" else null,saved.apiVersion)
            assertEquals(CapabilitySupport.SUPPORTED,h.providers.prepare().config.imageCapability?.documentation)
            val restored=HttpProviders.repository(transport=h.transport)
            assertTrue(SettingsRepository(h.memory.prefs).restoreProviderConfiguration(restored))
            assertEquals(saved.profileId,restored.prepare().profileId)
            assertEquals(saved.revision,restored.prepare().profileRevision)
            assertEquals(CapabilitySupport.SUPPORTED,restored.prepare().config.imageCapability?.documentation)
            assertFalse(restored.credentialStatus(restored.activeType()).isPresent)
            assertEquals(0,h.transport.opens);assertNoSecret(h.memory)
        }
    }
    @Test fun unknownStoredProviderNeverRestoresAsDeepSeek() {
        val h=SettingsHarness()
        h.memory.values[SettingsRepository.KEY_PROVIDER_TYPE]="UNRECOGNIZED_LEGACY"
        val old=h.memory.values.toMap()
        assertFalse(h.settings.restoreProviderConfiguration(h.providers))
        assertEquals(ProviderType.MOCK,h.providers.activeType())
        assertEquals(old,h.memory.values)
    }
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun typingAndLocalCheckNeitherApplyNorPersistNorRequest() = runTest {
        val h = SettingsHarness()
        h.fill()
        h.vm.checkProviderFormLocally()
        advanceUntilIdle()
        assertTrue(h.memory.values.isEmpty())
        assertFalse(h.providers.credentialStatus(ProviderType.DEEPSEEK).isPresent)
        assertEquals(ProviderType.MOCK, h.providers.activeType())
        assertTrue(h.vm.providerForm.value.notice!!.contains("尚未发送请求"))
        assertEquals(0, h.transport.opens)
    }

    @Test fun explicitApplyPersistsNonsecretConfigAndConnectionIdentityAndClearsTheKeyInput() = runTest {
        val h = SettingsHarness()
        h.fill()
        h.vm.saveProviderConfig()
        advanceUntilIdle()
        assertEquals(setOf(SettingsRepository.KEY_PROVIDER_TYPE, SettingsRepository.KEY_PROVIDER_BASE,
            SettingsRepository.KEY_PROVIDER_MODEL,SettingsRepository.KEY_PROFILE_ID,SettingsRepository.KEY_PROFILE_REVISION,
            SettingsRepository.KEY_CONNECTION_PROFILE), h.memory.values.keys)
        assertEquals(ProviderType.DEEPSEEK, h.providers.activeType())
        assertEquals("deepseek-flash", h.providers.configFor(ProviderType.DEEPSEEK).model)
        assertTrue(h.providers.credentialStatus(ProviderType.DEEPSEEK).isPresent)
        assertEquals("", h.vm.providerForm.value.apiKey)
        assertTrue(h.vm.providerForm.value.credentialConfigured)
        assertTrue(h.vm.providerForm.value.appliedLabel!!.contains("尚未验证"))
        assertNoSecret(h.memory)
        assertFalse(h.vm.providerForm.value.toString().contains(SECRET))
        assertEquals(0, h.transport.opens)
    }

    @Test fun deepSeekRejectsNonofficialOrCredentialBearingEndpointsBeforeApply() = runTest {
        val invalid = listOf(
            "http://api.deepseek.com", "https://elsewhere.invalid/v1", "https://api.deepseek.com.evil.invalid",
            "https://name@api.deepseek.com", "https://api.deepseek.com:443", "https://api.deepseek.com/v2",
            "https://api.deepseek.com?credential=synthetic", "https://api.deepseek.com#synthetic",
        )
        for (url in invalid) {
            val h = SettingsHarness()
            h.fill(baseUrl = url)
            h.vm.saveProviderConfig()
            advanceUntilIdle()
            assertTrue("Unexpected persistence for rejected endpoint", h.memory.values.isEmpty())
            assertFalse(h.providers.credentialStatus(ProviderType.DEEPSEEK).isPresent)
            assertEquals(ProviderType.MOCK, h.providers.activeType())
            assertTrue(h.vm.providerForm.value.notice!!.contains("配置无效"))
            assertEquals(0, h.transport.opens)
        }
    }

    @Test fun officialVersionPrefixIsAcceptedWithoutSending() = runTest {
        val h = SettingsHarness()
        h.fill(baseUrl = "https://api.deepseek.com/v1/")
        h.vm.saveProviderConfig()
        advanceUntilIdle()
        assertEquals("https://api.deepseek.com/v1/", h.settings.savedModelConfig().baseUrl)
        assertTrue(h.providers.credentialStatus(ProviderType.DEEPSEEK).isPresent)
        assertNoSecret(h.memory)
        assertEquals(0, h.transport.opens)
    }

    @Test fun candidateCredentialInsideModelIsRejectedBeforeEitherFieldIsPersisted() = runTest {
        val h = SettingsHarness()
        h.fill(model = "model-$SECRET")
        h.vm.saveProviderConfig()
        advanceUntilIdle()
        assertTrue(h.memory.values.isEmpty())
        assertFalse(h.providers.credentialStatus(ProviderType.DEEPSEEK).isPresent)
        assertFalse(h.vm.providerForm.value.notice!!.contains(SECRET))
        assertEquals(0, h.transport.opens)
    }

    @Test fun previouslyKnownCredentialCannotBeSavedAsModelAfterClear() = runTest {
        val h = SettingsHarness()
        h.providers.putCredential(ProviderType.DEEPSEEK, SECRET)
        h.providers.clearCredential(ProviderType.DEEPSEEK)
        h.fill(model = "model-$SECRET", key = "")
        h.vm.saveProviderConfig()
        advanceUntilIdle()
        assertTrue(h.memory.values.isEmpty())
        assertFalse(h.providers.credentialStatus(ProviderType.DEEPSEEK).isPresent)
        assertFalse(h.vm.providerForm.value.notice!!.contains(SECRET))
        assertEquals(0, h.transport.opens)
    }

    @Test fun clearCredentialTakesEffectSynchronouslyAndFreshProcessHasNoKey() = runTest {
        val h = SettingsHarness()
        h.fill(); h.vm.saveProviderConfig(); advanceUntilIdle()
        h.vm.onApiKeyChange("another-synthetic-draft")
        h.vm.clearCredential()
        assertFalse(h.providers.credentialStatus(ProviderType.DEEPSEEK).isPresent)
        assertFalse(h.vm.providerForm.value.credentialConfigured)
        assertEquals("", h.vm.providerForm.value.apiKey)
        val reopened = SettingsRepository(h.memory.prefs)
        assertEquals("DEEPSEEK", reopened.savedModelConfig().providerType)
        assertEquals("deepseek-flash", reopened.savedModelConfig().model)
        val freshProviders = HttpProviders.repository(transport = h.transport)
        val freshVm = SettingsViewModel(reopened, freshProviders)
        assertFalse(freshProviders.credentialStatus(ProviderType.DEEPSEEK).isPresent)
        assertEquals("", freshVm.providerForm.value.apiKey)
        assertNoSecret(h.memory)
        assertFalse(h.memory.values.values.any { it.toString().contains("another-synthetic-draft") })
        assertEquals(0, h.transport.opens)
    }

    @Test fun changingDraftProviderDiscardsKeyButDoesNotApplySelection() = runTest {
        val h = SettingsHarness()
        h.fill(); h.vm.saveProviderConfig(); advanceUntilIdle()
        h.vm.onApiKeyChange("synthetic-unsaved-draft")
        h.vm.onProviderTypeChange("MOCK")
        assertEquals("", h.vm.providerForm.value.apiKey)
        assertEquals("MOCK", h.vm.providerForm.value.providerType)
        assertFalse(h.vm.providerForm.value.credentialConfigured)
        assertEquals(ProviderType.DEEPSEEK, h.providers.activeType())
        h.vm.clearProviderForm()
        assertFalse("Mock form must not borrow the active DeepSeek credential badge", h.vm.providerForm.value.credentialConfigured)
        h.vm.onProviderTypeChange("MOCK")
        h.vm.saveProviderConfig(); advanceUntilIdle()
        assertEquals(ProviderType.MOCK, h.providers.activeType())
        assertTrue(h.vm.providerForm.value.appliedLabel!!.contains("不联网"))
        assertTrue(h.providers.credentialStatus(ProviderType.DEEPSEEK).isPresent)
        assertFalse(h.providers.credentialStatus(ProviderType.MOCK).isPresent)
        assertNoSecret(h.memory)
        assertEquals(0, h.transport.opens)
    }

    @Test fun applyingNewConfigStopsTheActiveGenerationBeforeChangingItsSnapshot() = runTest {
        val memory = Preferences()
        val settings = SettingsRepository(memory.prefs)
        var cancelled = 0
        var modelAtCancellation = ""
        lateinit var providers: ProviderRepository
        val hanging = object : ModelProvider {
            override val type = ProviderType.DEEPSEEK
            override fun stream(request: ChatRequest, config: ProviderConfig, credential: ProviderCredential): Flow<ProviderEvent> = flow {
                emit(ProviderEvent.TextDelta("synthetic partial"))
                try { awaitCancellation() } finally {
                    cancelled++
                    modelAtCancellation = providers.configFor(ProviderType.DEEPSEEK).model
                }
            }
        }
        providers = ProviderRepository(ProviderRegistry(mapOf(ProviderType.DEEPSEEK to hanging))).also {
            it.updateConfig(ProviderConfig(ProviderType.DEEPSEEK, "https://api.deepseek.com", "original-model"))
            it.putCredential(ProviderType.DEEPSEEK, SECRET)
            it.selectActive(ProviderType.DEEPSEEK)
        }
        val store = FakeDaoStore()
        val sessions = SessionRepository(store.sessionDao, store.messageDao, store.generationPersistence) { 100L }
        val processScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val coordinator = GenerationCoordinator(sessions, providers, processScope,
                toolExecutor = { _, _, _, _ -> WorkspaceToolResult(false, "unused") },
                workspaceAvailable = { true }, limits = GenerationLimits(joinMillis = 500, saveMillis = 500))
            runCurrent()
            assertTrue(coordinator.submit(GenerationSubmission(null, "synthetic question")))
            runCurrent()
            assertTrue(coordinator.state.value.busy)
            val vm = SettingsViewModel(settings, providers, coordinator)
            vm.onModelChange("deepseek-flash")
            vm.saveProviderConfig()
            advanceUntilIdle()
            assertEquals(1, cancelled)
            assertEquals("original-model", modelAtCancellation)
            assertFalse(coordinator.state.value.busy)
            assertEquals("CANCELLED", store.generations.value.single().status)
            assertEquals("deepseek-flash", providers.configFor(ProviderType.DEEPSEEK).model)
            assertNoSecret(memory)
        } finally { processScope.cancel() }
    }

    @Test fun clearingCredentialOrEditingDraftWhileApplyWaitsInvalidatesThePendingSave() = runTest {
        for (clearCredential in listOf(true, false)) {
            val memory = Preferences()
            val cancellationStarted = CompletableDeferred<Unit>()
            val allowCancellationToFinish = CompletableDeferred<Unit>()
            val hanging = object : ModelProvider {
                override val type = ProviderType.DEEPSEEK
                override fun stream(request: ChatRequest, config: ProviderConfig, credential: ProviderCredential): Flow<ProviderEvent> = flow {
                    emit(ProviderEvent.Started(type, config.model))
                    emit(ProviderEvent.TextDelta("synthetic partial"))
                    try { awaitCancellation() } finally {
                        withContext(NonCancellable) {
                            cancellationStarted.complete(Unit)
                            allowCancellationToFinish.await()
                        }
                    }
                }
            }
            val providers = ProviderRepository(ProviderRegistry(mapOf(ProviderType.DEEPSEEK to hanging))).also {
                it.updateConfig(ProviderConfig(ProviderType.DEEPSEEK, "https://api.deepseek.com", "original-model"))
                it.putCredential(ProviderType.DEEPSEEK, SECRET)
                it.selectActive(ProviderType.DEEPSEEK)
            }
            val store = FakeDaoStore()
            val sessions = SessionRepository(store.sessionDao, store.messageDao, store.generationPersistence) { 100L }
            val processScope = CoroutineScope(SupervisorJob() + dispatcher)
            try {
                val coordinator = GenerationCoordinator(sessions, providers, processScope,
                    toolExecutor = { _, _, _, _ -> WorkspaceToolResult(false, "unused") },
                    workspaceAvailable = { true }, limits = GenerationLimits(joinMillis = 500, saveMillis = 500))
                runCurrent()
                assertTrue(coordinator.submit(GenerationSubmission(null, "synthetic question")))
                runCurrent()
                val vm = SettingsViewModel(SettingsRepository(memory.prefs), providers, coordinator)
                val unappliedKey = "FAKE_PENDING_APPLY_CREDENTIAL"
                vm.onApiKeyChange(unappliedKey)
                vm.onModelChange("pending-model")
                vm.saveProviderConfig()
                runCurrent()
                assertTrue("Apply must be suspended while actual provider cancellation is held", cancellationStarted.isCompleted)
                assertTrue(coordinator.state.value.busy)
                assertTrue(memory.values.isEmpty())
                assertEquals("original-model", providers.configFor(ProviderType.DEEPSEEK).model)

                if (clearCredential) {
                    vm.clearCredential()
                    assertFalse(providers.credentialStatus(ProviderType.DEEPSEEK).isPresent)
                }
                vm.onModelChange("newest-unsaved-draft")
                allowCancellationToFinish.complete(Unit)
                advanceUntilIdle()

                assertFalse(coordinator.state.value.busy)
                assertEquals("CANCELLED", store.generations.value.single().status)
                assertEquals("original-model", providers.configFor(ProviderType.DEEPSEEK).model)
                assertEquals("newest-unsaved-draft", vm.providerForm.value.model)
                assertEquals(if (clearCredential) "" else unappliedKey, vm.providerForm.value.apiKey)
                assertEquals(!clearCredential, providers.credentialStatus(ProviderType.DEEPSEEK).isPresent)
                assertFalse("An obsolete apply must never put its captured credential", providers.containsKnownCredential(unappliedKey))
                assertTrue("An obsolete apply must not persist any configuration", memory.values.isEmpty())
                assertNoSecret(memory)
            } finally {
                allowCancellationToFinish.complete(Unit)
                processScope.cancel()
                runCurrent()
            }
        }
    }

    private class SettingsHarness {
        val memory = Preferences()
        val settings = SettingsRepository(memory.prefs)
        val transport = NoNetworkTransport()
        val providers = HttpProviders.repository(transport = transport)
        val vm = SettingsViewModel(settings, providers)
        fun fill(baseUrl: String = "https://api.deepseek.com", model: String = "deepseek-flash", key: String = SECRET) {
            vm.onProviderTypeChange("DEEPSEEK")
            vm.onBaseUrlChange(baseUrl); vm.onModelChange(model); vm.onApiKeyChange(key)
        }
    }

    private class NoNetworkTransport : HttpTransport {
        var opens = 0
        override suspend fun open(request: ProviderHttpRequest, timeouts: HttpTimeouts): StreamingHttpResponse {
            opens++
            throw AssertionError("Settings must not open a provider connection")
        }
    }

    private class Preferences {
        var commitSucceeds=true
        val values = mutableMapOf<String, Any>()
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "getString", "getBoolean", "getLong" -> values[args!![0]] ?: args[1]
                "contains" -> values.containsKey(args!![0])
                "getAll" -> values.toMap()
                "edit" -> editor()
                else -> null
            }
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val writes = mutableMapOf<String, Any?>()
            return Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                when (method.name) {
                    "putString", "putBoolean", "putLong" -> { writes[args!![0] as String] = args[1]; proxy }
                    "remove" -> { writes[args!![0] as String] = null; proxy }
                    "apply", "commit" -> {
                        if(method.name=="apply" || commitSucceeds)
                            writes.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                        if (method.name == "commit") commitSucceeds else null
                    }
                    else -> null
                }
            } as SharedPreferences.Editor
        }
    }

    companion object {
        private const val SECRET = "FAKE_SETTINGS_CREDENTIAL_NOT_SECRET"
        private fun assertNoSecret(memory: Preferences) {
            assertFalse(memory.values.values.any { it.toString().contains(SECRET) })
            assertFalse(memory.values.keys.any { it.contains("key", true) || it.contains("credential", true) })
        }
    }
}
