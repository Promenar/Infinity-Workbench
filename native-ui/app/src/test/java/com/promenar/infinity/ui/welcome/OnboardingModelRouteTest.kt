package com.promenar.infinity.ui.welcome

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.manager.ProviderManager
import com.promenar.infinity.data.model.*
import com.promenar.infinity.data.remote.*
import com.promenar.infinity.data.remote.protocol.ProtocolType
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Test

class OnboardingModelRouteTest {
    private val remote = "org/model::v:1/preview"
    private val model = ModelInfo(id = stableModelId("extra", remote), remoteModelId = remote, providerId = "extra", name = "Image", description = "", enabled = false)
    private val models = MutableStateFlow(listOf(model))
    private val providers = MutableStateFlow(listOf(ProviderListItem(id = "extra", name = "Extra", protocolType = ProtocolType.OpenAI_ChatCompletions)))
    private val manager = mockk<ProviderManager> {
        every { providerModels } returns models
        every { this@mockk.providers } returns this@OnboardingModelRouteTest.providers
        every { getProviderConfig("extra") } returns ProviderConfig(protocolType = ProtocolType.OpenAI_ChatCompletions, baseUrl = "https://extra.test/v1", apiKey = "fake-extra-key")
        every { isPersistedProtocolUnsupported("extra") } returns false
    }

    @Test fun `明确候选允许尚未启用但请求三元组来自所属提供商`() {
        val route = (resolveOnboardingModelRoute(manager, "extra", model.id) as ProviderResolution.Success).value
        assertThat(route.remoteModelId).isEqualTo(remote)
        assertThat(route.config.baseUrl).isEqualTo("https://extra.test/v1")
        assertThat(route.config.apiKey).isEqualTo("fake-extra-key")
        assertThat(models.value.single().enabled).isFalse()
    }

    @Test fun `引导候选不允许串提供商且已删除候选不能请求`() {
        assertThat((resolveOnboardingModelRoute(manager, "default", model.id) as ProviderResolution.Failure).reason).isEqualTo(ProviderResolutionError.MODEL_PROVIDER_MISMATCH)
        models.value = emptyList()
        assertThat((resolveOnboardingModelRoute(manager, "extra", model.id) as ProviderResolution.Failure).reason).isEqualTo(ProviderResolutionError.MODEL_NOT_FOUND)
    }

    @Test fun `引导候选不豁免提供商禁用或凭据缺失`() {
        providers.value = providers.value.map { it.copy(enabled = false) }
        assertThat((resolveOnboardingModelRoute(manager, "extra", model.id) as ProviderResolution.Failure).reason).isEqualTo(ProviderResolutionError.PROVIDER_DISABLED)
        providers.value = providers.value.map { it.copy(enabled = true) }
        every { manager.getProviderConfig("extra") } returns ProviderConfig(protocolType = ProtocolType.OpenAI_ChatCompletions, baseUrl = "https://extra.test/v1")
        assertThat((resolveOnboardingModelRoute(manager, "extra", model.id) as ProviderResolution.Failure).reason).isEqualTo(ProviderResolutionError.API_KEY_MISSING)
    }
}
