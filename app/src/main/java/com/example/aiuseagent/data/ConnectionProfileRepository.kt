package com.example.aiuseagent.data

import com.example.aiuseagent.data.local.ConnectionProfileDao
import com.example.aiuseagent.data.local.ConnectionProfileEntity
import com.example.aiuseagent.data.provider.PreparedProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import com.example.aiuseagent.data.provider.ProviderRepository
import com.example.aiuseagent.domain.provider.ProviderType
import com.example.aiuseagent.domain.provider.ProviderConfig

/** Non-secret profile persistence; in-memory credential stores never reach this API. */
class ConnectionProfileRepository(private val dao: ConnectionProfileDao) {
    private val mutex=Mutex()
    var requiresReconfiguration: Boolean = false
        private set
    suspend fun restoreInto(providers: ProviderRepository)=mutex.withLock {
        requiresReconfiguration=false
        dao.observe().first().forEach { row ->
            val config=decodeConfig(row)
            if(config==null) {requiresReconfiguration=true;return@forEach}
            // A malformed catalogue entry must not make unrelated conversations unavailable.
            if(runCatching { providers.restoreStoredProfile(row.id,config,row.revision) }.isFailure)
                requiresReconfiguration=true
        }
    }
    private fun decodeConfig(row:ConnectionProfileEntity):ProviderConfig? {
        if(row.profileJson!=null) {
            val profile=ConnectionProfileCodec.decode(row.profileJson) ?: return null
            if(profile.profileId!=row.id || profile.revision!=row.revision || profile.brand.name!=row.brand ||
                profile.protocol.name!=row.protocol || profile.baseUrl!=row.baseUrl || profile.modelId!=row.model) return null
            val type=ConnectionProfileCodec.adapterType(profile) ?: return null
            return ProviderConfig(type,profile.baseUrl,profile.modelId,profile.allowInsecureHttp,connectionProfile=profile)
        }
        if(row.capabilityRevision!="legacy-unverified") return null
        // Explicit v0 adapter mapping. These strings are not the new ProviderBrand names.
        val type=when(row.brand to row.protocol) {
            "DEEPSEEK" to "OPENAI_COMPATIBLE" -> ProviderType.DEEPSEEK
            "OPENAI_COMPATIBLE" to "OPENAI_COMPATIBLE" -> ProviderType.OPENAI_COMPATIBLE
            "ANTHROPIC" to "ANTHROPIC_MESSAGES" -> ProviderType.ANTHROPIC
            "MOCK" to "MOCK" -> ProviderType.MOCK
            else -> return null
        }
        return ProviderConfig(type,row.baseUrl,row.model)
    }
    suspend fun save(prepared: PreparedProvider)=mutex.withLock {
        require(!prepared.configurationContainsCredential)
        val profile=prepared.config.connectionProfile
        val encoded=profile?.let(ConnectionProfileCodec::encode)
        val row=ConnectionProfileEntity(prepared.profileId,profile?.brand?.name ?: prepared.type.name,
            profile?.protocol?.name ?: prepared.type.protocol.name,
            prepared.config.baseUrl,prepared.model,prepared.profileRevision,
            if(profile==null) "legacy-unverified" else "profile-v1-recompute",encoded)
        require(listOfNotNull(row.id,row.baseUrl,row.model,encoded).none(prepared::containsKnownCredential))
        val old=dao.find(row.id)
        if(old==null) dao.insert(row)
        else if(old!=row) {
            require(row.revision>old.revision)
            check(dao.update(row.id,old.revision,row.brand,row.protocol,row.baseUrl,row.model,row.revision,row.capabilityRevision,row.profileJson)==1)
        }
    }
}
