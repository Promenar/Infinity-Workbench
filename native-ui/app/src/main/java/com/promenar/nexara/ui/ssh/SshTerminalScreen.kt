package com.promenar.nexara.ui.ssh

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.NexaraApplication
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraPageLayout

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
        if (state.connecting) {
            CircularProgressIndicator()
            Text(stringResource(R.string.ssh_terminal_connecting), style = MaterialTheme.typography.bodyMedium)
        }
        state.error?.let {
            Text(stringResource(R.string.ssh_terminal_error, it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.truncated) {
            Text(stringResource(R.string.ssh_terminal_truncated), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (state.closed) Text(stringResource(R.string.ssh_terminal_closed), style = MaterialTheme.typography.bodyMedium)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        state.buffer?.let { buffer ->
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
                modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 48.dp),
            )
        } ?: Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                FilterChip(
                    selected = control,
                    onClick = { control = !control },
                    enabled = connected,
                    label = { Text(stringResource(R.string.ssh_terminal_ctrl)) },
                    modifier = Modifier.heightIn(min = 48.dp),
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

@Composable
private fun TerminalAction(label: Int, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp)) {
        Text(stringResource(label))
    }
}
