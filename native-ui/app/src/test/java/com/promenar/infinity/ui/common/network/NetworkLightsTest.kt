package com.promenar.infinity.ui.common.network

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.model.ProviderListItem
import com.promenar.infinity.data.remote.protocol.ProtocolType
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.data.network.PublicNetworkStatus
import com.promenar.infinity.data.tailnet.TailnetPhase
import com.promenar.infinity.data.tailnet.TailnetState
import com.promenar.infinity.ui.common.ModelSelectionUiModel
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
            ProviderListItem(id = "gateway", baseUrl = "https://gpu-box.tail3f9c.ts.net/v1", hasApiKey = true),
            ProviderListItem(id = "deepseek", baseUrl = "https://api.deepseek.com/v1", hasApiKey = true),
        )
        val state = networkLightsState(PublicNetworkStatus.CONNECTED, TailnetState(TailnetPhase.DISABLED), providers)
        assertThat(state.tailnetProviderIds).containsExactly("gateway")
        assertThat(state.tailscale).isEqualTo(TailscaleLightStatus.OFF)
        assertThat(state.badgeFor(model("gateway"))).isEqualTo(ModelNetworkBadge.Tailnet(TailscaleLightStatus.OFF))
        assertThat(state.badgeFor(model("deepseek"))).isEqualTo(ModelNetworkBadge.Public(PublicNetworkStatus.CONNECTED))

        val publicOnly = networkLightsState(PublicNetworkStatus.LIMITED, TailnetState(TailnetPhase.DISABLED), providers.drop(1))
        assertThat(publicOnly.tailscale).isNull()
        assertThat(publicOnly.public).isEqualTo(PublicNetworkStatus.LIMITED)
    }

    @Test
    fun `未配置凭据的 tailnet 提供商不可用，不计入内网模型`() {
        val providers = listOf(
            ProviderListItem(id = "draft", baseUrl = "https://gpu-box.tail3f9c.ts.net/v1"),
            ProviderListItem(id = "deepseek", baseUrl = "https://api.deepseek.com/v1", hasApiKey = true),
        )
        val state = networkLightsState(PublicNetworkStatus.CONNECTED, TailnetState(TailnetPhase.DISABLED), providers)
        assertThat(state.tailnetProviderIds).isEmpty()
        assertThat(state.tailscale).isNull()
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

    @Test
    fun `每个经网络访问的模型都带所走网络的灯，本机推理模型不带`() {
        val providers = listOf(
            ProviderListItem(id = "gateway", baseUrl = "https://gpu-box.tail3f9c.ts.net/v1", hasApiKey = true),
            ProviderListItem(id = "deepseek", baseUrl = "https://api.deepseek.com/v1", hasApiKey = true),
            ProviderListItem(id = "phone", baseUrl = "", protocolType = ProtocolType.Local),
        )
        val state = networkLightsState(PublicNetworkStatus.OFFLINE, TailnetState(TailnetPhase.RUNNING), providers)
        assertThat(state.badgeFor(model("gateway"))).isEqualTo(ModelNetworkBadge.Tailnet(TailscaleLightStatus.CONNECTED))
        assertThat(state.badgeFor(model("deepseek"))).isEqualTo(ModelNetworkBadge.Public(PublicNetworkStatus.OFFLINE))
        assertThat(state.badgeFor(model("phone"))).isNull()
        assertThat(state.badgeFor(model(""))).isEqualTo(ModelNetworkBadge.Public(PublicNetworkStatus.OFFLINE))
    }

    @Test
    fun `灯色只分三档：已连接绿、连接中或受限黄、未连接红`() {
        assertThat(PublicNetworkStatus.CONNECTED.tone()).isEqualTo(SignalTone.ON)
        assertThat(PublicNetworkStatus.LIMITED.tone()).isEqualTo(SignalTone.PENDING)
        assertThat(PublicNetworkStatus.OFFLINE.tone()).isEqualTo(SignalTone.DOWN)
        assertThat(TailscaleLightStatus.CONNECTED.tone()).isEqualTo(SignalTone.ON)
        assertThat(TailscaleLightStatus.CONNECTING.tone()).isEqualTo(SignalTone.PENDING)
        listOf(TailscaleLightStatus.NEEDS_LOGIN, TailscaleLightStatus.FAILED, TailscaleLightStatus.OFF).forEach {
            assertThat(it.tone()).isEqualTo(SignalTone.DOWN)
        }
    }
}
