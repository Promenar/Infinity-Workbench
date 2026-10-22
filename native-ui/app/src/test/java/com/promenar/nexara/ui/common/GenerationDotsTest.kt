package com.promenar.nexara.ui.common

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GenerationDotsTest {
    private val phases = (0 until 240).map { it / 240f }

    @Test
    fun `每个圆点的鼓起程度在零到一之间并且一轮只起落一次`() {
        for (index in 0..2) {
            val lifts = phases.map { generationDotLift(it, index) }
            assertTrue(lifts.all { it in 0f..1f })
            assertTrue("第 $index 个圆点应接近最高点", lifts.max() > 0.99f)
            // 起落一次：从静息进入鼓起只发生一次（相位按环形计）。
            val risings = (lifts + lifts.first()).zipWithNext().count { (a, b) -> a == 0f && b > 0f }
            assertEquals(1, risings)
        }
    }

    @Test
    fun `三个圆点依次错开形成从左到右的波浪`() {
        val peaks = (0..2).map { index -> phases.maxBy { generationDotLift(it, index) } }
        assertTrue(peaks[0] < peaks[1] && peaks[1] < peaks[2])
        assertEquals(GenerationDotsStagger, peaks[1] - peaks[0], 0.01f)
        assertEquals(GenerationDotsStagger, peaks[2] - peaks[1], 0.01f)
    }

    @Test
    fun `每轮都有三点同时静息的停顿`() {
        assertTrue(phases.any { phase -> (0..2).all { generationDotLift(phase, it) == 0f } })
    }

    @Test
    fun `互补色的中间色沿色相环取较短弧线并保持饱和`() {
        // 蓝（240°）与橙（30°）：较短弧经过品红一侧，中点约 315°，不是灰色。
        val mid = hueMidpoint(Color.hsv(240f, 0.7f, 0.8f), Color.hsv(30f, 0.9f, 0.6f))
        assertEquals(Color.hsv(315f, 0.8f, 0.7f).red, mid.red, 0.01f)
        assertEquals(Color.hsv(315f, 0.8f, 0.7f).green, mid.green, 0.01f)
        assertEquals(Color.hsv(315f, 0.8f, 0.7f).blue, mid.blue, 0.01f)
        // 跨越 0° 的情形：350° 与 10° 的中点是 0°。
        val wrap = hueMidpoint(Color.hsv(350f, 1f, 1f), Color.hsv(10f, 1f, 1f))
        assertEquals(1f, wrap.red, 0.01f)
        assertEquals(wrap.green, wrap.blue, 0.01f)
        // 一端为灰色时沿用另一端的色相。
        val gray = hueMidpoint(Color(0.5f, 0.5f, 0.5f), Color.hsv(120f, 1f, 1f))
        assertTrue(gray.green > gray.red && gray.green > gray.blue)
    }
}
