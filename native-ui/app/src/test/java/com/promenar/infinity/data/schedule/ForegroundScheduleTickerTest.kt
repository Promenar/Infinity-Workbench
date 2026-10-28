package com.promenar.infinity.data.schedule

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.schedule.ScheduleTrigger
import com.promenar.infinity.domain.schedule.ScheduledTask
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundScheduleTickerTest {
    private fun task(id: String, next: Long?, enabled: Boolean = true) = ScheduledTask(
        id, "s", id, "检查", ScheduleTrigger.Interval(5), exactRequested = true, exactEffective = true,
        enabled = enabled, nextFireAtMillis = next, runCount = 0, maxRuns = null, expiresAtMillis = null,
        lastFiredAtMillis = null, lastResult = null, createdAtMillis = 0,
    )

    private class Harness(scope: TestScope, initial: List<ScheduledTask>, visible: Boolean = true) {
        val tasks = MutableStateFlow(initial)
        val foreground = MutableStateFlow(visible)
        val fired = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        val completed = mutableListOf<String>()

        init {
            val ticker = ForegroundScheduleTicker(scope.backgroundScope, tasks, foreground, { scope.testScheduler.currentTime }) { id ->
                fired += id
                gate?.await()
                completed += id
            }
            scope.backgroundScope.launch { ticker.run() }
        }
    }

    @Test fun `前台时按计划时刻准点触发且不依赖系统闹钟`() = runTest {
        val h = Harness(this, listOf(task("a", 60_000)))
        advanceTimeBy(59_999); runCurrent()
        assertThat(h.fired).isEmpty()
        advanceTimeBy(1); runCurrent()
        assertThat(h.fired).containsExactly("a")
    }

    @Test fun `退到后台不触发，回到前台立即补触发已过期任务`() = runTest {
        val h = Harness(this, listOf(task("a", 60_000)), visible = false)
        advanceTimeBy(120_000); runCurrent()
        assertThat(h.fired).isEmpty()
        h.foreground.value = true; runCurrent()
        assertThat(h.fired).containsExactly("a")
    }

    @Test fun `列表因其它变化重发时同一次到期只领取一次，新的计划时刻再触发`() = runTest {
        val h = Harness(this, listOf(task("a", 60_000)))
        advanceTimeBy(60_000); runCurrent()
        h.tasks.value = listOf(task("a", 60_000), task("b", null, enabled = false)); runCurrent()
        assertThat(h.fired).containsExactly("a")
        h.tasks.value = listOf(task("a", 360_000)); runCurrent()
        advanceTimeBy(300_000); runCurrent()
        assertThat(h.fired).containsExactly("a", "a")
    }

    @Test fun `同时到期的多个任务都会触发，停用任务不触发`() = runTest {
        val h = Harness(this, listOf(task("a", 60_000), task("b", 60_000), task("c", 60_000, enabled = false)))
        advanceTimeBy(60_000); runCurrent()
        assertThat(h.fired).containsExactly("a", "b")
    }

    @Test fun `触发执行期间任务行变化不会取消正在进行的一轮`() = runTest {
        val h = Harness(this, listOf(task("a", 60_000)))
        h.gate = CompletableDeferred()
        advanceTimeBy(60_000); runCurrent()
        assertThat(h.fired).containsExactly("a")
        // 领取会改写任务行；模拟列表重新发射与退到后台。
        h.tasks.value = listOf(task("a", 60_000).copy(runCount = 1)); runCurrent()
        h.foreground.value = false; runCurrent()
        h.gate!!.complete(Unit); runCurrent()
        assertThat(h.completed).containsExactly("a")
    }
}
