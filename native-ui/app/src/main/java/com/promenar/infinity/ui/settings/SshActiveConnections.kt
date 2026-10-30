package com.promenar.infinity.ui.settings

import android.text.format.DateFormat
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.promenar.infinity.R
import com.promenar.infinity.data.ssh.SshActiveConnection
import com.promenar.infinity.data.ssh.SshConnectionKind
import com.promenar.infinity.ui.common.InfinitySettingsItem
import com.promenar.infinity.ui.common.InfinitySettingsSection
import com.promenar.infinity.ui.theme.InfinitySpacing
import java.util.Date

/**
 * 活动连接排序：用户自己的终端在前，其次转发与智能体；同类按连接先后。
 * 后台命令的连接只在会话的后台任务列表里出现，这里不重复列出。
 */
internal fun sortedActiveConnections(connections: List<SshActiveConnection>): List<SshActiveConnection> =
    connections.filter { it.kind != SshConnectionKind.JOB }.sortedWith(compareBy<SshActiveConnection> { it.kind.ordinal }.thenBy { it.connectedAtMillis }.thenBy { it.alias })

private val SshConnectionKind.icon: ImageVector
    get() = when (this) {
        SshConnectionKind.TERMINAL -> Icons.Rounded.Terminal
        SshConnectionKind.FORWARD -> Icons.Rounded.SwapHoriz
        SshConnectionKind.AGENT -> Icons.Rounded.SmartToy
        SshConnectionKind.JOB -> Icons.Rounded.Schedule
    }

@Composable
private fun SshConnectionKind.label(): String = stringResource(
    when (this) {
        SshConnectionKind.TERMINAL -> R.string.remote_conn_kind_terminal
        SshConnectionKind.FORWARD -> R.string.remote_conn_kind_forward
        SshConnectionKind.AGENT -> R.string.remote_conn_kind_agent
        SshConnectionKind.JOB -> R.string.remote_conn_kind_job
    },
)

/**
 * “活动连接”分区：每条连接一行，行尾断开；用户终端的行点按回到该终端。
 * 断开不做静默重连，终端页会显示已断开与“重新连接”。
 */
@Composable
internal fun SshActiveConnectionsSection(
    connections: List<SshActiveConnection>,
    enabled: Boolean,
    onOpenTerminal: (String) -> Unit,
    onDisconnect: (SshActiveConnection) -> Unit,
) {
    val context = LocalContext.current
    InfinitySettingsSection(
        title = stringResource(R.string.remote_conn_section),
        footer = stringResource(R.string.remote_conn_footer),
    ) {
        sortedActiveConnections(connections).forEach { connection ->
            val detail = stringResource(R.string.remote_conn_since, DateFormat.getTimeFormat(context).format(Date(connection.connectedAtMillis)))
            val terminal = connection.kind == SshConnectionKind.TERMINAL
            InfinitySettingsItem(
                icon = connection.kind.icon,
                title = connection.alias,
                subtitleSegments = listOf(connection.kind.label(), detail),
                onClick = { if (terminal) onOpenTerminal(connection.alias) },
                enabled = enabled,
                onClickLabel = if (terminal) stringResource(R.string.ssh_open_terminal) else null,
                modifier = Modifier.testTag("ssh_active:${connection.kind.name.lowercase()}:${connection.alias}"),
                trailingContent = {
                    IconButton(onClick = { onDisconnect(connection) }, enabled = enabled) {
                        Icon(
                            Icons.Rounded.LinkOff,
                            contentDescription = stringResource(R.string.remote_conn_disconnect, connection.alias),
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(InfinitySpacing.RowIconSize),
                        )
                    }
                },
            )
        }
    }
}
