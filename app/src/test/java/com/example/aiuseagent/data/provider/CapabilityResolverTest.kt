package com.example.aiuseagent.data.provider

import com.example.aiuseagent.domain.provider.*
import org.junit.Assert.*
import org.junit.Test

class CapabilityResolverTest {
    private val resolver=CapabilityResolver()
    private fun openAi()=ConnectionProfile("official",7,ProviderBrand.OPENAI,Protocol.CHAT_COMPLETIONS,
        "https://api.openai.com/v1","gpt-4.1-mini-2025-04-14",configurationSaved=true)
    @Test fun exactOfficialImageProfilesHaveDocumentationAndProtocolButNoLiveClaim() {
        val profiles=listOf(openAi(),ConnectionProfile("anthropic",2,ProviderBrand.CLAUDE,Protocol.ANTHROPIC_MESSAGES,
            "https://api.anthropic.com/v1","claude-sonnet-5-5","2023-06-01",configurationSaved=true))
        profiles.forEach {p ->
            val c=resolver.resolve(p);assertEquals(p.identity(),c.identity)
            assertEquals(CapabilitySupport.SUPPORTED,c.feature(ModelFeature.IMAGES).documentation)
            assertEquals(VerificationState.PASSED,c.feature(ModelFeature.IMAGES).protocolTest)
            assertEquals(VerificationState.NOT_TESTED,c.feature(ModelFeature.IMAGES).liveService)
            assertFalse(c.supports(ModelFeature.DOCUMENTS))
        }
    }
    @Test fun unknownOrCustomIdentitiesCannotBorrowOfficialImageEvidence() {
        val p=openAi()
        listOf(p.copy(brand=ProviderBrand.CUSTOM),p.copy(baseUrl="https://proxy.invalid/v1"),
            p.copy(baseUrl="https://api.openai.com.evil.invalid/v1"),p.copy(modelId="unverified-model"),
            p.copy(protocol=Protocol.RESPONSES),p.copy(apiVersion="unverified"),p.copy(allowInsecureHttp=true),
            p.copy(baseUrl="http://api.openai.com/v1",allowInsecureHttp=true),p.copy(revision=0)).forEach {
            assertEquals(CapabilitySupport.UNKNOWN,resolver.resolve(it).feature(ModelFeature.IMAGES).documentation)
        }
    }
    @Test fun everyIdentityFieldInvalidatesPreviouslyResolvedEvidence() {
        val p=openAi();val old=resolver.resolve(p)
        assertEquals(CapabilitySupport.SUPPORTED,old.feature(ModelFeature.IMAGES).documentation)
        listOf(p.copy(profileId="another"),p.copy(revision=8),p.copy(brand=ProviderBrand.CUSTOM),
            p.copy(protocol=Protocol.RESPONSES),p.copy(baseUrl="https://else.invalid/v1"),
            p.copy(modelId="other"),p.copy(apiVersion="other"),p.copy(allowInsecureHttp=true)).forEach {
            assertEquals(CapabilitySupport.UNKNOWN,old.forProfile(it).feature(ModelFeature.IMAGES).documentation)
        }
    }
}
