package com.example.aiuseagent.data

import com.example.aiuseagent.data.provider.ProviderRepository
import com.example.aiuseagent.domain.provider.ProviderConfig
import com.example.aiuseagent.domain.provider.ProviderType
import org.junit.Assert.*
import org.junit.Test

class ConnectionIdentityTest {
    @Test fun sameProtocolProfilesKeepSeparateCredentialsAndInvalidatePreparedSelection() {
        val repository=ProviderRepository()
        val config=ProviderConfig.forType(ProviderType.DEEPSEEK)
        repository.useProfile("profile-a",config)
        repository.putCredential(config.type,"synthetic-credential-A")
        val old=repository.prepare()
        repository.useProfile("profile-b",config.copy(baseUrl="https://fixture.invalid"))
        assertFalse(repository.credentialStatus(config.type).isPresent)
        assertFalse(repository.isCurrent(old))
        repository.putCredential(config.type,"synthetic-credential-B")
        assertNotEquals(old.profileId,repository.prepare().profileId)
        repository.useProfile("profile-a",config)
        assertTrue(repository.credentialStatus(config.type).isPresent)
        assertTrue(repository.containsKnownCredential("synthetic-credential-A"))
        assertTrue(repository.containsKnownCredential("synthetic-credential-B"))
    }

    @Test fun configAndCredentialChangesAdvanceRevisionWithoutPrintingSecrets() {
        val repository=ProviderRepository()
        val config=ProviderConfig.forType(ProviderType.DEEPSEEK)
        repository.useProfile("p",config)
        val first=repository.prepare()
        repository.updateConfig(config.copy(model="different-model"))
        val changed=repository.prepare()
        assertTrue(changed.profileRevision>first.profileRevision)
        repository.putCredential(config.type,"synthetic-private-value")
        assertTrue(repository.prepare().profileRevision>changed.profileRevision)
        assertFalse(repository.prepare().toString().contains("synthetic-private-value"))
        repository.clearCredential(config.type)
        assertFalse(repository.credentialStatus(config.type).isPresent)
    }
}
