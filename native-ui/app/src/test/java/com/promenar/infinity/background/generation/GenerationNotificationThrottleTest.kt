package com.promenar.infinity.background.generation

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.domain.generation.GenerationPhase
import com.promenar.infinity.domain.generation.GenerationProgress
import com.promenar.infinity.domain.generation.GenerationTaskSnapshot
import org.junit.Test

class GenerationNotificationThrottleTest {
    private fun work(phase: GenerationPhase = GenerationPhase.STREAMING, tokens: Int = 0, subagents: Int = 0) =
        ForegroundWorkState(
            listOf(
                GenerationTaskSnapshot("task", "session", "assistant", phase, tokens, 1L,
                    progress = GenerationProgress(tokens = tokens.toLong())),
            ),
            subagents,
        )

    @Test
    fun `首次更新立即发出`() {
        val throttle = GenerationNotificationThrottle(1_000)
        assertThat(throttle.delayFor(work().notificationKey(), now = 5_000)).isEqualTo(0)
    }

    @Test
    fun `仅进度计数变化时在间隔内合并`() {
        val throttle = GenerationNotificationThrottle(1_000)
        throttle.markSent(work(tokens = 10).notificationKey(), now = 5_000)
        assertThat(work(tokens = 10).notificationKey()).isEqualTo(work(tokens = 500).notificationKey())
        assertThat(throttle.delayFor(work(tokens = 500).notificationKey(), now = 5_200)).isEqualTo(800)
        assertThat(throttle.delayFor(work(tokens = 900).notificationKey(), now = 6_000)).isEqualTo(0)
    }

    @Test
    fun `阶段或子代理数量变化立即发出`() {
        val throttle = GenerationNotificationThrottle(1_000)
        throttle.markSent(work().notificationKey(), now = 5_000)
        assertThat(throttle.delayFor(work(phase = GenerationPhase.EXECUTING_TOOLS).notificationKey(), now = 5_010)).isEqualTo(0)
        assertThat(throttle.delayFor(work(subagents = 1).notificationKey(), now = 5_010)).isEqualTo(0)
    }

    @Test
    fun `重置后下一次更新立即发出`() {
        val throttle = GenerationNotificationThrottle(1_000)
        throttle.markSent(work().notificationKey(), now = 5_000)
        throttle.reset()
        assertThat(throttle.delayFor(work().notificationKey(), now = 5_001)).isEqualTo(0)
    }
}
