package com.promenar.nexara.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.tailnet.TailnetEndpoints
import com.promenar.nexara.data.tailnet.TailnetPeer
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.theme.NexaraMotion
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.nexaraDomainColors

/** 内置 Tailscale 状态行所需的本机信息。 */
data class ProviderTailnetInfo(
    val selfName: String? = null,
    val tailnetName: String? = null,
    val peers: List<TailnetPeer> = emptyList(),
)

/** “API 源”分组：公网 | Tailscale 内网 分段选择，选中 Tailscale 时显示内置 Tailscale 状态。 */
@Composable
internal fun ProviderApiSourceSection(
    source: ProviderApiSourceState,
    tailnet: ProviderTailnetInfo,
    onSourceSelected: (ProviderApiSource) -> Unit,
    onOpenTailscaleSettings: () -> Unit,
) {
    NexaraSettingsSection(
        title = stringResource(R.string.provider_api_source_title),
        footer = stringResource(R.string.provider_api_source_footer),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val options = listOf(
                Triple(ProviderApiSource.PUBLIC, R.string.provider_api_source_public, Icons.Rounded.Language),
                Triple(ProviderApiSource.TAILNET, R.string.provider_api_source_tailnet, Icons.Rounded.Lan),
            )
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                    .testTag(PROVIDER_API_SOURCE_TAG),
            ) {
                options.forEachIndexed { index, (option, label, icon) ->
                    val selected = source.selected == option
                    SegmentedButton(
                        selected = selected,
                        onClick = { onSourceSelected(option) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        modifier = Modifier
                            .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                            .testTag(providerApiSourceOptionTag(option)),
                        icon = {
                            SegmentedButtonDefaults.Icon(active = selected) {
                                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                            }
                        },
                        label = { Text(stringResource(label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
            AnimatedVisibility(
                visible = source.selected == ProviderApiSource.TAILNET,
                enter = NexaraMotion.expandIn,
                exit = NexaraMotion.shrinkOut,
            ) {
                BuiltInTailscaleRow(source.builtIn, tailnet, onOpenTailscaleSettings)
            }
        }
    }
}

@Composable
private fun BuiltInTailscaleRow(
    status: BuiltInTailscaleStatus,
    tailnet: ProviderTailnetInfo,
    onOpenTailscaleSettings: () -> Unit,
) {
    val colors = MaterialTheme.nexaraDomainColors
    val (dotColor, filled) = when (status) {
        BuiltInTailscaleStatus.CONNECTED -> colors.success to true
        BuiltInTailscaleStatus.CONNECTING -> colors.warning to false
        BuiltInTailscaleStatus.NEEDS_LOGIN -> colors.warning to true
        BuiltInTailscaleStatus.FAILED -> MaterialTheme.colorScheme.error to true
        BuiltInTailscaleStatus.DISABLED, BuiltInTailscaleStatus.UNAVAILABLE -> MaterialTheme.colorScheme.outline to false
    }
    val title = stringResource(
        when (status) {
            BuiltInTailscaleStatus.CONNECTED -> R.string.provider_tailnet_status_connected
            BuiltInTailscaleStatus.CONNECTING -> R.string.provider_tailnet_status_connecting
            BuiltInTailscaleStatus.NEEDS_LOGIN -> R.string.provider_tailnet_status_needs_login
            BuiltInTailscaleStatus.FAILED -> R.string.provider_tailnet_status_failed
            BuiltInTailscaleStatus.DISABLED -> R.string.provider_tailnet_status_disabled
            BuiltInTailscaleStatus.UNAVAILABLE -> R.string.provider_tailnet_status_unavailable
        },
    )
    val detail = when (status) {
        BuiltInTailscaleStatus.CONNECTED ->
            listOfNotNull(tailnet.selfName, tailnet.tailnetName).joinToString(" · ").takeIf { it.isNotBlank() } // wrap-check: allow 单行省略
        BuiltInTailscaleStatus.DISABLED -> stringResource(R.string.provider_tailnet_status_disabled_detail)
        BuiltInTailscaleStatus.UNAVAILABLE -> stringResource(R.string.provider_tailnet_status_unavailable_detail)
        BuiltInTailscaleStatus.NEEDS_LOGIN -> stringResource(R.string.provider_tailnet_status_needs_login_detail)
        else -> null
    }
    val action = when (status) {
        BuiltInTailscaleStatus.DISABLED -> R.string.provider_tailnet_action_enable
        BuiltInTailscaleStatus.NEEDS_LOGIN -> R.string.provider_tailnet_action_login
        BuiltInTailscaleStatus.UNAVAILABLE -> null
        else -> R.string.provider_tailnet_action_settings
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
            .testTag(PROVIDER_TAILNET_STATUS_TAG)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .then(
                    if (filled) Modifier.background(dotColor, CircleShape)
                    else Modifier.border(1.5.dp, dotColor, CircleShape),
                ),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall.let { style ->
                        if (status == BuiltInTailscaleStatus.CONNECTED) style.copy(fontFamily = FontFamily.Monospace) else style
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = if (status == BuiltInTailscaleStatus.CONNECTED) 1 else 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        action?.let {
            TextButton(
                onClick = onOpenTailscaleSettings,
                modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag(PROVIDER_TAILNET_ACTION_TAG),
            ) { Text(stringResource(it)) }
        }
    }
}

/** 接口地址下方的提示；[ProviderApiSourceHint.TAILNET_NEEDS_HTTPS] 作为输入框错误显示，不在这里。 */
@Composable
internal fun ProviderAddressHint(
    hint: ProviderApiSourceHint?,
    onSourceSelected: (ProviderApiSource) -> Unit,
) {
    when (hint) {
        ProviderApiSourceHint.RECOGNIZED_TAILNET -> Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(PROVIDER_ADDRESS_HINT_TAG),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = stringResource(R.string.provider_address_hint_recognized),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        ProviderApiSourceHint.ADDRESS_IS_TAILNET, ProviderApiSourceHint.ADDRESS_IS_PUBLIC -> {
            val toTailnet = hint == ProviderApiSourceHint.ADDRESS_IS_TAILNET
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp)
                    .testTag(PROVIDER_ADDRESS_HINT_TAG)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    Icons.Rounded.Info,
                    contentDescription = null,
                    tint = MaterialTheme.nexaraDomainColors.warning,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = stringResource(
                        if (toTailnet) R.string.provider_address_hint_is_tailnet else R.string.provider_address_hint_is_public,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { onSourceSelected(if (toTailnet) ProviderApiSource.TAILNET else ProviderApiSource.PUBLIC) },
                    modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget).testTag(PROVIDER_ADDRESS_HINT_FIX_TAG),
                ) {
                    Text(
                        stringResource(
                            if (toTailnet) R.string.provider_address_hint_use_tailnet else R.string.provider_address_hint_use_public,
                        ),
                    )
                }
            }
        }
        ProviderApiSourceHint.TAILNET_NEEDS_HTTPS, null -> Unit
    }
}

/** tailnet 设备：点按把接口地址换成该设备的 https 全名；缺少 MagicDNS 全名的设备不列出。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TailnetPeerChips(
    peers: List<TailnetPeer>,
    baseUrl: String,
    onPeerSelected: (TailnetPeer) -> Unit,
) {
    val usable = peers.filter { it.allowed && TailnetEndpoints.peerHost(it) != null }
        .sortedWith(compareBy({ !it.online }, { it.name.lowercase() }))
    if (usable.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.provider_tailnet_peers_title),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            modifier = Modifier.testTag(PROVIDER_TAILNET_PEERS_TAG),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val offlineLabel = stringResource(R.string.provider_tailnet_peer_offline)
            usable.forEach { peer ->
                val selected = TailnetEndpoints.matchesPeer(baseUrl, peer)
                val stateText = if (peer.online) null else offlineLabel
                FilterChip(
                    selected = selected,
                    onClick = { onPeerSelected(peer) },
                    label = {
                        Text(
                            text = if (stateText == null) peer.name else stringResource(R.string.provider_tailnet_peer_label_offline, peer.name),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    leadingIcon = {
                        val color = if (peer.online) MaterialTheme.nexaraDomainColors.success else MaterialTheme.colorScheme.outline
                        Box(
                            Modifier
                                .size(6.dp)
                                .then(
                                    if (peer.online) Modifier.background(color, CircleShape)
                                    else Modifier.border(1.dp, color, CircleShape),
                                ),
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        labelColor = if (peer.online) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                    ),
                    modifier = Modifier
                        .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                        .testTag(providerTailnetPeerTag(peer.name))
                        .semantics {
                            stateText?.let { stateDescription = it }
                            contentDescription = peer.name
                        },
                )
            }
        }
    }
}

internal const val PROVIDER_API_SOURCE_TAG = "provider_api_source"
internal const val PROVIDER_TAILNET_STATUS_TAG = "provider_tailnet_status"
internal const val PROVIDER_TAILNET_ACTION_TAG = "provider_tailnet_action"
internal const val PROVIDER_ADDRESS_HINT_TAG = "provider_address_hint"
internal const val PROVIDER_ADDRESS_HINT_FIX_TAG = "provider_address_hint_fix"
internal const val PROVIDER_TAILNET_PEERS_TAG = "provider_tailnet_peers"
internal fun providerApiSourceOptionTag(source: ProviderApiSource) = "provider_api_source:${source.name.lowercase()}"
internal fun providerTailnetPeerTag(name: String) = "provider_tailnet_peer:$name"
