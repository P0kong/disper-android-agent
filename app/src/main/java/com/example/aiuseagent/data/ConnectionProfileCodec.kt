package com.example.aiuseagent.data

import com.example.aiuseagent.domain.provider.*
import kotlinx.serialization.json.*

/** Versioned non-secret identity shared by preferences and durable profile rows. */
object ConnectionProfileCodec {
    fun encode(profile:ConnectionProfile):String {
        require(profile.isConfigured()) {"Invalid connection profile"}
        require(profile.profileId.matches(Regex("[A-Za-z0-9:_-]{1,100}")))
        val raw=buildJsonObject {
            put("version",1);put("id",profile.profileId);put("revision",profile.revision)
            put("brand",profile.brand.name);put("protocol",profile.protocol.name)
            put("baseUrl",profile.baseUrl);put("model",profile.modelId)
            put("apiVersion",profile.apiVersion?.let(::JsonPrimitive) ?: JsonNull)
            put("allowInsecureHttp",profile.allowInsecureHttp)
        }.toString()
        require(raw.length<=8192) {"Connection profile too large"}
        return raw
    }
    fun decode(raw:String):ConnectionProfile? = runCatching {
        require(raw.length in 1..8192)
        val obj=Json.parseToJsonElement(raw).jsonObject
        require(obj.keys==setOf("version","id","revision","brand","protocol","baseUrl","model","apiVersion","allowInsecureHttp"))
        fun text(key:String)=obj.getValue(key).jsonPrimitive.let {require(it.isString);it.content}
        require(obj.getValue("version").jsonPrimitive.let {!it.isString && it.int==1})
        val revision=obj.getValue("revision").jsonPrimitive.also {require(!it.isString)}.long
        val insecure=obj.getValue("allowInsecureHttp").jsonPrimitive.also {require(!it.isString)}.boolean
        val profile=ConnectionProfile(text("id"),revision,ProviderBrand.valueOf(text("brand")),
            Protocol.valueOf(text("protocol")),text("baseUrl"),text("model"),
            if(obj.getValue("apiVersion")==JsonNull) null else text("apiVersion"),insecure,true)
        encode(profile) // The same limits apply in both directions. No inferred fields.
        profile
    }.getOrNull()

    /** Existing adapters are selected by protocol; branding never grants a capability. */
    fun adapterType(profile:ConnectionProfile):ProviderType? = when(profile.protocol) {
        Protocol.CHAT_COMPLETIONS -> if(profile.brand==ProviderBrand.DEEPSEEK) ProviderType.DEEPSEEK else ProviderType.OPENAI_COMPATIBLE
        Protocol.ANTHROPIC_MESSAGES -> ProviderType.ANTHROPIC
        else -> null
    }
}
