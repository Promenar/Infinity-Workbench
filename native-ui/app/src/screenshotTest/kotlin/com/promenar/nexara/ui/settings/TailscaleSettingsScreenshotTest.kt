package com.promenar.nexara.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.R
import com.promenar.nexara.data.tailnet.TailnetPeer
import com.promenar.nexara.data.tailnet.TailnetPhase
import com.promenar.nexara.data.tailnet.TailnetSettings
import com.promenar.nexara.data.tailnet.TailnetState
import com.promenar.nexara.data.tailnet.TailnetStatusSummary
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@PreviewTest
@Preview(name = "Tailscale 已连接 浅色", widthDp = 412, heightDp = 2000, locale = "en")
@Preview(name = "Tailscale 已连接 浅色 大字号130", widthDp = 412, heightDp = 2600, locale = "en", fontScale = 1.3f)
@Composable
fun tailscaleRunningLightPreview() = TailscaleVisualPreview(dark = false, phase = TailnetPhase.RUNNING)

@PreviewTest
@Preview(name = "Tailscale 已连接 深色", widthDp = 412, heightDp = 2000, locale = "zh-rCN")
@Preview(name = "Tailscale 已连接 深色 大字号130", widthDp = 412, heightDp = 2600, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun tailscaleRunningDarkPreview() = TailscaleVisualPreview(dark = true, phase = TailnetPhase.RUNNING)

@PreviewTest
@Preview(name = "Tailscale 待登录 浅色", widthDp = 412, heightDp = 1600, locale = "en")
@Preview(name = "Tailscale 待登录 浅色 大字号130", widthDp = 412, heightDp = 2100, locale = "en", fontScale = 1.3f)
@Composable
fun tailscaleNeedsLoginLightPreview() = TailscaleVisualPreview(dark = false, phase = TailnetPhase.NEEDS_LOGIN)

@PreviewTest
@Preview(name = "Tailscale 待登录 深色", widthDp = 412, heightDp = 1600, locale = "zh-rCN")
@Preview(name = "Tailscale 待登录 深色 大字号130", widthDp = 412, heightDp = 2100, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun tailscaleNeedsLoginDarkPreview() = TailscaleVisualPreview(dark = true, phase = TailnetPhase.NEEDS_LOGIN)

@PreviewTest
@Preview(name = "Tailscale 未启用 浅色", widthDp = 412, heightDp = 1400, locale = "en")
@Composable
fun tailscaleDisabledLightPreview() = TailscaleVisualPreview(dark = false, phase = TailnetPhase.DISABLED)

@PreviewTest
@Preview(name = "Tailscale 未启用 深色", widthDp = 412, heightDp = 1400, locale = "zh-rCN")
@Composable
fun tailscaleDisabledDarkPreview() = TailscaleVisualPreview(dark = true, phase = TailnetPhase.DISABLED)

@Composable
private fun TailscaleVisualPreview(dark: Boolean, phase: TailnetPhase) {
    NexaraTheme(
        preferences = NexaraThemePreferences(
            mode = if (dark) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT,
            colorSource = NexaraColorSource.NEXARA,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            NexaraSettingsPageLayout(title = stringResource(R.string.tailscale_title), onBack = {}) { padding ->
                TailscaleSettingsContent(state = previewState(phase), contentPadding = padding)
            }
        }
    }
}

private fun previewState(phase: TailnetPhase): TailscaleSettingsUiState {
    val running = phase == TailnetPhase.RUNNING
    val summary = when (phase) {
        TailnetPhase.RUNNING -> TailnetStatusSummary(
            backendState = TailnetStatusSummary.STATE_RUNNING,
            selfName = "nexara-pixel",
            selfDnsName = "nexara-pixel.example-tailnet.ts.net",
            selfIps = listOf("100.101.102.103", "fd7a:115c:a1e0::1"),
            online = true,
            magicDnsSuffix = "example-tailnet.ts.net",
            tailnetName = "example-tailnet.ts.net",
        )
        TailnetPhase.NEEDS_LOGIN -> TailnetStatusSummary(backendState = TailnetStatusSummary.STATE_NEEDS_LOGIN)
        else -> null
    }
    return TailscaleSettingsUiState(
        available = true,
        settings = TailnetSettings(
            enabled = phase != TailnetPhase.DISABLED,
            ephemeral = !running,
            allowedPeers = if (running) listOf("nas", "100.64.0.8") else emptyList(),
            hasAuthKey = running,
        ),
        tailnet = TailnetState(
            phase = phase,
            summary = summary,
            peers = if (running) {
                listOf(
                    TailnetPeer("nas", "nas.example-tailnet.ts.net", listOf("100.64.0.7"), "linux", online = true),
                    TailnetPeer(
                        "build-server-with-a-long-name",
                        "build-server-with-a-long-name.example-tailnet.ts.net",
                        listOf("100.64.0.8"),
                        "linux",
                        online = false,
                    ),
                    TailnetPeer("laptop", "laptop.example-tailnet.ts.net", listOf("100.64.0.9"), "macOS", online = true, allowed = false),
                )
            } else {
                emptyList()
            },
        ),
    )
}
