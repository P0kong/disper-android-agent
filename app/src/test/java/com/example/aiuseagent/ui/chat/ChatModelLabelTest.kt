package com.example.aiuseagent.ui.chat

import androidx.lifecycle.viewModelScope
import com.example.aiuseagent.data.*
import com.example.aiuseagent.data.provider.HttpProviders
import com.example.aiuseagent.data.provider.http.*
import com.example.aiuseagent.domain.provider.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatModelLabelTest {
    private val dispatcher=StandardTestDispatcher()
    @Before fun before(){Dispatchers.setMain(dispatcher)}
    @After fun after(){Dispatchers.resetMain()}
    private var requests=0
    private val providers=HttpProviders.repository(transport=object:HttpTransport {
        override suspend fun open(request:ProviderHttpRequest,timeouts:HttpTimeouts):StreamingHttpResponse {requests++;error("No HTTP for labels")}
    })
    private fun vm():ChatViewModel {
        val store=FakeDaoStore()
        return ChatViewModel(SessionRepository(store.sessionDao,store.messageDao),providers=providers)
    }
    @Test fun configuredBrandIsDisplayedIndependentlyOfItsAdapter()=runTest(dispatcher) {
        val vm=vm()
        try {
            for((brand,label) in listOf(ProviderBrand.OPENAI to "OpenAI",ProviderBrand.CLAUDE to "Claude",ProviderBrand.CUSTOM to "自定义服务",ProviderBrand.DEEPSEEK to "DeepSeek")) {
                val protocol=if(brand==ProviderBrand.CLAUDE) Protocol.ANTHROPIC_MESSAGES else Protocol.CHAT_COMPLETIONS
                providers.useConnectionProfile(ConnectionProfile("synthetic:${brand.name}",1,brand,protocol,"https://example.invalid/v1","synthetic-model",
                    apiVersion=if(brand==ProviderBrand.CLAUDE) "2023-06-01" else null,configurationSaved=true))
                providers.putCredential(providers.activeType(),"synthetic-label-fixture-credential")
                vm.refreshModelLabel();assertEquals("$label · synthetic-model",vm.uiState.value.modelLabel)
            }
            assertEquals(0,requests)
        } finally {vm.viewModelScope.cancel()}
    }
    @Test fun absentProfileUsesGenericAdapterLabelWithoutGuessingDeepSeek()=runTest(dispatcher) {
        val vm=vm()
        try {
            providers.updateConfig(ProviderConfig(ProviderType.OPENAI_COMPATIBLE,"https://example.invalid/v1","synthetic-model"))
            providers.selectActive(ProviderType.OPENAI_COMPATIBLE);providers.putCredential(ProviderType.OPENAI_COMPATIBLE,"synthetic-label-fixture-credential")
            vm.refreshModelLabel()
            assertEquals("通用 OpenAI 兼容服务 · synthetic-model",vm.uiState.value.modelLabel);assertEquals(0,requests)
        } finally {vm.viewModelScope.cancel()}
    }
    @Test fun mockStillClearlySaysItIsOffline()=runTest(dispatcher) {
        val vm=vm()
        try {vm.refreshModelLabel();assertEquals("本地 Mock · 不联网",vm.uiState.value.modelLabel);assertEquals(0,requests)}
        finally {vm.viewModelScope.cancel()}
    }
}
