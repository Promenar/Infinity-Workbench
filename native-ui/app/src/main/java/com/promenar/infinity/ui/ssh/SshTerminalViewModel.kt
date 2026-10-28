package com.promenar.infinity.ui.ssh

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.promenar.infinity.InfinityApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class SshTerminalUiState(
    val connecting: Boolean = true,
    val closed: Boolean = false,
    val error: String? = null,
    val truncated: Boolean = false,
    val sessionId: String? = null,
    val buffer: RemoteTerminalBuffer? = null,
)

/** ViewModel 随终端导航项存活，旋转不关闭连接，离开导航项按主机配置清理。 */
internal class SshTerminalViewModel(private val app: InfinityApplication, private val alias: String) : ViewModel() {
    private val runtime = app.sshRuntime
    private val mutableState = MutableStateFlow(SshTerminalUiState())
    val state = mutableState.asStateFlow()
    private val requestedGrid = MutableStateFlow(TerminalGrid(80, 24))
    private val outgoing = Channel<String>(capacity = 64)
    private var connectionJob: Job? = null
    private var keepTerminal = false
    private var explicitlyClosing = false

    init {
        connect()
        viewModelScope.launch {
            requestedGrid.collectLatest { grid ->
                delay(120)
                val id = state.value.sessionId ?: return@collectLatest
                if (state.value.closed) return@collectLatest
                try {
                    withContext(Dispatchers.IO) { runtime.resizeSession("ui", id, grid.columns, grid.rows) }
                } catch (error: Exception) {
                    mutableState.update { it.copy(error = safeSshError(error)) }
                }
            }
        }
    }

    fun send(text: String, control: Boolean = false, alt: Boolean = false) {
        if (state.value.connecting || state.value.closed) return
        val encoded = TerminalInput.encode(text, control, alt)
        if (encoded.length > 32_768) {
            mutableState.update { it.copy(error = "SSH_INPUT_TOO_LARGE") }
            return
        }
        if (!outgoing.trySend(encoded).isSuccess) {
            mutableState.update { it.copy(error = "SSH_INPUT_BUSY") }
        }
    }

    fun arrow(direction: Char) {
        send(TerminalInput.arrow(direction, state.value.buffer?.emulator?.isCursorKeysApplicationMode == true))
    }

    fun resize(grid: TerminalGrid) {
        requestedGrid.value = grid
    }

    fun disconnect() {
        if (state.value.closed) return
        explicitlyClosing = true
        viewModelScope.launch {
            connectionJob?.cancelAndJoin()
            mutableState.update { it.copy(connecting = false, closed = true) }
            while (outgoing.tryReceive().isSuccess) Unit
        }
    }

    fun leave() {
        if (!keepTerminal) {
            explicitlyClosing = true
            connectionJob?.cancel()
        }
    }

    fun reconnect() {
        if (!state.value.closed || state.value.connecting) return
        state.value.buffer?.clear()
        mutableState.value = SshTerminalUiState()
        explicitlyClosing = false
        connect()
    }

    private fun connect() {
        connectionJob = viewModelScope.launch {
            var openedId: String? = null
            try {
                val host = withContext(Dispatchers.IO) { app.sshHostStore.find(alias) }
                    ?: throw SshInputException("SSH_HOST_NOT_FOUND")
                keepTerminal = host.keepTerminal
                val grid = requestedGrid.value
                val info = withContext(Dispatchers.IO) {
                    runtime.openSession("ui", alias, grid.columns, grid.rows).also { openedId = it.sessionId }
                }
                val buffer = RetainedTerminalBuffers.take(info.sessionId) ?: RemoteTerminalBuffer(grid.columns, grid.rows)
                buffer.onInput = { send(it) }
                mutableState.value = SshTerminalUiState(connecting = false, sessionId = info.sessionId, buffer = buffer)
                coroutineScope {
                    val sender = launch {
                        for (input in outgoing) {
                            withContext(Dispatchers.IO) { runtime.sendSession("ui", info.sessionId, input) }
                        }
                    }
                    try {
                        while (isActive) {
                            val output = withContext(Dispatchers.IO) { runtime.readSession("ui", info.sessionId, 250) }
                            if (output.output.isNotEmpty()) buffer.append(output.output)
                            if (output.truncated) mutableState.update { it.copy(truncated = true) }
                            if (output.closed) {
                                mutableState.update { it.copy(closed = true) }
                                break
                            }
                            if (output.output.isEmpty()) delay(30)
                        }
                    } finally {
                        sender.cancelAndJoin()
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { it.copy(connecting = false, closed = true, error = safeSshError(error)) }
            } finally {
                if (openedId != null && (!keepTerminal || explicitlyClosing || state.value.closed)) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        try {
                            runtime.closeSession("ui", openedId!!)
                        } catch (_: Exception) {
                            // 关闭阶段不记录异常正文，避免终端内容或凭据进入诊断。
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        val current = state.value
        current.buffer?.let { buffer ->
            buffer.onChanged = null
            buffer.onInput = {}
            if (keepTerminal && !explicitlyClosing && !current.closed && current.sessionId != null) {
                RetainedTerminalBuffers.retain(current.sessionId, buffer)
            } else {
                buffer.clear()
            }
        }
        outgoing.close()
        super.onCleared()
    }

    companion object {
        fun factory(app: InfinityApplication, alias: String): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = SshTerminalViewModel(app, alias) as T
        }
    }
}
