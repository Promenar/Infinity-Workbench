package com.promenar.infinity.ui.ssh

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.data.ssh.SshAuthType
import com.promenar.infinity.data.ssh.SshFailure
import com.promenar.infinity.data.ssh.SshHost
import com.promenar.infinity.data.ssh.SshSessionOutput
import com.promenar.infinity.ui.linux.terminal.TerminalExtraKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SshUserTerminalTest {
    /** 读取按队列返回；队列空时返回空输出，模拟远端暂无输出。 */
    private class FakeBackend(private val host: SshHost? = HOST) : SshTerminalBackend {
        val outputs = Channel<Result<SshSessionOutput>>(Channel.UNLIMITED)
        val sent = mutableListOf<String>()
        val closed = mutableListOf<String>()
        var opened = 0

        override suspend fun host(alias: String) = host
        override suspend fun open(alias: String, columns: Int, rows: Int) = "pty-${++opened}"
        override suspend fun send(id: String, input: String) { sent += input }
        override suspend fun read(id: String, waitMs: Int): SshSessionOutput =
            outputs.tryReceive().getOrNull()?.getOrThrow() ?: SshSessionOutput("", closed = false, truncated = false)
        override suspend fun resize(id: String, columns: Int, rows: Int) = Unit
        override suspend fun close(id: String) { closed += id }
    }

    private fun TestScope.terminal(backend: FakeBackend): Pair<SshUserTerminal, CoroutineScope> {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        return SshUserTerminal("nas", backend, scope, dispatcher) to scope
    }

    @Test fun `连接后显示主机，远端关闭后保持已断开并释放会话，不自动重连`() = runTest {
        val backend = FakeBackend()
        val (terminal, scope) = terminal(backend)
        runCurrent()
        assertThat(terminal.state.value.connecting).isFalse()
        assertThat(terminal.state.value.host).isEqualTo(HOST)
        assertThat(terminal.state.value.sessionId).isEqualTo("pty-1")

        backend.outputs.trySend(Result.success(SshSessionOutput("bye", closed = true, truncated = false)))
        advanceTimeBy(100); runCurrent()
        assertThat(terminal.state.value.closed).isTrue()
        assertThat(terminal.state.value.error).isNull()
        assertThat(backend.closed).containsExactly("pty-1")
        advanceTimeBy(10_000); runCurrent()
        assertThat(backend.opened).isEqualTo(1)
        scope.cancel()
    }

    @Test fun `会话已被全部断开或空闲回收时按普通断开处理，不显示错误`() = runTest {
        val backend = FakeBackend()
        val (terminal, scope) = terminal(backend)
        runCurrent()
        backend.outputs.trySend(Result.failure(SshFailure("SESSION_NOT_FOUND")))
        advanceTimeBy(100); runCurrent()
        assertThat(terminal.state.value.closed).isTrue()
        assertThat(terminal.state.value.error).isNull()
        scope.cancel()
    }

    @Test fun `其它失败显示错误码`() = runTest {
        val backend = FakeBackend()
        val (terminal, scope) = terminal(backend)
        runCurrent()
        backend.outputs.trySend(Result.failure(SshFailure("HOST_CONFIGURATION_CHANGED")))
        advanceTimeBy(100); runCurrent()
        assertThat(terminal.state.value.error).isEqualTo("HOST_CONFIGURATION_CHANGED")
        scope.cancel()
    }

    @Test fun `主机已删除时连接失败`() = runTest {
        val backend = FakeBackend(host = null)
        val (terminal, scope) = terminal(backend)
        runCurrent()
        assertThat(terminal.state.value.closed).isTrue()
        assertThat(terminal.state.value.error).isEqualTo("SSH_HOST_NOT_FOUND")
        assertThat(backend.opened).isEqualTo(0)
        scope.cancel()
    }

    @Test fun `用户断开后关闭会话；只有已断开时重新连接才生效`() = runTest {
        val backend = FakeBackend()
        val (terminal, scope) = terminal(backend)
        runCurrent()
        terminal.reconnect()
        runCurrent()
        assertThat(backend.opened).isEqualTo(1)

        terminal.disconnect()
        advanceTimeBy(100); runCurrent()
        assertThat(terminal.state.value.closed).isTrue()
        assertThat(backend.closed).containsExactly("pty-1")

        terminal.reconnect()
        advanceTimeBy(100); runCurrent()
        assertThat(backend.opened).isEqualTo(2)
        assertThat(terminal.state.value.closed).isFalse()
        assertThat(terminal.state.value.sessionId).isEqualTo("pty-2")
        scope.cancel()
    }

    @Test fun `按键行的 Ctrl 作用于下一次输入后清除`() = runTest {
        val backend = FakeBackend()
        val (terminal, scope) = terminal(backend)
        runCurrent()
        terminal.toggleCtrl()
        assertThat(terminal.state.value.modifiers.ctrl).isTrue()
        terminal.type("c", hardwareControl = false, hardwareAlt = false)
        assertThat(terminal.state.value.modifiers.any).isFalse()
        terminal.sendKey(TerminalExtraKey.TAB)
        advanceTimeBy(100); runCurrent()
        assertThat(backend.sent).containsExactly("\u0003", "\t").inOrder()
        scope.cancel()
    }

    private companion object {
        val HOST = SshHost(alias = "nas", hostname = "10.0.0.2", port = 22, username = "root", authType = SshAuthType.KEY)
    }
}
