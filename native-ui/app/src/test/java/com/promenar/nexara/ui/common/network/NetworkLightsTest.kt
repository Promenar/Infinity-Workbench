package com.promenar.nexara.ui.common.network

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.data.model.ProviderListItem
import com.promenar.nexara.data.model.catalog.ModelWorkload
import com.promenar.nexara.data.model.catalog.SupportState
import com.promenar.nexara.data.network.PublicNetworkStatus
import com.promenar.nexara.data.tailnet.TailnetPhase
import com.promenar.nexara.data.tailnet.TailnetState
import com.promenar.nexara.ui.common.ModelSelectionUiModel
import org.junit.Test

class NetworkLightsTest {
    @Test
    fun `Tailscale 灯按相位给出状态，未开启时只在有内网模型时显示`() {
        assertThat(tailscaleLightStatus(TailnetPhase.RUNNING, false)).isEqualTo(TailscaleLightStatus.CONNECTED)
        assertThat(tailscaleLightStatus(TailnetPhase.STARTING, false)).isEqualTo(TailscaleLightStatus.CONNECTING)
        assertThat(tailscaleLightStatus(TailnetPhase.STOPPED, false)).isEqualTo(TailscaleLightStatus.CONNECTING)
        assertThat(tailscaleLightStatus(TailnetPhase.NEEDS_LOGIN, false)).isEqualTo(TailscaleLightStatus.NEEDS_LOGIN)
        assertThat(tailscaleLightStatus(TailnetPhase.FAILED, false)).isEqualTo(TailscaleLightStatus.FAILED)
        assertThat(tailscaleLightStatus(TailnetPhase.DISABLED, false)).isNull()
        assertThat(tailscaleLightStatus(TailnetPhase.UNAVAILABLE, false)).isNull()
        assertThat(tailscaleLightStatus(TailnetPhase.DISABLED, true)).isEqualTo(TailscaleLightStatus.OFF)
        assertThat(tailscaleLightStatus(TailnetPhase.UNAVAILABLE, true)).isEqualTo(TailscaleLightStatus.OFF)
    }

    @Test
    fun `失败时点按重试，其它状态前往设置`() {
        assertThat(TailscaleLightStatus.FAILED.action()).isEqualTo(TailscaleLightAction.RETRY)
        TailscaleLightStatus.entries.filter { it != TailscaleLightStatus.FAILED }.forEach {
            assertThat(it.action()).isEqualTo(TailscaleLightAction.OPEN_SETTINGS)
        }
    }

    @Test
    fun `只有接口在 tailnet 内的提供商的模型带小灯`() {
        val providers = listOf(
            ProviderListItem(id = "gateway", baseUrl = "https://gpu-box.tail3f9c.ts.net/v1"),
            ProviderListItem(id = "deepseek", baseUrl = "https://api.deepseek.com/v1"),
        )
        val state = networkLightsState(PublicNetworkStatus.CONNECTED, TailnetState(TailnetPhase.DISABLED), providers)
        assertThat(state.tailnetProviderIds).containsExactly("gateway")
        assertThat(state.tailscale).isEqualTo(TailscaleLightStatus.OFF)
        assertThat(state.badgeFor(model("gateway"))).isEqualTo(TailscaleLightStatus.OFF)
        assertThat(state.badgeFor(model("deepseek"))).isNull()

        val publicOnly = networkLightsState(PublicNetworkStatus.LIMITED, TailnetState(TailnetPhase.DISABLED), providers.drop(1))
        assertThat(publicOnly.tailscale).isNull()
        assertThat(publicOnly.public).isEqualTo(PublicNetworkStatus.LIMITED)
    }

    private fun model(providerId: String) = ModelSelectionUiModel(
        selectionId = "$providerId/m",
        remoteModelId = "m",
        displayName = "M",
        providerName = providerId,
        contextTokens = null,
        workload = ModelWorkload.GENERATIVE_TEXT,
        capabilityStates = emptyMap(),
        chatEndpointCompatible = SupportState.SUPPORTED,
        providerId = providerId,
    )
}
