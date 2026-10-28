package com.promenar.infinity.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.android.tools.screenshot.PreviewTest
import com.promenar.infinity.data.model.Message
import com.promenar.infinity.data.model.MessageRole
import com.promenar.infinity.data.model.catalog.ModelCapability
import com.promenar.infinity.data.model.catalog.ModelWorkload
import com.promenar.infinity.data.model.catalog.SupportState
import com.promenar.infinity.data.network.PublicNetworkStatus
import com.promenar.infinity.data.tailnet.TailnetConnectProgress
import com.promenar.infinity.data.tailnet.TailnetConnectStep
import com.promenar.infinity.data.tailnet.TailnetPeer
import com.promenar.infinity.ui.common.ModelPickerSheetContent
import com.promenar.infinity.ui.common.ModelSelectionUiModel
import com.promenar.infinity.ui.common.network.NetworkLightsRow
import com.promenar.infinity.ui.common.network.NetworkLightsState
import com.promenar.infinity.ui.common.network.TailscaleLightStatus
import com.promenar.infinity.ui.settings.BuiltInTailscaleStatus
import com.promenar.infinity.ui.settings.ProviderAddressHint
import com.promenar.infinity.ui.settings.ProviderApiSource
import com.promenar.infinity.ui.settings.ProviderApiSourceHint
import com.promenar.infinity.ui.settings.ProviderApiSourceSection
import com.promenar.infinity.ui.settings.ProviderApiSourceState
import com.promenar.infinity.ui.settings.ProviderTailnetInfo
import com.promenar.infinity.ui.settings.TailnetPeerOptions
import com.promenar.infinity.ui.settings.TailnetPeerPicker
import com.promenar.infinity.ui.settings.selectableTailnetPeers
import com.promenar.infinity.ui.theme.*

// 经 Tailscale 访问内网模型网关的界面：网络指示灯、发送前的连接等待浮窗、失败后的操作行、提供商的 API 源分组。

@Composable
private fun TailnetFrame(mode: InfinityThemeMode, content: @Composable () -> Unit) {
    InfinityTheme(preferences = InfinityThemePreferences(mode = mode, colorSource = InfinityColorSource.INFINITY)) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

// 网络行的各状态：公网正常/受限/离线，Tailscale 已连接、连接中、需登录、失败、未开启。
@Composable
private fun NetworkLightsStates() {
    val rows = listOf(
        PublicNetworkStatus.CONNECTED to TailscaleLightStatus.CONNECTED,
        PublicNetworkStatus.CONNECTED to TailscaleLightStatus.CONNECTING,
        PublicNetworkStatus.LIMITED to TailscaleLightStatus.NEEDS_LOGIN,
        PublicNetworkStatus.OFFLINE to TailscaleLightStatus.FAILED,
        PublicNetworkStatus.CONNECTED to TailscaleLightStatus.OFF,
        PublicNetworkStatus.CONNECTED to null,
    )
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        rows.forEach { (public, tailscale) ->
            NetworkLightsRow(
                state = NetworkLightsState(public, tailscale, emptySet()),
                onOpenTailscaleSettings = {},
                onRetryTailscale = {},
            )
        }
    }
}

@PreviewTest
@Preview(name = "网络指示灯 深中411", widthDp = 411, locale = "zh-rCN")
@Preview(name = "网络指示灯 深英360 大字", widthDp = 360, locale = "en", fontScale = 1.3f)
@Composable
fun networkLightsDarkPreview() = TailnetFrame(InfinityThemeMode.DARK) { NetworkLightsStates() }

@PreviewTest
@Preview(name = "网络指示灯 浅中360", widthDp = 360, locale = "zh-rCN")
@Composable
fun networkLightsLightPreview() = TailnetFrame(InfinityThemeMode.LIGHT) { NetworkLightsStates() }

private fun pickerModel(id: String, name: String, provider: String, providerId: String) = ModelSelectionUiModel(
    selectionId = "$providerId::$id",
    remoteModelId = id,
    displayName = name,
    providerName = provider,
    contextTokens = 131_072,
    workload = ModelWorkload.GENERATIVE_TEXT,
    capabilityStates = mapOf(ModelCapability.REASONING to SupportState.SUPPORTED),
    chatEndpointCompatible = SupportState.SUPPORTED,
    providerId = providerId,
)

private val PICKER_MODELS = listOf(
    pickerModel("qwen3.8-32b", "Qwen3.8 32B", "内网网关", "gateway"),
    pickerModel("gemma-4-27b", "Gemma 4 27B", "内网网关", "gateway"),
    pickerModel("deepseek-v4-flash", "DeepSeek V4 Flash", "DeepSeek", "deepseek"),
    pickerModel("deepseek-v4-pro", "DeepSeek V4 Pro", "DeepSeek", "deepseek"),
)

@Composable
private fun TailnetModelPicker(tailscale: TailscaleLightStatus, public: PublicNetworkStatus = PublicNetworkStatus.CONNECTED) {
    ModelPickerSheetContent(
        title = "选择模型",
        searchQuery = "",
        onSearchQueryChange = {},
        models = PICKER_MODELS,
        currentModelId = PICKER_MODELS.first().selectionId,
        onSelect = { _, _ -> },
        modifier = Modifier.fillMaxSize().padding(top = 16.dp),
        networkLights = NetworkLightsState(public, tailscale, setOf("gateway")),
        onOpenTailscaleSettings = {},
        onRetryTailscale = {},
    )
}

@PreviewTest
@Preview(name = "模型选择 内网已连接 深中411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Composable
fun tailnetModelPickerConnectedPreview() = TailnetFrame(InfinityThemeMode.DARK) {
    TailnetModelPicker(TailscaleLightStatus.CONNECTED)
}

@PreviewTest
@Preview(name = "模型选择 内网失败 浅中360 大字", widthDp = 360, heightDp = 720, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun tailnetModelPickerFailedPreview() = TailnetFrame(InfinityThemeMode.LIGHT) {
    TailnetModelPicker(TailscaleLightStatus.FAILED, PublicNetworkStatus.LIMITED)
}

private val WAIT_PROGRESS_STARTING = TailnetConnectProgress("gpu-box.tail1234.ts.net", TailnetConnectStep.STARTING, 30_000)
private val WAIT_PROGRESS_JOINING = TailnetConnectProgress("gpu-box.tail1234.ts.net", TailnetConnectStep.JOINING, 30_000)

// 等待卡片：启动中（刚开始）与接入中（已等 12 秒）两个阶段，以及连上后的确认行。
@Composable
private fun ConnectOverlayStates() {
    Column(Modifier.padding(16.dp)) {
        TailnetWaitingCard(progress = WAIT_PROGRESS_STARTING, startedAt = 0L, elapsedClock = { 1_000L }, onCancel = {})
        Spacer(Modifier.height(16.dp))
        TailnetWaitingCard(progress = WAIT_PROGRESS_JOINING, startedAt = 0L, elapsedClock = { 12_000L }, onCancel = {})
        Spacer(Modifier.height(16.dp))
        TailnetConnectedRow()
    }
}

@PreviewTest
@Preview(name = "连接浮窗 深中411", widthDp = 411, heightDp = 860, locale = "zh-rCN")
@Preview(name = "连接浮窗 深英360 大字", widthDp = 360, heightDp = 1100, locale = "en", fontScale = 1.3f)
@Composable
fun tailnetConnectOverlayDarkPreview() = TailnetFrame(InfinityThemeMode.DARK) { ConnectOverlayStates() }

@PreviewTest
@Preview(name = "连接浮窗 浅中360", widthDp = 360, heightDp = 860, locale = "zh-rCN")
@Composable
fun tailnetConnectOverlayLightPreview() = TailnetFrame(InfinityThemeMode.LIGHT) { ConnectOverlayStates() }

private fun failureGroup(id: String, reason: String) = buildPipelineGroups(
    listOf(
        Message(
            "$id-error", MessageRole.ASSISTANT, "",
            isError = true,
            errorMessage = "@infinity/failure:TAILNET_UNAVAILABLE?tailnetReason=$reason",
            modelId = "gateway::qwen3.8-32b",
            createdAt = 1_760_000_000_000,
        ),
    ),
).single()

// 失败气泡下的操作行：未开启（去开启 / 重试 / 换用公网模型）、需要登录（去登录 / 重试 / 换用公网模型）与连不上（重试 / 换用公网模型 / Tailscale 设置）。
@Composable
private fun FailureActionStates() {
    val actions = GenerationFailureActions(onRetry = {}, onSwitchModel = {}, onOpenTailscaleSettings = {})
    Column(Modifier.padding(16.dp)) {
        PipelineBubble(group = failureGroup("disabled", "DISABLED"), isGenerating = false, streamingContent = "", fontSize = 15, failureActions = actions)
        Spacer(Modifier.height(16.dp))
        PipelineBubble(group = failureGroup("login", "NEEDS_LOGIN"), isGenerating = false, streamingContent = "", fontSize = 15, failureActions = actions)
        Spacer(Modifier.height(16.dp))
        PipelineBubble(group = failureGroup("timeout", "TIMEOUT"), isGenerating = false, streamingContent = "", fontSize = 15, failureActions = actions)
    }
}

@PreviewTest
@Preview(name = "内网失败操作 深中411", widthDp = 411, heightDp = 640, locale = "zh-rCN")
@Preview(name = "内网失败操作 浅英360 大字", widthDp = 360, heightDp = 1000, locale = "en", fontScale = 1.3f)
@Composable
fun tailnetFailureActionsPreview() = TailnetFrame(InfinityThemeMode.DARK) { FailureActionStates() }

private val GATEWAY_PEERS = listOf(
    TailnetPeer(name = "gpu-box", dnsName = "gpu-box.tail1234.ts.net.", os = "linux", online = true),
    TailnetPeer(name = "nas", dnsName = "nas.tail1234.ts.net.", os = "linux", online = true),
    TailnetPeer(name = "old-laptop", dnsName = "old-laptop.tail1234.ts.net.", os = "macOS", online = false),
)

// 提供商表单的 API 源分组：选中 Tailscale 内网（已连接 / 需登录），地址已识别、与来源不一致两种提示和设备候选。
@Composable
private fun ApiSourceStates() {
    val info = ProviderTailnetInfo(selfName = "oppo-find", tailnetName = "promenar.github", peers = GATEWAY_PEERS)
    Column(Modifier.padding(vertical = 8.dp)) {
        ProviderApiSourceSection(
            source = ProviderApiSourceState(ProviderApiSource.TAILNET, ProviderApiSource.TAILNET, ProviderApiSourceHint.RECOGNIZED_TAILNET, BuiltInTailscaleStatus.CONNECTED),
            tailnet = info,
            onSourceSelected = {},
            onOpenTailscaleSettings = {},
        )
        ProviderAddressHint(ProviderApiSourceHint.RECOGNIZED_TAILNET, onSourceSelected = {})
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = 16.dp)) {
            TailnetPeerPicker(GATEWAY_PEERS, baseUrl = "https://gpu-box.tail1234.ts.net:3010/v1", onPeerSelected = {})
        }
        Spacer(Modifier.height(16.dp))
        ProviderApiSourceSection(
            source = ProviderApiSourceState(ProviderApiSource.TAILNET, ProviderApiSource.PUBLIC, ProviderApiSourceHint.ADDRESS_IS_PUBLIC, BuiltInTailscaleStatus.NEEDS_LOGIN),
            tailnet = ProviderTailnetInfo(),
            onSourceSelected = {},
            onOpenTailscaleSettings = {},
        )
        ProviderAddressHint(ProviderApiSourceHint.ADDRESS_IS_PUBLIC, onSourceSelected = {})
    }
}

// 设备面板的内容：在线设备在前，选中项打勾，离线设备注明。
@Composable
private fun PeerOptionsPanel(mode: InfinityThemeMode) = TailnetFrame(mode) {
    val peers = selectableTailnetPeers(GATEWAY_PEERS)
    TailnetPeerOptions(peers = peers, selected = peers.first(), onSelect = {})
}

@PreviewTest
@Preview(name = "设备面板 深中411", widthDp = 411, locale = "zh-rCN")
@Composable
fun tailnetPeerOptionsDarkPreview() = PeerOptionsPanel(InfinityThemeMode.DARK)

@PreviewTest
@Preview(name = "设备面板 浅英360 大字", widthDp = 360, locale = "en", fontScale = 1.3f)
@Composable
fun tailnetPeerOptionsLightPreview() = PeerOptionsPanel(InfinityThemeMode.LIGHT)

@PreviewTest
@Preview(name = "API 源 深中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Preview(name = "API 源 深英360 大字", widthDp = 360, heightDp = 1200, locale = "en", fontScale = 1.3f)
@Composable
fun providerApiSourceDarkPreview() = TailnetFrame(InfinityThemeMode.DARK) { ApiSourceStates() }

@PreviewTest
@Preview(name = "API 源 浅中360", widthDp = 360, heightDp = 960, locale = "zh-rCN")
@Composable
fun providerApiSourceLightPreview() = TailnetFrame(InfinityThemeMode.LIGHT) { ApiSourceStates() }
