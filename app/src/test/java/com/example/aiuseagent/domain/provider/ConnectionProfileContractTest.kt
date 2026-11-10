package com.example.aiuseagent.domain.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contracts for the new non-secret connection model; no existing provider wiring is exercised. */
class ConnectionProfileContractTest {
    private fun profile() = ConnectionProfile(
        profileId = "profile-a",
        revision = 1,
        brand = ProviderBrand.DEEPSEEK,
        protocol = Protocol.CHAT_COMPLETIONS,
        baseUrl = "https://api.example.test/v1",
        modelId = "explicit-model-id",
    )

    @Test
    fun allEightBrandsAndCustomRequireAnExplicitModelRatherThanSelectingACandidate() {
        val brands = listOf(
            ProviderBrand.DEEPSEEK, ProviderBrand.QWEN, ProviderBrand.ZHIPU,
            ProviderBrand.KIMI, ProviderBrand.MINIMAX, ProviderBrand.OPENAI,
            ProviderBrand.CLAUDE, ProviderBrand.GEMINI, ProviderBrand.CUSTOM,
        )
        for (brand in brands) {
            val draft = ConnectionProfile(
                profileId = "draft-${brand.name}", brand = brand,
                protocol = Protocol.CHAT_COMPLETIONS,
            )
            assertTrue("No candidate model may become a product default: $brand", draft.modelId.isEmpty())
            assertFalse(draft.isConfigured())
            assertTrue(draft.validationIssues().contains(ConnectionProfileIssue.BLANK_MODEL))
        }
    }

    @Test
    fun brandDoesNotSelectOrOverrideTheExplicitProtocol() {
        val chat = profile()
        val responses = chat.copy(protocol = Protocol.RESPONSES)
        val messages = chat.copy(protocol = Protocol.ANTHROPIC_MESSAGES, apiVersion = "explicit-version")
        assertTrue(chat.isConfigured())
        assertTrue(responses.isConfigured())
        assertTrue(messages.isConfigured())
        assertNotEquals(chat.identity(), responses.identity())
        assertNotEquals(chat.identity(), messages.identity())
    }

    @Test
    fun customConnectionsCanExplicitlySelectEachRealProtocolWithoutNameSniffing() {
        for (protocol in listOf(
            Protocol.CHAT_COMPLETIONS, Protocol.RESPONSES,
            Protocol.ANTHROPIC_MESSAGES, Protocol.GEMINI_NATIVE,
        )) {
            val custom = profile().copy(
                brand = ProviderBrand.CUSTOM, protocol = protocol,
                baseUrl = "https://unrelated.example.test/prefix", apiVersion = "fixed-version",
            )
            assertTrue("Explicit protocol must not depend on host or model names", custom.isConfigured())
            assertEquals(protocol, custom.identity().protocol)
        }
    }

    @Test
    fun identitySeparatesProfilesModelsRevisionsEndpointsBrandsProtocolsAndApiVersions() {
        val original = profile()
        val variants = listOf(
            original.copy(profileId = "profile-b"),
            original.copy(revision = 2),
            original.copy(modelId = "another-model"),
            original.copy(baseUrl = "https://another.example.test/v1"),
            original.copy(brand = ProviderBrand.CUSTOM),
            original.copy(protocol = Protocol.RESPONSES),
            original.copy(apiVersion = "another-api-version"),
            original.copy(allowInsecureHttp = true),
        )
        assertEquals(9, (variants + original).map { it.identity() }.toSet().size)
    }

    @Test
    fun harmlessPrefixWhitespaceAndTrailingSlashesDoNotCreateAnotherIdentity() {
        val original = profile()
        assertEquals(original.identity(), original.copy(baseUrl = "  https://api.example.test/v1/  ").identity())
    }

    @Test
    fun savedConfigurationDoesNotMakeAnIncompleteDraftReady() {
        val draft = profile().copy(modelId = "", configurationSaved = true)
        assertFalse(draft.isConfigured())
        assertTrue(draft.validationIssues().contains(ConnectionProfileIssue.BLANK_MODEL))
    }

    @Test
    fun nativeApiVersionsMustBeExplicit() {
        for (protocol in listOf(Protocol.ANTHROPIC_MESSAGES, Protocol.GEMINI_NATIVE)) {
            val unset = profile().copy(protocol = protocol)
            assertTrue(unset.validationIssues().contains(ConnectionProfileIssue.API_VERSION_REQUIRED))
            assertFalse(unset.isConfigured())
            assertTrue(unset.copy(apiVersion = "fixed-version").isConfigured())
        }
    }

    @Test
    fun plainHttpRequiresExplicitOptIn() {
        val local = profile().copy(brand = ProviderBrand.CUSTOM, baseUrl = "http://127.0.0.1:8123/v1")
        assertTrue(local.validationIssues().contains(ConnectionProfileIssue.INSECURE_HTTP))
        assertFalse(local.isConfigured())
        assertTrue(local.copy(allowInsecureHttp = true).isConfigured())
    }

    @Test
    fun persistentPrefixRejectsUserInfoQueryAndFragmentInsteadOfStoringHiddenAuthChannels() {
        val cases = listOf(
            "https://placeholder@api.example.test/v1" to ConnectionProfileIssue.USER_INFO_NOT_ALLOWED,
            "https://api.example.test/v1?option=placeholder" to ConnectionProfileIssue.QUERY_NOT_ALLOWED,
            "https://api.example.test/v1#placeholder" to ConnectionProfileIssue.FRAGMENT_NOT_ALLOWED,
        )
        for ((url, issue) in cases) {
            val invalid = profile().copy(baseUrl = url)
            assertFalse(invalid.isConfigured())
            assertTrue(invalid.validationIssues().contains(issue))
        }
    }

    @Test
    fun endpointUrlsAreNotAcceptedAsApiPrefixesForAnyProtocol() {
        for (suffix in listOf(
            "/chat/completions", "/responses", "/messages",
            "/models/example:generateContent", "/models/example:streamGenerateContent",
        )) {
            val invalid = profile().copy(baseUrl = "https://api.example.test/v1$suffix")
            assertTrue(invalid.validationIssues().contains(ConnectionProfileIssue.ENDPOINT_NOT_PREFIX))
            assertFalse(invalid.isConfigured())
        }
    }

    @Test
    fun missingIdentityInvalidRevisionAndUnsupportedUrlsCannotBecomeConfigured() {
        val invalid = listOf(
            profile().copy(profileId = " "), profile().copy(revision = 0),
            profile().copy(baseUrl = ""), profile().copy(baseUrl = "https:///missing-host"),
            profile().copy(baseUrl = "ftp://example.test/v1"),
        )
        invalid.forEach { assertFalse(it.isConfigured()) }
    }

    @Test
    fun identifiersApiVersionsAndUrlsRejectControlsBeforeNormalization() {
        for (control in listOf("\n", "\r", "\t", "\u0000", "\u007f", "\u0085", "\u2028", "\u200b")) {
            val invalid = listOf(
                profile().copy(profileId = "profile${control}a"),
                profile().copy(modelId = "model${control}a"),
                profile().copy(apiVersion = "version${control}a"),
                profile().copy(baseUrl = "${control}https://api.example.test/v1"),
            )
            invalid.forEach { assertFalse("Control characters must not disappear during trimming", it.isConfigured()) }
        }
        assertFalse(profile().copy(apiVersion = "   ").isConfigured())
    }

    @Test
    fun encodedEndpointsAndControlsCannotBypassPrefixValidation() {
        for (path in listOf(
            "/v1/%72esponses///", "/v1/%6dessages/", "/v1/chat/%63ompletions/",
            "/v1/models/model:%67enerateContent/", "/v1/responses/.",
        )) {
            val invalid = profile().copy(baseUrl = "https://api.example.test$path")
            assertTrue(invalid.validationIssues().contains(ConnectionProfileIssue.ENDPOINT_NOT_PREFIX))
        }
        for (path in listOf("/v1/%0a", "/v1/%250a", "/v1%2Fresponses", "/v1%5Cmessages")) {
            assertFalse(profile().copy(baseUrl = "https://api.example.test$path").isConfigured())
        }
        assertEquals(profile().identity(), profile().copy(baseUrl = "https://api.example.test/v1///").identity())
    }

    @Test
    fun diagnosticsOmitUserSuppliedIdentifiersAndEndpointValues() {
        val configured = profile().copy(
            profileId = "private-profile-label", modelId = "private-model-label",
            baseUrl = "https://private-host.example.test/private-prefix",
            apiVersion = "private-api-version",
        )
        for (text in listOf(configured.toString(), configured.identity().toString())) {
            listOf("private-profile-label", "private-model-label", "private-host", "private-prefix", "private-api-version")
                .forEach { assertFalse("Diagnostics must not echo user-entered configuration", text.contains(it)) }
        }
    }
}
