package com.promenar.nexara.data.trigger

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.domain.trigger.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SystemTurnDispatcherTest {
    private class Runtime : SystemTurnRuntime {
        val ready = CompletableDeferred<Unit>()
        var exists = true
        var busy = false
        var loads = 0
        var loadWait: CompletableDeferred<Unit>? = null
        var beforeStart: CompletableDeferred<Unit>? = null
        val calls = mutableListOf<SystemTurnRequest>()
        var finish: CompletableDeferred<Unit>? = null
        var rejectedOnce = false
        var cancelled = false
        override suspend fun awaitReady() { ready.await() }
        override suspend fun sessionExists(sessionId: String) = exists
        override suspend fun loadSession(sessionId: String): Boolean { loads++; loadWait?.await(); return exists }
        override suspend fun isBusy() = busy
        override suspend fun run(request: SystemTurnRequest, onStarted: () -> Unit): SystemTurnOutcome? {
            beforeStart?.await()
            if (rejectedOnce) { rejectedOnce = false; busy = true; return null }
            onStarted()
            calls += request
            try { finish?.await() } catch (error: CancellationException) { cancelled = true; throw error }
            return SystemTurnOutcome.Completed
        }
    }
    private fun request(source: String = "a", body: String = "检查") =
        SystemTurnRequest("session", SystemTurnKind.SCHEDULE, source, "检查", body)

    @Test fun `数据未就绪不能读取会话或发出生成`() = runTest {
        val runtime = Runtime()
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val outcome = async { dispatcher.request(request()) }
        runCurrent()
        assertThat(runtime.loads).isEqualTo(0)
        assertThat(runtime.calls).isEmpty()
        runtime.ready.complete(Unit); runCurrent()
        assertThat(outcome.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(runtime.loads).isEqualTo(1)
    }

    @Test fun `缺失会话跳过且不载入`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); exists = false }
        val outcome = async { DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断").request(request()) }
        runCurrent()
        assertThat(outcome.await()).isEqualTo(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SESSION_MISSING))
        assertThat(runtime.loads).isEqualTo(0)
    }

    @Test fun `忙时同来源仅保留最新请求`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); busy = true }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val old = async { dispatcher.request(request(body = "旧")) }; runCurrent()
        val latest = async { dispatcher.request(request(body = "新")) }; runCurrent()
        assertThat(old.await()).isEqualTo(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SUPERSEDED))
        assertThat(runtime.loads).isEqualTo(0)
        runtime.busy = false; advanceTimeBy(250); runCurrent()
        assertThat(latest.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(runtime.calls.map { it.body }).containsExactly("新")
    }

    @Test fun `三十分钟繁忙超时使用虚拟时间`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); busy = true }
        val outcome = async { DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断").request(request()) }
        runCurrent(); advanceTimeBy(30 * 60 * 1000L); runCurrent()
        assertThat(outcome.await()).isEqualTo(SystemTurnOutcome.Skipped(SystemTurnSkipReason.BUSY_TIMEOUT))
        assertThat(runtime.calls).isEmpty()
    }

    @Test fun `其它系统轮次长时间运行时排队请求也能准时超时`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); finish = CompletableDeferred() }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val running = async { dispatcher.request(request("first")) }; runCurrent()
        val pending = async { dispatcher.request(request("second")) }; runCurrent()
        advanceTimeBy(30 * 60 * 1000L); runCurrent()
        assertThat(pending.await()).isEqualTo(SystemTurnOutcome.Skipped(SystemTurnSkipReason.BUSY_TIMEOUT))
        runtime.finish!!.complete(Unit); runCurrent(); running.await()
    }

    @Test fun `调用方取消只撤回尚未发出的请求`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); busy = true }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val pending = launch { dispatcher.request(request()) }; runCurrent()
        pending.cancelAndJoin(); runtime.busy = false; advanceTimeBy(250); runCurrent()
        assertThat(runtime.calls).isEmpty()
        runtime.finish = CompletableDeferred()
        val active = launch { dispatcher.request(request()) }; runCurrent()
        active.cancelAndJoin()
        assertThat(runtime.cancelled).isFalse()
        runtime.finish!!.complete(Unit); runCurrent()
    }

    @Test fun `系统撤回执行窗口只取消匹配来源`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); finish = CompletableDeferred() }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val outcome = async { dispatcher.request(request()) }; runCurrent()
        dispatcher.stopSource("other")
        assertThat(runtime.cancelled).isFalse()
        dispatcher.stopSource("a"); runCurrent()
        assertThat(runtime.cancelled).isTrue()
        assertThat(outcome.await()).isInstanceOf(SystemTurnOutcome.Failed::class.java)
    }

    @Test fun `载入尚未打开的会话期间取消不会发出生成`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); loadWait = CompletableDeferred() }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val waiting = launch { dispatcher.request(request()) }; runCurrent()
        assertThat(runtime.loads).isEqualTo(1)
        waiting.cancelAndJoin(); runtime.loadWait!!.complete(Unit); runCurrent()
        assertThat(runtime.calls).isEmpty()
    }

    @Test fun `载入期间同来源替换也只发出最新请求`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); loadWait = CompletableDeferred() }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val old = async { dispatcher.request(request(body = "旧")) }; runCurrent()
        val latest = async { dispatcher.request(request(body = "新")) }; runCurrent()
        assertThat(old.await()).isEqualTo(SystemTurnOutcome.Skipped(SystemTurnSkipReason.SUPERSEDED))
        runtime.loadWait!!.complete(Unit); runCurrent()
        assertThat(latest.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(runtime.calls.map { it.body }).containsExactly("新")
    }

    @Test fun `等待生成准入期间三十分钟到期仍返回繁忙超时`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); beforeStart = CompletableDeferred() }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val outcome = async { dispatcher.request(request()) }; runCurrent()
        advanceTimeBy(30 * 60 * 1000L); runCurrent()
        assertThat(outcome.await()).isEqualTo(SystemTurnOutcome.Skipped(SystemTurnSkipReason.BUSY_TIMEOUT))
        runtime.beforeStart!!.complete(Unit); runCurrent()
        assertThat(runtime.calls).isEmpty()
    }

    @Test fun `暂停只撤回待发而保留已发轮次的真实结果`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); finish = CompletableDeferred() }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val active = async { dispatcher.request(request()) }; runCurrent()
        dispatcher.withdrawPendingSource("a")
        assertThat(runtime.cancelled).isFalse()
        runtime.finish!!.complete(Unit); runCurrent()
        assertThat(active.await()).isEqualTo(SystemTurnOutcome.Completed)
    }

    @Test fun `开始时失去生成槽位会重新排队不重复完成`() = runTest {
        val runtime = Runtime().apply { ready.complete(Unit); rejectedOnce = true }
        val dispatcher = DefaultSystemTurnDispatcher(backgroundScope, runtime, "未完成", "系统中断")
        val outcome = async { dispatcher.request(request()) }; runCurrent()
        assertThat(outcome.isCompleted).isFalse()
        runtime.busy = false; advanceTimeBy(250); runCurrent()
        assertThat(outcome.await()).isEqualTo(SystemTurnOutcome.Completed)
        assertThat(runtime.calls).hasSize(1)
    }
}
