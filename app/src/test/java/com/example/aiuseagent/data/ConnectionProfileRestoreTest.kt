package com.example.aiuseagent.data

import com.example.aiuseagent.data.local.*
import com.example.aiuseagent.data.provider.HttpProviders
import com.example.aiuseagent.domain.provider.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ConnectionProfileRestoreTest {
    @Test fun durableCatalogueRestoresCompleteIdentityWithoutSpuriousRevisionChange()=runTest {
        val dao=Profiles();val persisted=ConnectionProfileRepository(dao)
        val first=HttpProviders.repository()
        first.useConnectionProfile(ConnectionProfile("custom:claude",9,ProviderBrand.CLAUDE,
            Protocol.ANTHROPIC_MESSAGES,"https://api.anthropic.com/v1","claude-sonnet-5-5",
            "2023-06-01",configurationSaved=true))
        val expected=first.prepare()
        persisted.save(expected)
        val reopened=HttpProviders.repository()
        reopened.restoreProfile(expected.profileId,expected.config.copy(imageCapability=null),expected.profileRevision)
        persisted.restoreInto(reopened)
        assertEquals(expected.config.connectionProfile,reopened.prepare().config.connectionProfile)
        assertEquals(expected.profileRevision,reopened.prepare().profileRevision)
        assertEquals(CapabilitySupport.SUPPORTED,reopened.prepare().config.imageCapability!!.documentation)
        assertFalse(reopened.credentialStatus(ProviderType.ANTHROPIC).isPresent)
    }
    @Test fun unknownLegacyProtocolIsRetainedWithoutInterruptingStartup()=runTest {
        val dao=Profiles();val row=ConnectionProfileEntity("custom:unknown","DEEPSEEK","future-protocol",
            "https://example.invalid/v1","unknown",4,"legacy-unverified")
        dao.insert(row)
        val providers=HttpProviders.repository()
        ConnectionProfileRepository(dao).restoreInto(providers)
        assertEquals(row,dao.find(row.id))
        assertEquals(ProviderType.MOCK,providers.activeType())
    }
    private class Profiles: ConnectionProfileDao {
        val rows=MutableStateFlow<List<ConnectionProfileEntity>>(emptyList())
        override fun observe()=rows
        override suspend fun find(id: String)=rows.value.find { it.id==id }
        override suspend fun insert(profile: ConnectionProfileEntity) { check(find(profile.id)==null);rows.value+=profile }
        override suspend fun update(id: String,expectedRevision: Long,brand: String,protocol: String,baseUrl: String,model: String,revision: Long,capabilityRevision: String,profileJson:String?): Int {
            if(find(id)?.revision!=expectedRevision) return 0
            rows.value=rows.value.map { if(it.id==id) ConnectionProfileEntity(id,brand,protocol,baseUrl,model,revision,capabilityRevision,profileJson) else it }
            return 1
        }
    }
    @Test fun coldStartRestoresInactiveProfileRevisionWithoutChangingTheSelectedConnection()=runTest {
        val dao=Profiles();val persisted=ConnectionProfileRepository(dao);val first=HttpProviders.repository()
        repeat(4) { first.selectActive(ProviderType.DEEPSEEK);first.selectActive(ProviderType.MOCK) }
        persisted.save(first.prepare());val previous=first.prepare().profileRevision
        val reopened=HttpProviders.repository()
        reopened.restoreProfile("builtin:DEEPSEEK",ProviderConfig.forType(ProviderType.DEEPSEEK),8)
        persisted.restoreInto(reopened)
        assertEquals(ProviderType.DEEPSEEK,reopened.activeType())
        reopened.selectActive(ProviderType.MOCK)
        assertTrue(reopened.prepare().profileRevision>previous)
        persisted.save(reopened.prepare())
        assertEquals(reopened.prepare().profileRevision,dao.find("builtin:MOCK")!!.revision)
        assertFalse(reopened.credentialStatus(ProviderType.DEEPSEEK).isPresent)
    }
    @Test fun newerSavedSettingsKeepTheirConfigAndAdvancePastTheDatabaseRevision()=runTest {
        val dao=Profiles();val persisted=ConnectionProfileRepository(dao)
        dao.insert(ConnectionProfileEntity("custom:p","OPENAI_COMPATIBLE","OPENAI_COMPATIBLE","https://old.example/v1","old",20,"legacy-unverified"))
        val reopened=HttpProviders.repository()
        reopened.restoreProfile("custom:p",ProviderConfig(ProviderType.OPENAI_COMPATIBLE,"https://new.example/v1","new"),2)
        persisted.restoreInto(reopened)
        assertEquals("new",reopened.prepare().model)
        assertTrue(reopened.prepare().profileRevision>20)
        persisted.save(reopened.prepare())
        assertEquals("new",dao.find("custom:p")!!.model)
    }
}
