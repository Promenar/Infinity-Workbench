package com.promenar.nexara.ui.ssh

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.nexaraDomainColors

@Composable
fun SshTerminalScreen(alias: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as NexaraApplication
    val terminal: SshTerminalViewModel = viewModel(key = "ssh-terminal-$alias", factory = SshTerminalViewModel.factory(app, alias))
    val state by terminal.state.collectAsStateWithLifecycle()
    var terminalView by remember { mutableStateOf<SshTerminalView?>(null) }
    var control by remember { mutableStateOf(false) }
    val currentControl by rememberUpdatedState(control)
    val connected = !state.connecting && !state.closed
    val leave = { terminal.leave(); onBack() }

    BackHandler(onBack = leave)

    DisposableEffect(Unit) {
        onDispose { terminalView?.detach(); terminalView = null }
    }

    NexaraPageLayout(
        title = stringResource(R.string.ssh_terminal_title, alias),
        onBack = leave,
        scrollable = false,
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.large,
        ) {
            Column(
                modifier = Modifier.padding(NexaraSpacing.Medium),
                verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.connecting) {
                        CircularProgressIndicator(modifier = Modifier.size(NexaraSpacing.XLarge))
                    } else {
                        Icon(
                            imageVector = if (state.closed) Icons.Rounded.Info else Icons.Rounded.CheckCircle,
                            contentDescription = null,
                            tint = if (state.closed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.nexaraDomainColors.success,
                            modifier = Modifier.size(NexaraSpacing.XLarge),
                        )
                    }
                    Text(
                        stringResource(
                            when {
                                state.connecting -> R.string.ssh_terminal_connecting
                                state.closed -> R.string.ssh_terminal_closed
                                else -> R.string.ssh_visual_terminal_connected
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.error?.let {
                    Row(horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
                        Icon(
                            imageVector = Icons.Rounded.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(NexaraSpacing.XLarge),
                        )
                        Text(stringResource(R.string.ssh_terminal_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (state.truncated) {
                    Row(horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.nexaraDomainColors.warning,
                            modifier = Modifier.size(NexaraSpacing.XLarge),
                        )
                        Text(stringResource(R.string.ssh_terminal_truncated), color = MaterialTheme.nexaraDomainColors.warning, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = NexaraSpacing.Small),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = MaterialTheme.shapes.medium,
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = NexaraSpacing.XSmall),
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
            ) {
                item {
                    TerminalAction(R.string.ssh_terminal_keyboard, connected) { terminalView?.showKeyboard() }
                }
                item {
                    TerminalAction(R.string.ssh_terminal_bottom, state.buffer != null) { terminalView?.scrollToLatest() }
                }
                item {
                    if (state.closed) TerminalAction(R.string.ssh_terminal_reconnect, !state.connecting) { terminal.reconnect() }
                    else TerminalAction(R.string.ssh_terminal_disconnect, connected) { terminal.disconnect() }
                }
            }
        }
        state.buffer?.let { buffer ->
            Surface(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(vertical = NexaraSpacing.Small),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
            ) {
                AndroidView(
                    factory = { context ->
                        SshTerminalView(context).also { terminalView = it }
                    },
                    update = { view ->
                        view.isEnabled = connected
                        view.onInput = { text, hardwareControl, alt ->
                            terminal.send(text, hardwareControl || currentControl, alt)
                            if (currentControl) control = false
                        }
                        view.onGridChanged = terminal::resize
                        view.attach(buffer)
                    },
                    modifier = Modifier.fillMaxSize().heightIn(min = NexaraSpacing.MinimumTouchTarget),
                )
            }
        } ?: Spacer(Modifier.weight(1f))
        Text(
            stringResource(R.string.ssh_terminal_controls),
            modifier = Modifier.padding(bottom = NexaraSpacing.XSmall),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = NexaraSpacing.XSmall),
                horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.XSmall),
            ) {
                item {
                    FilterChip(
                        selected = control,
                        onClick = { control = !control },
                        enabled = connected,
                        label = { Text(stringResource(R.string.ssh_terminal_ctrl)) },
                        modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget),
                    )
                }
                item { TerminalAction(R.string.ssh_terminal_esc, connected) { terminal.send("\u001B"); control = false } }
                item { TerminalAction(R.string.ssh_terminal_tab, connected) { terminal.send("\t"); control = false } }
                item { TerminalAction(R.string.ssh_terminal_enter, connected) { terminal.send("\r"); control = false } }
                item { TerminalAction(R.string.ssh_terminal_up, connected) { terminal.arrow('A'); control = false } }
                item { TerminalAction(R.string.ssh_terminal_down, connected) { terminal.arrow('B'); control = false } }
                item { TerminalAction(R.string.ssh_terminal_left, connected) { terminal.arrow('D'); control = false } }
                item { TerminalAction(R.string.ssh_terminal_right, connected) { terminal.arrow('C'); control = false } }
            }
        }
    }
}

@Composable
private fun TerminalAction(label: Int, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
        Text(stringResource(label))
    }
}
