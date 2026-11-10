package com.example.aiuseagent.data.provider

import com.example.aiuseagent.domain.provider.*
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class PreparedProviderTest {
    @Test fun credentialsRegisteredAfterProfileCreationNeverAppearInExposedIdentity() {
        val repository=HttpProviders.repository()
        repository.useConnectionProfile(ConnectionProfile("custom:visual",2,ProviderBrand.CLAUDE,
            Protocol.ANTHROPIC_MESSAGES,"https://example.invalid/v1","synthetic-model",
            "synthetic-version",configurationSaved=true))
        repository.putCredential(ProviderType.ANTHROPIC,"synthetic-version")
        val prepared=repository.prepare()
        assertFalse(prepared.config.connectionProfile?.apiVersion.orEmpty().contains("synthetic-version"))
        assertEquals(ProviderErrorKind.INVALID_CONFIG,prepared.preflight(ChatRequest.ofUserText("safe"))?.kind)
    }
    private val secret = "fake-split-credential"
    private val otherSecret = "other-credential-test"
    private val request = ChatRequest.ofUserText("safe test prompt")
    private val config = ProviderConfig(ProviderType.DEEPSEEK, "https://example.invalid", "original-model")

    @Test
    fun preparationFreezesConfigAndCredentialAcrossLaterChanges() = runTest {
        val provider = RecordingProvider()
        val repository = repository(provider)
        val prepared = repository.prepare()
        repository.updateConfig(config.copy(model = "changed-model"))
        repository.putCredential(ProviderType.DEEPSEEK, "changed-fake-credential")
        repository.selectActive(ProviderType.MOCK)
        prepared.stream(request).toList()
        prepared.stream(request).toList()
        assertEquals(ProviderType.DEEPSEEK, prepared.type)
        assertEquals("original-model", prepared.model)
        assertEquals(listOf("original-model", "original-model"), provider.models)
        assertEquals(listOf(secret, secret), provider.keys)
        assertTrue(repository.containsKnownCredential("old $secret and changed-fake-credential"))
    }

    @Test
    fun rejectsKnownCredentialsInEveryPromptAndToolFieldBeforeProviderRuns() = runTest {
        val provider = RecordingProvider()
        val prepared = repository(provider).prepare()
        val tool = ToolDefinition("read_file", "safe", buildJsonObject { put("type", "object") })
        val toolCall = ProviderToolCall("id", "read_file", "{}")
        val variants = listOf(
            request.copy(system = secret),
            ChatRequest.ofUserText(otherSecret),
            request.copy(messages = listOf(ProviderMessage(ChatRole.TOOL, secret, toolCallId = "id"))),
            request.copy(messages = listOf(ProviderMessage(ChatRole.TOOL, "result", toolCallId = secret))),
            request.copy(messages = listOf(ProviderMessage(ChatRole.ASSISTANT, "", toolCalls = listOf(toolCall.copy(arguments = "{\"key\":\"$secret\"}"))))),
            request.copy(tools = listOf(tool.copy(description = secret))),
            request.copy(tools = listOf(tool.copy(parameters = buildJsonObject { put("type", "object"); put("description", otherSecret) }))),
        )
        for (variant in variants) {
            val failure = prepared.stream(variant).toList().last() as ProviderEvent.Failed
            assertEquals(ProviderErrorKind.INVALID_CONFIG, failure.error.kind)
            assertFalse(failure.error.detail.orEmpty().contains(secret))
        }
        assertTrue(provider.keys.isEmpty())
    }

    @Test
    fun splitEchoAndOtherProviderCredentialAreMaskedBeforeTextLeavesPreparedStream() = runTest {
        val provider = RecordingProvider { flow {
            emit(ProviderEvent.Started(ProviderType.DEEPSEEK, "safe"))
            emit(ProviderEvent.TextDelta("answer fake-"))
            emit(ProviderEvent.TextDelta("split-credential / other-"))
            emit(ProviderEvent.TextDelta("credential-test"))
            emit(ProviderEvent.Completed())
        } }
        val events = repository(provider).prepare().stream(request).toList()
        assertEquals("answer *** / ***", ProviderEventContract.textOf(events))
        assertTrue(events.last() is ProviderEvent.Completed)
        assertTrue(ProviderEventContract.validate(events).isEmpty())
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun cancellationDropsUnresolvedPrefixAndPreservesOnlySafeText() = runTest {
        val provider = RecordingProvider { flow {
            emit(ProviderEvent.Started(ProviderType.DEEPSEEK, "safe"))
            emit(ProviderEvent.TextDelta("safe answer fake-split-"))
            awaitCancellation()
        } }
        val events = mutableListOf<ProviderEvent>()
        val job = launch { repository(provider).prepare().stream(request).collect { events += it } }
        runCurrent()
        job.cancelAndJoin()
        assertEquals("safe answer ", ProviderEventContract.textOf(events))
        assertTrue(events.none { it.isTerminal })
    }

    @Test
    fun toolsContainingKnownCredentialAreRejectedWithoutExposingArguments() = runTest {
        val provider = RecordingProvider { flow {
            emit(ProviderEvent.Started(ProviderType.DEEPSEEK, "safe"))
            emit(ProviderEvent.ToolCalls(listOf(ProviderToolCall("id", "read_file", "{\"text\":\"$otherSecret\"}"))))
            emit(ProviderEvent.Completed(FinishReason.TOOL_CALLS))
        } }
        val events = repository(provider).prepare().stream(request).toList()
        assertTrue(events.none { it is ProviderEvent.ToolCalls })
        assertEquals(ProviderErrorKind.PROTOCOL, (events.last() as ProviderEvent.Failed).error.kind)
        assertTrue(ProviderEventContract.validate(events).isEmpty())
    }

    @Test
    fun clearRetainsCredentialKnowledgeForTranscriptProtection() {
        val repository = repository(RecordingProvider())
        repository.clearCredential(ProviderType.DEEPSEEK)
        assertTrue(repository.containsKnownCredential(secret))
        assertFalse(repository.credentialStatus(ProviderType.DEEPSEEK).isPresent)
    }

    @Test
    fun preparedMetadataAndPrintableValuesNeverContainCredentials() {
        val repository = repository(RecordingProvider())
        repository.updateConfig(config.copy(model = secret, baseUrl = "https://example.invalid/$otherSecret"))
        val prepared = repository.prepare()
        assertFalse(prepared.model.contains(secret))
        assertFalse(prepared.config.baseUrl.contains(otherSecret))
        assertFalse(prepared.toString().contains(secret))
        assertFalse(config.copy(model = secret).toString().contains(secret))
        assertNotNull(prepared.preflight(request))
    }

    private fun repository(provider: ModelProvider): ProviderRepository = ProviderRepository(
        ProviderRegistry(mapOf(ProviderType.DEEPSEEK to provider)),
    ).also {
        it.updateConfig(config)
        it.putCredential(ProviderType.DEEPSEEK, secret)
        it.putCredential(ProviderType.ANTHROPIC, otherSecret)
        it.selectActive(ProviderType.DEEPSEEK)
    }

    private class RecordingProvider(private val script: (() -> Flow<ProviderEvent>)? = null) : ModelProvider {
        override val type = ProviderType.DEEPSEEK
        val keys = mutableListOf<String?>()
        val models = mutableListOf<String>()
        override fun stream(request: ChatRequest, config: ProviderConfig, credential: ProviderCredential): Flow<ProviderEvent> {
            keys += credential.reveal()
            models += config.model
            return script?.invoke() ?: flow {
                emit(ProviderEvent.Started(type, config.model))
                emit(ProviderEvent.TextDelta("safe answer"))
                emit(ProviderEvent.Completed())
            }
        }
    }
}
