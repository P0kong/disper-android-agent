package com.example.aiuseagent.data.provider

import com.example.aiuseagent.domain.provider.*

/** Resolves authenticated local evidence; model discovery alone never grants a capability. */
class CapabilityResolver {
    fun resolve(profile: ConnectionProfile): ModelCapabilities {
        val unknown=ModelCapabilities(profile.identity())
        if(!profile.isConfigured() || profile.allowInsecureHttp) return unknown
        val reference=when {
            profile.brand==ProviderBrand.OPENAI && profile.protocol==Protocol.CHAT_COMPLETIONS &&
                profile.normalizedBaseUrl()=="https://api.openai.com/v1" && profile.apiVersion==null &&
                profile.modelId in setOf("gpt-4.1-mini","gpt-4.1-mini-2025-04-14") ->
                "https://developers.openai.com/api/docs/models/gpt-4.1-mini"
            profile.brand==ProviderBrand.CLAUDE && profile.protocol==Protocol.ANTHROPIC_MESSAGES &&
                profile.normalizedBaseUrl()=="https://api.anthropic.com/v1" && profile.apiVersion=="2023-06-01" &&
                profile.modelId=="claude-sonnet-5-5" -> "https://platform.claude.com/docs/en/models/overview"
            else -> return unknown
        }
        val evidence=listOf(
            CapabilitySource(CapabilitySourceKind.OFFICIAL_DOCUMENTATION,reference,"2026-10-29"),
            CapabilitySource(CapabilitySourceKind.PROTOCOL_FIXTURE,"attachment-image-wire:v1","2026-10-29"))
        return ModelCapabilities(profile.identity(),mapOf(ModelFeature.IMAGES to
            FeatureCapability(CapabilitySupport.SUPPORTED,VerificationState.PASSED,sources=evidence)))
    }
}
