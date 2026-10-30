package com.promenar.infinity.ui.ssh

import com.promenar.infinity.InfinityApplication
import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.ssh.SshSessionOutput
import com.promenar.infinity.ui.linux.terminal.TerminalExtraKey
import com.promenar.infinity.ui.linux.terminal.TerminalKeyEncoder
import com.promenar.infinity.ui.linux.terminal.TerminalModifiers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    /** 连接成功后读到的主机配置，用于标题副标题的地址。 */
    val host: SshHost? = null,
    /** 按键行的一次性 Ctrl / Alt。 */
    val modifiers: TerminalModifiers = TerminalModifiers.NONE,
)

/** 用户终端对 SSH 运行时的最小依赖；owner 固定为 `ui`，测试中以假实现替代。 */
internal interface SshTerminalBackend {
    suspend fun host(alias: String): SshHost?
    suspend fun open(alias: String, columns: Int, rows: Int): String
    suspend fun send(id: String, input: String)
    suspend fun read(id: String, waitMs: Int): SshSessionOutput
    suspend fun resize(id: String, columns: Int, rows: Int)
    suspend fun close(id: String)
}

private class RuntimeTerminalBackend(private val app: InfinityApplication) : SshTerminalBackend {
    override suspend fun host(alias: String) = app.sshHostStore.find(alias)
    override suspend fun open(alias: String, columns: Int, rows: Int) = app.sshRuntime.openSession(UI_OWNER, alias, columns, rows).sessionId
    override suspend fun send(id: String, input: String) = app.sshRuntime.sendSession(UI_OWNER, id, input)
    override suspend fun read(id: String, waitMs: Int) = app.sshRuntime.readSession(UI_OWNER, id, waitMs)
    override suspend fun resize(id: String, columns: Int, rows: Int) = app.sshRuntime.resizeSession(UI_OWNER, id, columns, rows)
    override suspend fun close(id: String) = app.sshRuntime.closeSession(UI_OWNER, id)
}

private const val UI_OWNER = "ui"

/** 会话已被“全部断开”、空闲策略或运行时关闭时按普通断开处理，不显示错误。 */
private val QUIET_CLOSE_CODES = setOf("SESSION_NOT_FOUND", "RUNTIME_CLOSED")

/**
 * 一台主机的用户终端，随应用进程存活：离开终端页、在设置与会话“远程”面板之间切换都不断开，
 * 也不会有两个读取方同时消费同一 PTY 的输出。只有用户断开、远端关闭、空闲策略或应用退出时结束；
 * 结束后保持“已断开”状态，等用户点“重新连接”，不自动重连。
 */
internal class SshUserTerminal(
    val alias: String,
    private val backend: SshTerminalBackend,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val mutableState = MutableStateFlow(SshTerminalUiState())
    val state: StateFlow<SshTerminalUiState> = mutableState.asStateFlow()
    private val requestedGrid = MutableStateFlow(TerminalGrid(80, 24))
    private val outgoing = Channel<String>(capacity = 64)
    private var connectionJob: Job? = null
    private var explicitlyClosing = false

    init {
        connect()
        scope.launch {
            requestedGrid.collectLatest { grid ->
                delay(120)
                val id = state.value.sessionId ?: return@collectLatest
                if (state.value.closed) return@collectLatest
                try {
                    withContext(io) { backend.resize(id, grid.columns, grid.rows) }
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

    /** 系统键盘输入：叠加按键行上一次按下的 Ctrl / Alt，用后清除。 */
    fun type(text: String, hardwareControl: Boolean, hardwareAlt: Boolean) {
        val modifiers = state.value.modifiers
        if (modifiers.any) mutableState.update { it.copy(modifiers = TerminalModifiers.NONE) }
        send(text, hardwareControl || modifiers.ctrl, hardwareAlt || modifiers.alt)
    }

    fun sendKey(key: TerminalExtraKey) {
        val current = state.value
        if (current.connecting || current.closed) return
        val text = TerminalKeyEncoder.encode(key, current.modifiers, current.buffer?.emulator?.isCursorKeysApplicationMode == true)
        mutableState.update { it.copy(modifiers = TerminalModifiers.NONE) }
        if (!outgoing.trySend(text).isSuccess) mutableState.update { it.copy(error = "SSH_INPUT_BUSY") }
    }

    fun toggleCtrl() = mutableState.update { it.copy(modifiers = it.modifiers.toggleCtrl()) }

    fun toggleAlt() = mutableState.update { it.copy(modifiers = it.modifiers.toggleAlt()) }

    fun resize(grid: TerminalGrid) {
        requestedGrid.value = grid
    }

    fun disconnect() {
        if (state.value.closed) return
        explicitlyClosing = true
        scope.launch {
            connectionJob?.cancelAndJoin()
            mutableState.update { it.copy(connecting = false, closed = true, modifiers = TerminalModifiers.NONE) }
            while (outgoing.tryReceive().isSuccess) Unit
        }
    }

    /** 已断开时重新连接；连接中或仍在连接时忽略。 */
    fun reconnect() {
        if (!state.value.closed || state.value.connecting) return
        state.value.buffer?.clear()
        mutableState.value = SshTerminalUiState()
        explicitlyClosing = false
        connect()
    }

    private fun connect() {
        connectionJob = scope.launch {
            var openedId: String? = null
            try {
                val host = withContext(io) { backend.host(alias) } ?: throw SshInputException("SSH_HOST_NOT_FOUND")
                val grid = requestedGrid.value
                val id = withContext(io) { backend.open(alias, grid.columns, grid.rows) }.also { openedId = it }
                val buffer = RemoteTerminalBuffer(grid.columns, grid.rows)
                buffer.onInput = { send(it) }
                mutableState.value = SshTerminalUiState(connecting = false, sessionId = id, buffer = buffer, host = host)
                coroutineScope {
                    val sender = launch {
                        for (input in outgoing) withContext(io) { backend.send(id, input) }
                    }
                    try {
                        while (isActive) {
                            val output = withContext(io) { backend.read(id, 250) }
                            if (output.output.isNotEmpty()) buffer.append(output.output)
                            if (output.truncated) mutableState.update { it.copy(truncated = true) }
                            if (output.closed) {
                                mutableState.update { it.copy(closed = true, modifiers = TerminalModifiers.NONE) }
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
                val code = safeSshError(error)
                val quiet = error is SshFailure && code in QUIET_CLOSE_CODES
                mutableState.update {
                    it.copy(connecting = false, closed = true, error = code.takeUnless { quiet }, modifiers = TerminalModifiers.NONE)
                }
            } finally {
                val id = openedId
                if (id != null && (explicitlyClosing || state.value.closed)) {
                    withContext(NonCancellable + io) {
                        try {
                            backend.close(id)
                        } catch (_: Exception) {
                            // 关闭阶段不记录异常正文，避免终端内容或凭据进入诊断。
                        }
                    }
                }
            }
        }
    }
}

/** 进程内的用户终端注册表：每台主机至多一个终端，界面只附着与解除附着。 */
internal object SshUserTerminals {
    private val terminals = HashMap<String, SshUserTerminal>()
    private var scope: CoroutineScope? = null

    /** 取得主机的终端；还没有时新建并开始连接。 */
    fun get(app: InfinityApplication, alias: String): SshUserTerminal = synchronized(this) {
        terminals.getOrPut(alias) {
            val owned = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scope = it }
            SshUserTerminal(alias, RuntimeTerminalBackend(app), owned)
        }
    }

    /** 用户明确要打开终端（主机行的终端按钮、主机操作面板）时调用：已断开的终端重新连接。 */
    fun open(app: InfinityApplication, alias: String): SshUserTerminal = get(app, alias).also { it.reconnect() }
}
