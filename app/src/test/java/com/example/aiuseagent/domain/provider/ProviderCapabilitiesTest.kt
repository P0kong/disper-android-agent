package com.example.aiuseagent.domain.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Explicit fixture records are not a product model catalogue and never contact a provider. */
class ProviderCapabilitiesTest {
    private fun profile(protocol: Protocol = Protocol.CHAT_COMPLETIONS) = ConnectionProfile(
        profileId = "profile-a", revision = 1, brand = ProviderBrand.CUSTOM,
        protocol = protocol, baseUrl = "https://api.example.test/v1",
        modelId = "fixture-model", apiVersion = "fixture-api-version",
    )

    private fun source(kind: CapabilitySourceKind) = CapabilitySource(
        kind = kind,
        reference = when (kind) {
            CapabilitySourceKind.OFFICIAL_DOCUMENTATION -> "https://docs.example.test/model-contract"
            CapabilitySourceKind.MODEL_LIST -> "https://api.example.test/v1/models"
            CapabilitySourceKind.PROTOCOL_FIXTURE -> "fixture:messages-tool-round-trip"
            CapabilitySourceKind.LIVE_SERVICE -> "verification:synthetic-receipt-without-request-data"
        },
        checkedOn = "2026-10-23",
    )

    private fun documented(support: CapabilitySupport = CapabilitySupport.SUPPORTED) = FeatureCapability(
        documentation = support,
        sources = listOf(source(CapabilitySourceKind.OFFICIAL_DOCUMENTATION)),
    )

    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject

    @Test
    fun modelListingDoesNotProveAnyFeatureOrContextLimitEvenForPersuasiveNames() {
        for (name in listOf("gpt-vision-thinking-tools", "claude-ultra", "gemini-pro", "unknown-model")) {
            val record = ModelCapabilities.fromModelList(
                profile().copy(modelId = name, configurationSaved = true),
                source(CapabilitySourceKind.MODEL_LIST),
            )
            for (feature in ModelFeature.entries) {
                val capability = record.feature(feature)
                assertEquals(CapabilitySupport.UNKNOWN, capability.documentation)
                assertEquals(VerificationState.NOT_TESTED, capability.protocolTest)
                assertEquals(VerificationState.NOT_TESTED, capability.liveService)
                assertFalse(record.supports(feature))
            }
            assertNull(record.thinkingParametersFor(profile().copy(modelId = name), "ultra"))
            assertEquals(ContextLimitDecision.UNKNOWN, record.contextLimits.assess(10, 10))
        }
    }

    @Test
    fun documentationSupportsAFeatureWithoutClaimingProtocolOrLiveVerification() {
        val record = ModelCapabilities(
            identity = profile().identity(), features = mapOf(ModelFeature.TOOLS to documented()),
        )
        assertTrue(record.supports(ModelFeature.TOOLS))
        assertFalse(record.supports(ModelFeature.IMAGES))
        assertEquals(VerificationState.NOT_TESTED, record.feature(ModelFeature.TOOLS).protocolTest)
        assertEquals(VerificationState.NOT_TESTED, record.feature(ModelFeature.TOOLS).liveService)
    }

    @Test
    fun protocolFixturePassDoesNotClaimModelSupportOrRealServiceVerification() {
        val record = ModelCapabilities(
            identity = profile().identity(),
            features = mapOf(ModelFeature.IMAGES to FeatureCapability(
                protocolTest = VerificationState.PASSED,
                sources = listOf(source(CapabilitySourceKind.PROTOCOL_FIXTURE)),
            )),
        )
        assertFalse(record.supports(ModelFeature.IMAGES))
        assertEquals(CapabilitySupport.UNKNOWN, record.feature(ModelFeature.IMAGES).documentation)
        assertEquals(VerificationState.NOT_TESTED, record.feature(ModelFeature.IMAGES).liveService)
    }

    @Test
    fun protocolAndLiveResultsRemainIndependentOfTheDocumentedSupportFlag() {
        val capability = documented().copy(
            protocolTest = VerificationState.PASSED,
            liveService = VerificationState.FAILED,
            sources = listOf(
                source(CapabilitySourceKind.OFFICIAL_DOCUMENTATION),
                source(CapabilitySourceKind.PROTOCOL_FIXTURE),
                source(CapabilitySourceKind.LIVE_SERVICE),
            ),
        )
        val record = ModelCapabilities(profile().identity(), mapOf(ModelFeature.TOOLS to capability))
        assertEquals(CapabilitySupport.SUPPORTED, record.feature(ModelFeature.TOOLS).documentation)
        assertEquals(VerificationState.PASSED, record.feature(ModelFeature.TOOLS).protocolTest)
        assertEquals(VerificationState.FAILED, record.feature(ModelFeature.TOOLS).liveService)
    }

    @Test
    fun modelListEvidenceCannotBeRelabelledAsOfficialDocumentationOrLiveSuccess() {
        val listing = listOf(source(CapabilitySourceKind.MODEL_LIST))
        assertThrows(IllegalArgumentException::class.java) {
            FeatureCapability(documentation = CapabilitySupport.SUPPORTED, sources = listing)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FeatureCapability(liveService = VerificationState.PASSED, sources = listing)
        }
        assertThrows(IllegalArgumentException::class.java) {
            FeatureCapability(protocolTest = VerificationState.PASSED, sources = listing)
        }
    }

    @Test
    fun explicitLiveReceiptCanVerifyOneFeatureWithoutPromotingOtherFeatures() {
        val record = ModelCapabilities(profile().identity(), mapOf(
            ModelFeature.TEXT_STREAMING to FeatureCapability(
                liveService = VerificationState.PASSED,
                sources = listOf(source(CapabilitySourceKind.LIVE_SERVICE)),
            ),
        ))
        assertTrue(record.supports(ModelFeature.TEXT_STREAMING))
        assertFalse(record.supports(ModelFeature.TOOLS))
        assertFalse(record.supports(ModelFeature.DOCUMENTS))
        assertEquals(VerificationState.NOT_TESTED, record.feature(ModelFeature.DOCUMENTS).liveService)
    }

    @Test
    fun nativeDocumentSupportIsNotInferredFromTextOrImageSupport() {
        val record = ModelCapabilities(profile().identity(), mapOf(
            ModelFeature.TEXT_STREAMING to documented(), ModelFeature.IMAGES to documented(),
            ModelFeature.DOCUMENTS to documented(CapabilitySupport.UNSUPPORTED),
        ))
        assertTrue(record.supports(ModelFeature.TEXT_STREAMING))
        assertTrue(record.supports(ModelFeature.IMAGES))
        assertFalse(record.supports(ModelFeature.DOCUMENTS))
        assertEquals(CapabilitySupport.UNSUPPORTED, record.feature(ModelFeature.DOCUMENTS).documentation)
    }

    @Test
    fun capabilitiesCannotSurviveAChangeToAnyConnectionBindingDimension() {
        val original = profile()
        val record = ModelCapabilities(original.identity(), mapOf(ModelFeature.TOOLS to documented()))
        val variants = listOf(
            original.copy(profileId = "profile-b"), original.copy(revision = 2),
            original.copy(brand = ProviderBrand.DEEPSEEK), original.copy(protocol = Protocol.RESPONSES),
            original.copy(baseUrl = "https://another.example.test/v1"),
            original.copy(apiVersion = "different-version"), original.copy(modelId = "different-model"),
            original.copy(allowInsecureHttp = true),
        )
        assertTrue(record.forProfile(original).supports(ModelFeature.TOOLS))
        for (changed in variants) {
            val resolved = record.forProfile(changed)
            assertFalse(resolved.supports(ModelFeature.TOOLS))
            assertEquals(CapabilitySupport.UNKNOWN, resolved.feature(ModelFeature.TOOLS).documentation)
            assertEquals(VerificationState.NOT_TESTED, resolved.feature(ModelFeature.TOOLS).liveService)
        }
    }

    @Test
    fun thinkingOptionsProduceExactExplicitWireFieldsForEachProtocol() {
        // Shapes are explicit fixtures derived from the checked official protocol research.
        // No model-name lookup or product default is introduced here.
        val cases = listOf(
            Protocol.CHAT_COMPLETIONS to """{"enable_thinking":true,"reasoning_effort":"xhigh"}""",
            Protocol.RESPONSES to """{"reasoning":{"effort":"high"}}""",
            Protocol.ANTHROPIC_MESSAGES to """{"output_config":{"effort":"max"}}""",
            Protocol.GEMINI_NATIVE to """{"generationConfig":{"thinkingConfig":{"thinkingLevel":"high"}}}""",
        )
        for ((protocol, expected) in cases) {
            val connection = profile(protocol)
            val record = ModelCapabilities(
                identity = connection.identity(), features = mapOf(ModelFeature.THINKING to documented()),
                thinking = ThinkingConfiguration(options = listOf(
                    ThinkingOption(id = "maximum-supported", requestParameters = json(expected)),
                )),
            )
            assertEquals(json(expected), record.thinkingParametersFor(connection, "maximum-supported"))
            assertNull(record.thinkingParametersFor(connection, "ultra"))
            assertNull(record.thinkingParametersFor(connection.copy(modelId = "unknown"), "maximum-supported"))
        }
    }

    @Test
    fun thinkingBudgetAndAlwaysOnModesAreOnlyWhatTheRecordExplicitlyOffers() {
        val connection = profile(Protocol.ANTHROPIC_MESSAGES)
        val record = ModelCapabilities(
            identity = connection.identity(), features = mapOf(ModelFeature.THINKING to documented()),
            thinking = ThinkingConfiguration(
                options = listOf(ThinkingOption(
                    id = "budget-2048",
                    requestParameters = json("""{"thinking":{"type":"enabled","budget_tokens":2048}}"""),
                )), defaultOptionId = "budget-2048",
            ),
        )
        assertEquals(
            json("""{"thinking":{"type":"enabled","budget_tokens":2048}}"""),
            record.thinkingParametersFor(connection, "budget-2048"),
        )
        assertNull(record.thinkingParametersFor(connection, "off"))
        assertNull(record.thinkingParametersFor(connection, "high"))
    }

    @Test
    fun unprovenThinkingCannotEmitParametersEvenWhenAnOptionWasConfigured() {
        val connection = profile()
        val record = ModelCapabilities(
            identity = connection.identity(),
            thinking = ThinkingConfiguration(listOf(ThinkingOption(
                id = "high", requestParameters = json("""{"reasoning_effort":"high"}"""),
            ))),
        )
        assertNull(record.thinkingParametersFor(connection, "high"))
    }

    @Test
    fun ambiguousThinkingOptionIdsOrMissingDefaultAreRejected() {
        val option = ThinkingOption("high", json("""{"reasoning_effort":"high"}"""))
        assertThrows(IllegalArgumentException::class.java) {
            ThinkingConfiguration(listOf(option, option.copy(requestParameters = json("""{"reasoning_effort":"low"}"""))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ThinkingConfiguration(listOf(option), defaultOptionId = "invented-ultra")
        }
    }

    @Test
    fun thinkingParametersCannotOverrideRequestIdentityMessagesOrAuthentication() {
        for (payload in listOf(
            """{"model":"other-model"}""", """{"messages":[]}""",
            """{"headers":{"Authorization":"placeholder"}}""",
            """{"reasoning":{"effort":"high","unexpected":"value"}}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) { ThinkingOption("invalid", json(payload)) }
        }
    }

    @Test
    fun literalDotsInRootKeysCannotImpersonateNestedThinkingFields() {
        for (payload in listOf(
            """{"reasoning.effort":"high"}""",
            """{"output_config.effort":"max"}""",
            """{"generationConfig.thinkingConfig":{"thinkingBudget":1}}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ThinkingOption("invalid-root-shape", json(payload))
            }
        }
    }

    @Test
    fun literalDotsInNestedKeysCannotImpersonateAnotherObjectLevel() {
        for (payload in listOf(
            """{"generationConfig":{"thinkingConfig.thinkingBudget":1}}""",
            """{"generationConfig":{"thinkingConfig.thinkingLevel":"high"}}""",
            """{"generationConfig":{"thinkingConfig.includeThoughts":true}}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ThinkingOption("invalid-nested-shape", json(payload))
            }
        }
    }

    @Test
    fun mixedNestedAndLiteralDotKeysCannotCollapseToOneAllowedThinkingPath() {
        for (payload in listOf(
            """{"reasoning":{"effort":"low"},"reasoning.effort":"high"}""",
            """{"thinking":{"type":"enabled","budget_tokens":2048},"thinking.budget_tokens":4096}""",
            """{"generationConfig":{"thinkingConfig":{"thinkingBudget":1},"thinkingConfig.thinkingBudget":2}}""",
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                ThinkingOption("invalid-mixed-shape", json(payload))
            }
        }
    }

    @Test
    fun evidenceAndOptionInputsAreSnapshotsRatherThanMutableAuthority() {
        val evidence = mutableListOf(source(CapabilitySourceKind.OFFICIAL_DOCUMENTATION))
        val capability = FeatureCapability(documentation = CapabilitySupport.SUPPORTED, sources = evidence)
        evidence.clear()
        assertEquals(1, capability.sources.size)
        val optionValues = linkedMapOf("reasoning_effort" to Json.parseToJsonElement("\"high\""))
        val option = ThinkingOption("high", JsonObject(optionValues))
        optionValues["reasoning_effort"] = Json.parseToJsonElement("\"low\"")
        assertEquals(json("""{"reasoning_effort":"high"}"""), option.requestParameters)
    }

    @Test
    fun wrongProtocolMappingAndInvalidProfileCannotEmitThinking() {
        val option = ThinkingOption("high", json("""{"reasoning":{"effort":"high"}}"""))
        assertThrows(IllegalArgumentException::class.java) {
            ModelCapabilities(
                identity = profile(Protocol.GEMINI_NATIVE).identity(),
                features = mapOf(ModelFeature.THINKING to documented()),
                thinking = ThinkingConfiguration(listOf(option)),
            )
        }
        val connection = profile(Protocol.RESPONSES)
        val record = ModelCapabilities(connection.identity(), mapOf(ModelFeature.THINKING to documented()),
            thinking = ThinkingConfiguration(listOf(option)))
        assertNull(record.thinkingParametersFor(connection.copy(apiVersion = "\n"), "high"))
    }

    @Test
    fun contextLimitsUseCombinedWindowAndIndependentInputOutputCeilings() {
        val limits = ModelContextLimits(contextWindowTokens = 100, inputTokens = 90, outputTokens = 20)
        assertEquals(ContextLimitDecision.WITHIN_LIMITS, limits.assess(80, 20))
        assertEquals(ContextLimitDecision.EXCEEDED, limits.assess(81, 20))
        assertEquals(ContextLimitDecision.EXCEEDED, limits.assess(91, 1))
        assertEquals(ContextLimitDecision.EXCEEDED, limits.assess(1, 21))
        assertEquals(ContextLimitDecision.UNKNOWN, ModelContextLimits(inputTokens = 90).assess(80, 20))
        assertEquals(ContextLimitDecision.UNKNOWN, ModelContextLimits(contextWindowTokens = 100).assess(80, 20))
        assertEquals(ContextLimitDecision.UNKNOWN, ModelContextLimits(inputTokens = 90, outputTokens = 20).assess(80, 20))
        assertEquals(ContextLimitDecision.EXCEEDED, ModelContextLimits(inputTokens = 90).assess(91, 20))
    }

    @Test
    fun contextArithmeticCannotOverflowIntoAnAllowedRequest() {
        val limits = ModelContextLimits(contextWindowTokens = Long.MAX_VALUE)
        assertEquals(ContextLimitDecision.EXCEEDED, limits.assess(Long.MAX_VALUE, 1))
        assertThrows(IllegalArgumentException::class.java) { limits.assess(-1, 1) }
        assertThrows(IllegalArgumentException::class.java) { ModelContextLimits(outputTokens = 0) }
    }
}
