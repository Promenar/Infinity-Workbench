package com.promenar.infinity.data.termux

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TermuxRequestRegistryTest {
    @Test fun `乱序回调按请求ID关联且重复与未知结果被忽略`() = runTest {
        val registry = TermuxRequestRegistry()
        var cleaned = 0
        val first = async { registry.execute("a", 1000, {}, { cleaned++ }) }
        val second = async { registry.execute("b", 1000, {}, { cleaned++ }) }
        runCurrent()
        assertThat(registry.complete("unknown", TermuxCommandResult(stdout = "伪造"))).isFalse()
        assertThat(registry.complete("b", TermuxCommandResult(stdout = "B", exitCode = 0))).isTrue()
        assertThat(registry.complete("b", TermuxCommandResult(stdout = "覆盖"))).isFalse()
        registry.complete("a", TermuxCommandResult(stdout = "A", exitCode = 0))
        assertThat(first.await().stdout).isEqualTo("A")
        assertThat(second.await().stdout).isEqualTo("B")
        assertThat(cleaned).isEqualTo(2)
    }

    @Test fun `超时释放等待并忽略迟到结果但不宣称远端已停止`() = runTest {
        val registry = TermuxRequestRegistry()
        var cleaned = false
        val pending = async { registry.execute("slow", 60_000, {}, { cleaned = true }) }
        runCurrent()
        advanceTimeBy(60_001)
        val result = pending.await()
        assertThat(result.timedOut).isTrue()
        assertThat(result.errorCode).isEqualTo("TERMUX_TIMEOUT")
        assertThat(result.guidance).contains("可能仍在运行")
        assertThat(cleaned).isTrue()
        assertThat(registry.complete("slow", TermuxCommandResult(stdout = "late"))).isFalse()
    }

    @Test fun `取消等待清理回调且同步回调不会丢失`() = runTest {
        val registry = TermuxRequestRegistry()
        var cleaned = 0
        val pending = async { registry.execute("cancel", 1000, {}, { cleaned++ }) }
        runCurrent()
        pending.cancel()
        runCurrent()
        assertThat(registry.complete("cancel", TermuxCommandResult())).isFalse()
        assertThat(cleaned).isEqualTo(1)
        val result = registry.execute("sync", 1000, { registry.complete("sync", TermuxCommandResult(stdout = "即时")) })
        assertThat(result.stdout).isEqualTo("即时")
    }
}
