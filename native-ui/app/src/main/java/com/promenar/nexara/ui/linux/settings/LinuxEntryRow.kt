package com.promenar.nexara.ui.linux.settings

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.domain.linux.LinuxRuntimeState
import com.promenar.nexara.domain.linux.LinuxUnavailableReason
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.testing.UiTags

/** 工具页“本机与远端”中的 Linux 环境入口；渠道不提供时不显示。 */
@Composable
internal fun LinuxEntryRow(state: LinuxRuntimeState?, agentEnabled: Boolean, onClick: () -> Unit) {
    if (state == null || state == LinuxRuntimeState.Unavailable(LinuxUnavailableReason.DISTRIBUTION)) return
    val subtitle = when (state) {
        is LinuxRuntimeState.Unavailable -> stringResource(R.string.linux_entry_unsupported_abi)
        is LinuxRuntimeState.NotInstalled -> stringResource(R.string.linux_entry_not_installed)
        is LinuxRuntimeState.Installing -> stringResource(R.string.linux_entry_installing)
        is LinuxRuntimeState.InstallInterrupted -> stringResource(R.string.linux_entry_interrupted)
        is LinuxRuntimeState.Ready ->
            if (agentEnabled) stringResource(R.string.linux_entry_ready, state.info.version)
            else stringResource(R.string.linux_entry_ready_agent_off)
        is LinuxRuntimeState.Maintaining -> stringResource(R.string.linux_entry_maintaining)
        is LinuxRuntimeState.Broken -> stringResource(R.string.linux_entry_broken)
    }
    NexaraSettingsItem(
        icon = Icons.Rounded.Terminal,
        title = stringResource(R.string.linux_entry_title),
        subtitle = subtitle,
        onClick = onClick,
        showChevron = state !is LinuxRuntimeState.NotInstalled,
        trailingContent = if (state is LinuxRuntimeState.NotInstalled) ({ LinuxNewBadge() }) else null,
        modifier = Modifier.testTag(UiTags.LINUX_ENTRY),
    )
}

@Composable
private fun LinuxNewBadge() {
    Row {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primaryContainer) {
            Text(
                stringResource(R.string.linux_entry_badge_new),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}
