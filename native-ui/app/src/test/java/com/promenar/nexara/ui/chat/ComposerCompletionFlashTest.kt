package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ComposerCompletionFlashTest {
    private fun outcome(kind: ChatGenerationOutcome.Kind, sessionId: String = "s1") = ChatGenerationOutcome(sessionId, kind)

    @Test
    fun `看到运行后以完成收尾时闪一次`() {
        val gate = CompletionFlashGate()
        gate.observe("s1", running = false)
        gate.observe("s1", running = true)
        // 终态在状态值上停留不足一帧：运行标志可能先回落，结果稍后才到。
        gate.observe("s1", running = false)

        assertThat(gate.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED))).isTrue()
        // 同一轮的结果不会重复触发。
        assertThat(gate.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED))).isFalse()
    }

    @Test
    fun `出错或取消收尾不闪`() {
        for (kind in listOf(ChatGenerationOutcome.Kind.FAILED, ChatGenerationOutcome.Kind.CANCELLED)) {
            val gate = CompletionFlashGate()
            gate.observe("s1", running = true)

            assertThat(gate.onOutcome(outcome(kind))).isFalse()
            assertThat(gate.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED))).isFalse()
        }
    }

    @Test
    fun `进入会话时任务已结束不补闪`() {
        val gate = CompletionFlashGate()
        gate.observe("s1", running = false)

        assertThat(gate.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED))).isFalse()
    }

    @Test
    fun `进入时正在运行的一轮完成后会闪`() {
        val gate = CompletionFlashGate()
        gate.observe("s1", running = true)

        assertThat(gate.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED))).isTrue()
    }

    @Test
    fun `切换会话后旧会话的运行记录作废，其它会话的结果也不闪`() {
        val gate = CompletionFlashGate()
        gate.observe("s1", running = true)
        gate.observe("s2", running = false)

        assertThat(gate.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED, "s1"))).isFalse()
        assertThat(gate.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED, "s2"))).isFalse()
    }

    @Test
    fun `连续两轮各闪一次`() {
        val gate = CompletionFlashGate()
        repeat(2) {
            gate.observe("s1", running = true)
            gate.observe("s1", running = false)
            assertThat(gate.onOutcome(outcome(ChatGenerationOutcome.Kind.COMPLETED))).isTrue()
        }
    }
}
