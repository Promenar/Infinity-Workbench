package com.promenar.nexara.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.nexara.ui.common.motion.GenerationMotionPhase
import org.junit.Test

class ScopeScreenReadoutTest {
    private val out = CharArray(ScopeReadoutCapacity)

    private fun readout(
        phase: GenerationMotionPhase,
        waitSeconds: Int = 0,
        rate: Float = 0f,
        toolRound: Int = 0,
        retrySeconds: Int = 0,
    ): String = String(out, 0, scopeReadout(phase, waitSeconds, rate, toolRound, retrySeconds, out))

    @Test
    fun `首字前显示等待秒数，超过 100 秒改为分秒`() {
        assertThat(readout(GenerationMotionPhase.WAIT, waitSeconds = 0)).isEqualTo("0")
        assertThat(readout(GenerationMotionPhase.WAIT, waitSeconds = 7)).isEqualTo("7")
        assertThat(readout(GenerationMotionPhase.WAIT, waitSeconds = 99)).isEqualTo("99")
        assertThat(readout(GenerationMotionPhase.WAIT, waitSeconds = 125)).isEqualTo("2:05")
        assertThat(readout(GenerationMotionPhase.WAIT, waitSeconds = 100_000)).isEqualTo("99:59")
    }

    @Test
    fun `流式显示每秒字数，不到 10 保留一位小数`() {
        assertThat(readout(GenerationMotionPhase.STREAM, rate = 0f)).isEqualTo("0.0")
        assertThat(readout(GenerationMotionPhase.STREAM, rate = 7.46f)).isEqualTo("7.5")
        assertThat(readout(GenerationMotionPhase.STREAM, rate = 9.96f)).isEqualTo("10")
        assertThat(readout(GenerationMotionPhase.STREAM, rate = 48.4f)).isEqualTo("48")
        assertThat(readout(GenerationMotionPhase.STREAM, rate = 123_456f)).isEqualTo("9999")
    }

    @Test
    fun `工具执行显示轮次，审批显示感叹号，重试显示倒计时`() {
        assertThat(readout(GenerationMotionPhase.TOOL, toolRound = 3)).isEqualTo("#3")
        assertThat(readout(GenerationMotionPhase.TOOL, toolRound = 0)).isEqualTo("#1")
        assertThat(readout(GenerationMotionPhase.TOOL, toolRound = 250)).isEqualTo("#99")
        assertThat(readout(GenerationMotionPhase.APPROVAL)).isEqualTo("!")
        assertThat(readout(GenerationMotionPhase.RETRY, retrySeconds = 5)).isEqualTo("5")
    }

    @Test
    fun `本轮平均速率与流式读数同一格式`() {
        assertThat(String(out, 0, writeRateReadout(23.6f, out))).isEqualTo("24")
        assertThat(String(out, 0, writeRateReadout(3.04f, out))).isEqualTo("3.0")
    }

    @Test
    fun `读数只用点阵字形里有的字符，且放得进固定的数字区`() {
        val samples = listOf(
            readout(GenerationMotionPhase.WAIT, waitSeconds = 5999),
            readout(GenerationMotionPhase.STREAM, rate = 9999f),
            readout(GenerationMotionPhase.STREAM, rate = 5.5f),
            readout(GenerationMotionPhase.TOOL, toolRound = 99),
            readout(GenerationMotionPhase.APPROVAL),
            readout(GenerationMotionPhase.RETRY, retrySeconds = 6000),
        )
        for (text in samples) {
            assertThat(text.all(::scopeGlyphSupported)).isTrue()
            val chars = text.toCharArray()
            assertThat(scopeReadoutWidth(chars, chars.size)).isAtMost(ScopeScreenSpec.ReadoutCols)
        }
    }
}
