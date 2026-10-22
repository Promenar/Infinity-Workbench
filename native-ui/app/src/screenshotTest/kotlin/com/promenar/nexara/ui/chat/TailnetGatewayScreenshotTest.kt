package com.promenar.nexara.ui.chat

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
import com.promenar.nexara.data.model.Message
import com.promenar.nexara.data.model.MessageRole
import com.promenar.nexara.data.model.catalog.ModelCapability
import com.promenar.nexara.data.model.catalog.ModelWorkload
import com.promenar.nexara.data.model.catalog.SupportState
import com.promenar.nexara.data.network.PublicNetworkStatus
import com.promenar.nexara.data.tailnet.TailnetConnectProgress
import com.promenar.nexara.data.tailnet.TailnetConnectStep
import com.promenar.nexara.data.tailnet.TailnetPeer
import com.promenar.nexara.ui.common.ModelPickerSheetContent
import com.promenar.nexara.ui.common.ModelSelectionUiModel
import com.promenar.nexara.ui.common.network.NetworkLightsRow
import com.promenar.nexara.ui.common.network.NetworkLightsState
import com.promenar.nexara.ui.common.network.TailscaleLightStatus
import com.promenar.nexara.ui.settings.BuiltInTailscaleStatus
import com.promenar.nexara.ui.settings.ProviderAddressHint
import com.promenar.nexara.ui.settings.ProviderApiSource
import com.promenar.nexara.ui.settings.ProviderApiSourceHint
import com.promenar.nexara.ui.settings.ProviderApiSourceSection
import com.promenar.nexara.ui.settings.ProviderApiSourceState
import com.promenar.nexara.ui.settings.ProviderTailnetInfo
import com.promenar.nexara.ui.settings.TailnetPeerChips
import com.promenar.nexara.ui.theme.*

// 经 Tailscale 访问内网模型网关的界面：网络指示灯、发送前的连接等待浮窗、失败后的操作行、提供商的 API 源分组。

@Composable
private fun TailnetFrame(mode: NexaraThemeMode, content: @Composable () -> Unit) {
    NexaraTheme(preferences = NexaraThemePreferences(mode = mode, colorSource = NexaraColorSource.NEXARA)) {
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
fun networkLightsDarkPreview() = TailnetFrame(NexaraThemeMode.DARK) { NetworkLightsStates() }

@PreviewTest
@Preview(name = "网络指示灯 浅中360", widthDp = 360, locale = "zh-rCN")
@Composable
fun networkLightsLightPreview() = TailnetFrame(NexaraThemeMode.LIGHT) { NetworkLightsStates() }

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
fun tailnetModelPickerConnectedPreview() = TailnetFrame(NexaraThemeMode.DARK) {
    TailnetModelPicker(TailscaleLightStatus.CONNECTED)
}

@PreviewTest
@Preview(name = "模型选择 内网失败 浅中360 大字", widthDp = 360, heightDp = 720, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun tailnetModelPickerFailedPreview() = TailnetFrame(NexaraThemeMode.LIGHT) {
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
fun tailnetConnectOverlayDarkPreview() = TailnetFrame(NexaraThemeMode.DARK) { ConnectOverlayStates() }

@PreviewTest
@Preview(name = "连接浮窗 浅中360", widthDp = 360, heightDp = 860, locale = "zh-rCN")
@Composable
fun tailnetConnectOverlayLightPreview() = TailnetFrame(NexaraThemeMode.LIGHT) { ConnectOverlayStates() }

private fun failureGroup(id: String, reason: String) = buildPipelineGroups(
    listOf(
        Message(
            "$id-error", MessageRole.ASSISTANT, "",
            isError = true,
            errorMessage = "@nexara/failure:TAILNET_UNAVAILABLE?tailnetReason=$reason",
            modelId = "gateway::qwen3.8-32b",
            createdAt = 1_760_000_000_000,
        ),
    ),
).single()

// 失败气泡下的操作行：需要登录（去登录 / 换用公网模型）与连不上（重试 / 换用公网模型 / Tailscale 设置）。
@Composable
private fun FailureActionStates() {
    val actions = GenerationFailureActions(onRetry = {}, onSwitchModel = {}, onOpenTailscaleSettings = {})
    Column(Modifier.padding(16.dp)) {
        PipelineBubble(group = failureGroup("login", "NEEDS_LOGIN"), isGenerating = false, streamingContent = "", fontSize = 15, failureActions = actions)
        Spacer(Modifier.height(16.dp))
        PipelineBubble(group = failureGroup("timeout", "TIMEOUT"), isGenerating = false, streamingContent = "", fontSize = 15, failureActions = actions)
    }
}

@PreviewTest
@Preview(name = "内网失败操作 深中411", widthDp = 411, heightDp = 560, locale = "zh-rCN")
@Preview(name = "内网失败操作 浅英360 大字", widthDp = 360, heightDp = 760, locale = "en", fontScale = 1.3f)
@Composable
fun tailnetFailureActionsPreview() = TailnetFrame(NexaraThemeMode.DARK) { FailureActionStates() }

private val GATEWAY_PEERS = listOf(
    TailnetPeer(name = "gpu-box", dnsName = "gpu-box.tail1234.ts.net.", online = true),
    TailnetPeer(name = "nas", dnsName = "nas.tail1234.ts.net.", online = true),
    TailnetPeer(name = "old-laptop", dnsName = "old-laptop.tail1234.ts.net.", online = false),
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
            TailnetPeerChips(GATEWAY_PEERS, baseUrl = "https://gpu-box.tail1234.ts.net/v1", onPeerSelected = {})
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

@PreviewTest
@Preview(name = "API 源 深中411", widthDp = 411, heightDp = 900, locale = "zh-rCN")
@Preview(name = "API 源 深英360 大字", widthDp = 360, heightDp = 1200, locale = "en", fontScale = 1.3f)
@Composable
fun providerApiSourceDarkPreview() = TailnetFrame(NexaraThemeMode.DARK) { ApiSourceStates() }

@PreviewTest
@Preview(name = "API 源 浅中360", widthDp = 360, heightDp = 960, locale = "zh-rCN")
@Composable
fun providerApiSourceLightPreview() = TailnetFrame(NexaraThemeMode.LIGHT) { ApiSourceStates() }
