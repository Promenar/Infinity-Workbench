package com.promenar.nexara.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.foundation.layout.padding
import com.android.tools.screenshot.PreviewTest
import com.promenar.nexara.R
import com.promenar.nexara.domain.network.AgentNetworkSnapshot
import com.promenar.nexara.domain.network.AgentNetworkTool
import com.promenar.nexara.domain.network.NetworkAccessCell
import com.promenar.nexara.domain.network.NetworkAccessRow
import com.promenar.nexara.domain.network.NetworkAccessState
import com.promenar.nexara.domain.network.NetworkTargetClass
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.theme.NexaraColorSource
import com.promenar.nexara.ui.theme.NexaraTheme
import com.promenar.nexara.ui.theme.NexaraThemeMode
import com.promenar.nexara.ui.theme.NexaraThemePreferences

@PreviewTest
@Preview(name = "网络访问 白名单已设置 浅色", widthDp = 412, heightDp = 2200, locale = "en")
@Preview(name = "网络访问 白名单已设置 浅色 大字号130", widthDp = 360, heightDp = 3000, locale = "en", fontScale = 1.3f)
@Composable
fun networkAccessConfiguredLightPreview() = NetworkAccessPreview(dark = false, configured = true)

@PreviewTest
@Preview(name = "网络访问 未设置 深色", widthDp = 412, heightDp = 2200, locale = "zh-rCN")
@Preview(name = "网络访问 未设置 深色 大字号130", widthDp = 360, heightDp = 3000, locale = "zh-rCN", fontScale = 1.3f)
@Composable
fun networkAccessEmptyDarkPreview() = NetworkAccessPreview(dark = true, configured = false)

@Composable
private fun NetworkAccessPreview(dark: Boolean, configured: Boolean) {
    NexaraTheme(
        preferences = NexaraThemePreferences(
            mode = if (dark) NexaraThemeMode.DARK else NexaraThemeMode.LIGHT,
            colorSource = NexaraColorSource.NEXARA,
        ),
    ) {
        Surface(color = MaterialTheme.colorScheme.background) {
            NexaraSettingsPageLayout(title = stringResource(R.string.network_access_title), onBack = {}) { padding ->
                NetworkAccessSettingsContent(
                    snapshot = previewNetworkSnapshot(configured),
                    onEditHosts = {},
                    onOpenTailscale = {},
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

/** 与总表规则一致的预览数据；[configured] 为 true 时白名单有两项且 tailnet 已连接。 */
internal fun previewNetworkSnapshot(configured: Boolean): AgentNetworkSnapshot {
    fun row(tool: AgentNetworkTool, vararg states: NetworkAccessState) =
        NetworkAccessRow(tool, NetworkTargetClass.entries.zip(states.toList()) { target, state -> NetworkAccessCell(target, state) })
    val guarded = arrayOf(NetworkAccessState.ALLOWED, NetworkAccessState.ALLOWLIST_ONLY, NetworkAccessState.ALLOWLIST_ONLY, NetworkAccessState.TAILNET_REQUIRED)
    val configuredOnly = Array(4) { NetworkAccessState.CONFIGURED_ONLY }
    return AgentNetworkSnapshot(
        lanHosts = if (configured) listOf("nas.local", "127.0.0.1") else emptyList(),
        tailnetAvailable = true,
        tailnetEnabled = configured,
        tailnetRunning = configured,
        tailnetPeerAllowlist = if (configured) listOf("100.64.0.7") else emptyList(),
        rows = listOf(
            row(AgentNetworkTool.WEB_FETCH, *guarded),
            row(AgentNetworkTool.MEDIA_FETCH, *guarded),
            row(AgentNetworkTool.SCRIPT, *guarded),
            row(AgentNetworkTool.MCP, *configuredOnly),
            row(
                AgentNetworkTool.BUILTIN_PROCESS,
                NetworkAccessState.UNRESTRICTED, NetworkAccessState.UNRESTRICTED, NetworkAccessState.UNRESTRICTED, NetworkAccessState.TAILNET_REQUIRED,
            ),
            row(AgentNetworkTool.TERMUX, *Array(4) { NetworkAccessState.UNRESTRICTED }),
            row(AgentNetworkTool.SSH, *configuredOnly),
        ),
    )
}
