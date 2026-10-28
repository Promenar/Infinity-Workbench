package com.promenar.nexara.ui.common.motion

import com.promenar.nexara.domain.generation.GenerationFailureCode
import com.promenar.nexara.domain.generation.GenerationPhase
import com.promenar.nexara.domain.generation.GenerationRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationMotionTest {
    private val frame = 1f / 60f

    private fun GenerationMotion.run(seconds: Float) {
        repeat((seconds / frame).toInt()) { step(frame) }
    }

    @Test
    fun `思考归入首字前，工具执行与等待用户各自成阶段`() {
        assertEquals(GenerationMotionPhase.WAIT, generationMotionPhase(GenerationPhase.CONNECTING, awaitingUser = false))
        assertEquals(GenerationMotionPhase.WAIT, generationMotionPhase(GenerationPhase.THINKING, awaitingUser = false))
        assertEquals(GenerationMotionPhase.STREAM, generationMotionPhase(GenerationPhase.STREAMING, awaitingUser = false))
        assertEquals(GenerationMotionPhase.TOOL, generationMotionPhase(GenerationPhase.EXECUTING_TOOLS, awaitingUser = false))
        assertEquals(GenerationMotionPhase.APPROVAL, generationMotionPhase(GenerationPhase.WAITING_APPROVAL, awaitingUser = false))
        assertEquals(GenerationMotionPhase.APPROVAL, generationMotionPhase(GenerationPhase.WAITING_INPUT, awaitingUser = false))
        // 子代理在工具执行中请求审批：轮到用户处理，优先显示等待审批。
        assertEquals(GenerationMotionPhase.APPROVAL, generationMotionPhase(GenerationPhase.EXECUTING_TOOLS, awaitingUser = true))
        // 持久化的待批准调用在生成任务退出后仍提示。
        assertEquals(GenerationMotionPhase.APPROVAL, generationMotionPhase(null, awaitingUser = true))
        assertNull(generationMotionPhase(GenerationPhase.COMPLETED, awaitingUser = false))
        assertNull(generationMotionPhase(null, awaitingUser = false))
    }

    @Test
    fun `开始生成时直接显示首个阶段，不淡入`() {
        val controller = MotionPhaseController()
        controller.step(frame, GenerationMotionPhase.WAIT)
        assertEquals(GenerationMotionPhase.WAIT, controller.shown)
        assertEquals(1f, controller.weight(GenerationMotionPhase.WAIT))
    }

    @Test
    fun `连续的短工具调用不会让显示阶段来回切换`() {
        val controller = MotionPhaseController()
        controller.step(frame, GenerationMotionPhase.WAIT)
        repeat(30) { controller.step(frame, GenerationMotionPhase.WAIT) }
        // 工具执行 0.15 秒 → 首字前 0.12 秒 → 工具执行 0.18 秒 → 首字前 0.1 秒，像连续三次很快的工具调用。
        val switchTimes = mutableListOf<Float>()
        var last = controller.shown
        var clock = 0f
        val bursts = listOf(
            GenerationMotionPhase.TOOL to 0.15f, GenerationMotionPhase.WAIT to 0.12f,
            GenerationMotionPhase.TOOL to 0.18f, GenerationMotionPhase.WAIT to 0.1f,
            GenerationMotionPhase.TOOL to 0.15f, GenerationMotionPhase.WAIT to 0.12f,
        )
        for ((phase, seconds) in bursts) repeat((seconds / frame).toInt()) {
            controller.step(frame, phase)
            clock += frame
            if (controller.shown != last) {
                switchTimes += clock
                last = controller.shown
            }
        }
        // 真实阶段切换了 6 次，显示阶段只跟着切换几次，且两次切换之间至少隔 0.4 秒。
        assertTrue("切换次数：${switchTimes.size}", switchTimes.size in 1..3)
        switchTimes.zipWithNext().forEach { (a, b) -> assertTrue("两次切换间隔 ${b - a} 秒", b - a >= MotionPhaseController.DwellSeconds - 1e-3f) }
    }

    @Test
    fun `稳定阶段的权重保持为 1，不逐帧抖动`() {
        val controller = MotionPhaseController()
        repeat(120) {
            controller.step(frame, GenerationMotionPhase.STREAM)
            assertEquals(1f, controller.weight(GenerationMotionPhase.STREAM))
        }
    }

    @Test
    fun `真实阶段稳定后显示阶段在停留时间后跟上，并在淡入时长内完成交叉淡入淡出`() {
        val controller = MotionPhaseController()
        controller.step(frame, GenerationMotionPhase.WAIT)
        repeat(60) { controller.step(frame, GenerationMotionPhase.WAIT) }
        controller.step(frame, GenerationMotionPhase.STREAM)
        assertEquals("超过停留时间后立即切换", GenerationMotionPhase.STREAM, controller.shown)
        repeat(7) { controller.step(frame, GenerationMotionPhase.STREAM) }
        val wait = controller.weight(GenerationMotionPhase.WAIT)
        val stream = controller.weight(GenerationMotionPhase.STREAM)
        assertTrue("淡入进行中：$wait / $stream", wait in 0.1f..0.9f)
        assertEquals("权重之和保持为 1", 1f, wait + stream, 0.02f)
        repeat(12) { controller.step(frame, GenerationMotionPhase.STREAM) }
        assertEquals(0f, controller.weight(GenerationMotionPhase.WAIT))
        assertEquals(1f, controller.weight(GenerationMotionPhase.STREAM))
    }

    @Test
    fun `生成结束后保留最后显示的阶段，由外层淡出收尾`() {
        val controller = MotionPhaseController()
        controller.step(frame, GenerationMotionPhase.STREAM)
        repeat(30) { controller.step(frame, null) }
        assertEquals(GenerationMotionPhase.STREAM, controller.shown)
        assertEquals(1f, controller.weight(GenerationMotionPhase.STREAM))
    }

    @Test
    fun `出字速率跟随实际到达的字数，停顿后回落到零`() {
        val meter = OutputRateMeter()
        var length = 0
        meter.report(length)
        // 每 0.1 秒到达 4 个字，约 40 字/秒。
        repeat(180) { i ->
            if (i % 6 == 0) {
                length += 4
                meter.report(length)
            }
            meter.step(frame)
        }
        assertEquals(40f, meter.rate, 8f)
        assertEquals(40f / GenerationMotionFullRate, meter.act, 0.15f)
        assertTrue("历史记录了速率", meter.historyAt(0) > 20f)
        repeat(240) { meter.step(frame) }
        assertTrue("停顿 4 秒后速率回落：${meter.rate}", meter.rate < 1f)
        assertEquals(0f, meter.act, 0.02f)
    }

    @Test
    fun `新一轮回复的累计字数变小时不计入速率`() {
        val meter = OutputRateMeter()
        meter.report(500)
        meter.step(frame)
        meter.report(3)
        meter.step(frame)
        assertEquals(0f, meter.rate, 0.01f)
    }

    @Test
    fun `重试倒计时期间显示重试等待，倒计时结束回到首字前`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        val retry = GenerationRetry(attempt = 1, maxAttempts = 10, code = GenerationFailureCode.NETWORK, delayMs = 2_000)
        motion.update(GenerationMotionPhase.WAIT, retry, outputLength = 0, animate = true)
        motion.step(frame)
        assertEquals(GenerationMotionPhase.RETRY, motion.actual)
        assertEquals(2, motion.retrySeconds)
        motion.run(1.2f)
        assertEquals(1, motion.retrySeconds)
        assertTrue(motion.retryLeft in 0.3f..0.5f)
        motion.run(1f)
        assertEquals(GenerationMotionPhase.WAIT, motion.actual)
        assertEquals(GenerationMotionPhase.WAIT, motion.shown)
    }

    @Test
    fun `等待审批不被重试倒计时覆盖`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        val retry = GenerationRetry(attempt = 1, maxAttempts = 10, code = GenerationFailureCode.NETWORK, delayMs = 5_000)
        motion.update(GenerationMotionPhase.APPROVAL, retry, outputLength = 0, animate = true)
        motion.step(frame)
        assertEquals(GenerationMotionPhase.APPROVAL, motion.actual)
    }

    @Test
    fun `系统关闭动画时直接显示真实阶段`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        motion.update(GenerationMotionPhase.TOOL, retry = null, outputLength = 0, animate = false)
        assertEquals(GenerationMotionPhase.TOOL, motion.shown)
        assertEquals(1f, motion.weight(GenerationMotionPhase.TOOL))
        assertEquals(GenerationMotionPhase.TOOL, motion.displayed)
    }

    @Test
    fun `还没开始过生成时按流式显示`() {
        val motion = GenerationMotion(GenerationMotion.Source.LIVE)
        assertEquals(1f, motion.weight(GenerationMotionPhase.STREAM))
        assertEquals(0f, motion.weight(GenerationMotionPhase.WAIT))
    }

    @Test
    fun `演示脚本轮播全部五个阶段`() {
        val motion = GenerationMotion(GenerationMotion.Source.DEMO)
        val seen = mutableSetOf<GenerationMotionPhase>()
        repeat((30f / frame).toInt()) {
            motion.step(frame)
            motion.shown?.let(seen::add)
        }
        assertEquals(GenerationMotionPhase.entries.toSet(), seen)
    }

    @Test
    fun `慢闪每秒一次、亮灭各半，等间隔闪 0点8 秒一次`() {
        val samples = (0 until 1000).map { it / 1000f }
        val litFraction = samples.count { slowBlink(it) > 0.5f } / 1000f
        assertEquals(0.5f, litFraction, 0.08f)
        assertEquals(slowBlink(0.3f), slowBlink(1.3f), 1e-4f)
        assertEquals(1f, evenBlink(0.1f))
        assertEquals(0f, evenBlink(0.5f))
        assertEquals(1f, evenBlink(0.9f))
    }

    @Test
    fun `速率为零时流式的 ACT 灯全部熄灭`() {
        assertTrue((0 until 200).none { actOn(it % 6, it * 0.013f, act = 0f) })
        assertTrue((0 until 200).any { actOn(it % 6, it * 0.013f, act = 1f) })
    }
}
