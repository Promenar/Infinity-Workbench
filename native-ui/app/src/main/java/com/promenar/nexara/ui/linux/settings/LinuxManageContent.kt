package com.promenar.nexara.ui.linux.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxStorageUsage
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsRowLayout
import com.promenar.nexara.ui.common.NexaraSettingsSection
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.nexaraDomainColors

@Composable
internal fun LinuxManageContent(
    state: LinuxEnvironmentUiState,
    runtime: LinuxRuntimeState.Ready,
    actions: LinuxEnvironmentActions,
    modifier: Modifier = Modifier,
) {
    var confirm by remember { mutableStateOf<LinuxBusyAction?>(null) }
    val info = runtime.info
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = NexaraSpacing.Large),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Large),
    ) {
        val startup = info.lastDiagnostics?.startupMs
        LinuxStatusLine(
            text = if (startup != null) stringResource(R.string.linux_status_ready_startup, info.version, linuxDuration(startup))
            else stringResource(R.string.linux_status_ready, info.version),
            dot = MaterialTheme.nexaraDomainColors.success,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LinuxQuickAction(Icons.Rounded.Terminal, stringResource(R.string.linux_action_terminal), actions.onOpenTerminal, Modifier.weight(1f))
            LinuxQuickAction(Icons.Rounded.FolderOpen, stringResource(R.string.linux_action_files), actions.onOpenFiles, Modifier.weight(1f))
            LinuxQuickAction(Icons.Rounded.Inventory2, stringResource(R.string.linux_action_packages), actions.onOpenPackages, Modifier.weight(1f))
        }
        NexaraSettingsSection(title = stringResource(R.string.linux_section_agent)) {
            SettingsToggle(
                title = stringResource(R.string.linux_agent_toggle_title),
                description = stringResource(R.string.linux_agent_toggle_subtitle),
                checked = state.agentEnabled,
                onCheckedChange = actions.onSetAgentEnabled,
                modifier = Modifier.testTag(UiTags.LINUX_AGENT_TOGGLE),
            )
            NexaraSettingsRowLayout(
                title = stringResource(R.string.linux_paths_title),
                subtitle = stringResource(R.string.linux_paths_subtitle),
                subtitleIsIdentifier = true,
                subtitleMaxLines = 2,
            )
        }
        LinuxStorageSection(state.usage, busy = state.busy, onClearCache = actions.onClearCache)
        NexaraSettingsSection(title = stringResource(R.string.linux_section_maintenance)) {
            val mirror = linuxMirrorLabel(state.mirror)
            NexaraSettingsItem(
                title = stringResource(R.string.linux_mirror_title),
                subtitle = state.mirrorLatencyMs?.let { stringResource(R.string.linux_mirror_summary_latency, mirror, linuxDuration(it)) } ?: mirror,
                onClick = actions.onOpenMirrors,
                showChevron = true,
                modifier = Modifier.testTag(UiTags.LINUX_MIRROR_ENTRY),
            )
            val diagnostics = info.lastDiagnostics
            NexaraSettingsItem(
                title = stringResource(R.string.linux_diagnostics_title),
                subtitle = when {
                    state.busy == LinuxBusyAction.DIAGNOSTICS -> stringResource(R.string.linux_diagnostics_running)
                    diagnostics == null -> stringResource(R.string.linux_diagnostics_never)
                    diagnostics.passed -> stringResource(R.string.linux_diagnostics_passed, linuxRelativeTime(diagnostics.atMs))
                    else -> stringResource(R.string.linux_diagnostics_failed, linuxRelativeTime(diagnostics.atMs))
                },
                enabled = state.busy == null,
                onClick = actions.onRunDiagnostics,
                trailingContent = if (state.busy == LinuxBusyAction.DIAGNOSTICS) {
                    { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }
                } else null,
                modifier = Modifier.testTag(UiTags.LINUX_DIAGNOSTICS),
            )
            LinuxDangerRows(
                uninstallSubtitle = state.usage?.let { stringResource(R.string.linux_uninstall_subtitle, linuxBytes(it.totalBytes)) }
                    ?: stringResource(R.string.linux_uninstall_subtitle_plain),
                busy = state.busy,
                onRequest = { confirm = it },
            )
        }
    }
    LinuxDangerConfirm(confirm, onDismiss = { confirm = null }, actions = actions)
}

@Composable
private fun LinuxQuickAction(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.heightIn(min = 72.dp),
    ) {
        Column(
            Modifier.padding(vertical = NexaraSpacing.Medium),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** 四段占用条与图例；占用尚未读取时只显示标题。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LinuxStorageSection(usage: LinuxStorageUsage?, busy: LinuxBusyAction?, onClearCache: () -> Unit) {
    val title = usage?.let { stringResource(R.string.linux_section_storage, linuxBytes(it.totalBytes)) }
        ?: stringResource(R.string.linux_section_storage_loading)
    NexaraSettingsSection(title = title) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = NexaraSpacing.Large, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val segments = storageSegments(usage)
            if (usage != null && usage.totalBytes > 0) {
                Row(
                    Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    segments.filter { it.bytes > 0 }.forEach { segment ->
                        Box(Modifier.weight(segment.bytes.toFloat()).fillMaxHeight().background(segment.color))
                    }
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Medium),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    segments.forEach { segment ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.size(8.dp).background(segment.color, CircleShape))
                            Text(
                                stringResource(segment.label, linuxBytes(segment.bytes)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            FilledTonalButton(
                onClick = onClearCache,
                enabled = busy == null && (usage?.cacheBytes ?: 0) > 0,
                modifier = Modifier.heightIn(min = 40.dp).testTag(UiTags.LINUX_CLEAR_CACHE),
            ) {
                if (busy == LinuxBusyAction.CLEAR_CACHE) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.linux_storage_clear_cache))
            }
        }
    }
}

private data class StorageSegment(val label: Int, val bytes: Long, val color: Color)

@Composable
private fun storageSegments(usage: LinuxStorageUsage?): List<StorageSegment> {
    if (usage == null) return emptyList()
    val colors = MaterialTheme.colorScheme
    return listOf(
        StorageSegment(R.string.linux_storage_base, usage.baseBytes, colors.inversePrimary),
        StorageSegment(R.string.linux_storage_packages, usage.packagesBytes, colors.primary),
        StorageSegment(R.string.linux_storage_home, usage.homeBytes, colors.tertiary),
        StorageSegment(R.string.linux_storage_cache, usage.cacheBytes, colors.outline),
    )
}
