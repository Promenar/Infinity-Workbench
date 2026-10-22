package com.promenar.nexara.data.tailnet

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TailnetConnectGateTest {
    private class FakeConnector(phase: TailnetPhase) : TailnetConnector {
        val mutableState = MutableStateFlow(TailnetState(phase))
        override val state: StateFlow<TailnetState> = mutableState
        val startResult = CompletableDeferred<Boolean>()
        val forces = mutableListOf<Boolean>()
        override fun isTailnetHost(host: String): Boolean = host.endsWith(".ts.net") || host.startsWith("100.")
        override suspend fun ensureStarted(force: Boolean, timeoutMillis: Long): Boolean {
            forces += force
            return startResult.await()
        }
    }

    @Test
    fun `非 tailnet 目标与缺少主机名时不等待`() = runTest {
        val connector = FakeConnector(TailnetPhase.STOPPED)
        val gate = TailnetConnectGate(connector, clock = { testScheduler.currentTime })
        assertThat(gate.await("api.deepseek.com")).isEqualTo(TailnetConnectResult.NotRequired)
        assertThat(gate.await(null)).isEqualTo(TailnetConnectResult.NotRequired)
        assertThat(connector.forces).isEmpty()
    }

    @Test
    fun `已接入时直接放行，需要登录时立即返回而不空等`() = runTest {
        val connector = FakeConnector(TailnetPhase.RUNNING)
        val gate = TailnetConnectGate(connector, clock = { testScheduler.currentTime })
        assertThat(gate.await("gpu.tail1.ts.net")).isEqualTo(TailnetConnectResult.Ready(waited = false))
        connector.mutableState.value = TailnetState(TailnetPhase.NEEDS_LOGIN)
        assertThat(gate.await("gpu.tail1.ts.net"))
            .isEqualTo(TailnetConnectResult.Unavailable(TailnetUnavailableReason.NEEDS_LOGIN))
        assertThat(connector.forces).isEmpty()
    }

    @Test
    fun `未接入时先拉起再等节点上线，进度依次为启动与接入`() = runTest {
        val connector = FakeConnector(TailnetPhase.FAILED)
        val gate = TailnetConnectGate(connector, clock = { testScheduler.currentTime })
        val steps = mutableListOf<TailnetConnectStep>()
        val result = async { gate.await("100.101.7.42", force = true) { steps += it.step } }
        runCurrent()
        assertThat(steps).containsExactly(TailnetConnectStep.STARTING)
        // 本机代理就绪时，上一轮的 FAILED 已被新一轮的 STARTING 取代。
        connector.mutableState.value = TailnetState(TailnetPhase.STARTING)
        connector.startResult.complete(true)
        runCurrent()
        assertThat(steps).containsExactly(TailnetConnectStep.STARTING, TailnetConnectStep.JOINING).inOrder()
        assertThat(result.isCompleted).isFalse()
        connector.mutableState.value = TailnetState(TailnetPhase.RUNNING)
        assertThat(result.await()).isEqualTo(TailnetConnectResult.Ready(waited = true))
        assertThat(connector.forces).containsExactly(true)
    }

    @Test
    fun `启动失败、上线前要求登录与超时分别给出原因`() = runTest {
        val failed = FakeConnector(TailnetPhase.STOPPED).apply { startResult.complete(false) }
        assertThat(TailnetConnectGate(failed, clock = { testScheduler.currentTime }).await("nas.tail1.ts.net"))
            .isEqualTo(TailnetConnectResult.Unavailable(TailnetUnavailableReason.FAILED))

        val login = FakeConnector(TailnetPhase.STOPPED).apply { startResult.complete(true) }
        val loginResult = async { TailnetConnectGate(login, clock = { testScheduler.currentTime }).await("nas.tail1.ts.net") }
        runCurrent()
        login.mutableState.value = TailnetState(TailnetPhase.NEEDS_LOGIN)
        assertThat(loginResult.await()).isEqualTo(TailnetConnectResult.Unavailable(TailnetUnavailableReason.NEEDS_LOGIN))

        val slow = FakeConnector(TailnetPhase.STOPPED).apply { startResult.complete(true) }
        slow.mutableState.value = TailnetState(TailnetPhase.STARTING)
        val slowResult = async {
            TailnetConnectGate(slow, timeoutMillis = 30_000, clock = { testScheduler.currentTime }).await("nas.tail1.ts.net")
        }
        advanceTimeBy(29_000)
        assertThat(slowResult.isCompleted).isFalse()
        advanceTimeBy(2_000)
        assertThat(slowResult.await()).isEqualTo(TailnetConnectResult.Unavailable(TailnetUnavailableReason.TIMEOUT))

        val hung = FakeConnector(TailnetPhase.STOPPED)
        val hungResult = async {
            TailnetConnectGate(hung, timeoutMillis = 5_000, clock = { testScheduler.currentTime }).await("nas.tail1.ts.net")
        }
        advanceTimeBy(6_000)
        assertThat(hungResult.await()).isEqualTo(TailnetConnectResult.Unavailable(TailnetUnavailableReason.TIMEOUT))
    }
}
