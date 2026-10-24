package com.promenar.nexara.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.domain.network.AgentNetworkSnapshot
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.theme.NexaraSpacing

@Composable
fun NetworkAccessSettingsScreen(onBack: () -> Unit, onOpenTailscale: () -> Unit) {
    val access = (LocalContext.current.applicationContext as NexaraApplication).agentNetworkAccess
    val snapshot by access.snapshot.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf(false) }
    NexaraSettingsPageLayout(title = stringResource(R.string.network_access_title), onBack = onBack) { padding ->
        NetworkAccessSettingsContent(
            snapshot = snapshot,
            onEditHosts = { editing = true },
            onOpenTailscale = onOpenTailscale,
            modifier = Modifier.padding(padding),
        )
    }
    LanHostsEditorSheet(
        show = editing,
        stored = snapshot.lanHosts.joinToString("\n"),
        onDismiss = { editing = false },
        onSave = { text -> saveLanHosts(access::setLanHosts, text) },
    )
}

/** 保存失败时返回契约约定的可展示消息；没有消息时由调用方显示通用格式说明。 */
internal fun saveLanHosts(save: (String) -> Unit, text: String): String? = try {
    save(text)
    null
} catch (invalid: IllegalArgumentException) {
    invalid.message?.takeIf(String::isNotBlank) ?: ""
}

@Composable
internal fun lanHostsSummary(snapshot: AgentNetworkSnapshot): String =
    if (snapshot.lanHosts.isEmpty()) stringResource(R.string.network_lan_hosts_empty)
    else pluralStringResource(R.plurals.network_lan_hosts_count, snapshot.lanHosts.size, snapshot.lanHosts.size)

@Composable
internal fun tailnetSummary(snapshot: AgentNetworkSnapshot): String {
    val phase = stringResource(
        when {
            !snapshot.tailnetAvailable -> R.string.network_tailnet_missing
            !snapshot.tailnetEnabled -> R.string.network_tailnet_disabled
            !snapshot.tailnetRunning -> R.string.network_tailnet_offline
            else -> R.string.network_tailnet_running
        },
    )
    val peers = if (snapshot.tailnetPeerAllowlist.isEmpty()) stringResource(R.string.network_tailnet_peers_all)
    else pluralStringResource(R.plurals.network_tailnet_peers_count, snapshot.tailnetPeerAllowlist.size, snapshot.tailnetPeerAllowlist.size)
    return "$phase · $peers"
}

/** 设置页正文：先给两处可调的入口（白名单、Tailscale），再给按工具展开的访问范围总表。 */
@Composable
internal fun NetworkAccessSettingsContent(
    snapshot: AgentNetworkSnapshot,
    onEditHosts: () -> Unit,
    onOpenTailscale: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = NexaraSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.SectionGap),
    ) {
        NexaraSettingsSection(
            title = stringResource(R.string.network_section_controls),
            footer = stringResource(R.string.network_section_controls_footer),
        ) {
            NexaraSettingsItem(
                icon = Icons.Rounded.Dns,
                title = stringResource(R.string.network_lan_hosts_title),
                subtitle = lanHostsSummary(snapshot),
                onClick = onEditHosts,
                showChevron = true,
                subtitleMaxLines = 2,
                modifier = Modifier.testTag("network_lan_hosts_entry"),
            )
            NexaraSettingsItem(
                icon = Icons.Rounded.Hub,
                title = stringResource(R.string.network_tailnet_title),
                subtitle = tailnetSummary(snapshot),
                onClick = onOpenTailscale,
                showChevron = true,
                subtitleMaxLines = 2,
                modifier = Modifier.testTag("network_tailnet_entry"),
            )
        }
        NexaraSettingsSection(
            title = stringResource(R.string.network_section_matrix),
            footer = stringResource(R.string.network_section_matrix_footer),
        ) {
            NetworkAccessRows(snapshot)
        }
    }
}
