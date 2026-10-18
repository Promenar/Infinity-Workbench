package com.promenar.nexara.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothStreamContentTest {
    private val frameNanos = 16_666_667L

    @Test
    fun `流式中途挂载立即显示当前全文且不为历史文字添加渐显`() {
        val revealer = StreamRevealer()
        val current = "已经读过的👩🏽‍💻正文".repeat(400)

        val frame = revealer.updateTarget(current, true, 1_000_000_000L)

        assertEquals(current, frame.text)
        assertFalse(frame.hasFadingText)
        assertFalse(revealer.hasPendingText)
    }

    @Test
    fun `挂载之后新增的增量才逐帧放出并保留历史前缀`() {
        val revealer = StreamRevealer()
        revealer.updateTarget("已读正文", true, 1_000_000_000L)

        val arrived = revealer.updateTarget("已读正文" + "新".repeat(100), true, 1_100_000_000L)
        val advanced = revealer.advanceFrame(1_100_000_000L + frameNanos)

        assertEquals("已读正文", arrived.text)
        assertTrue(advanced.text.startsWith("已读正文"))
        assertTrue(advanced.text.length in 5..103)
        assertTrue(advanced.recentRanges.all { it.start >= 4 })
    }

    @Test
    fun `部分放出帧携带原始目标来源以证明稳定前缀`() {
        val revealer = StreamRevealer()
        revealer.updateTarget("已读", true, 0L)
        val target = "已读" + "新".repeat(100)
        revealer.updateTarget(target, true, 100_000_000L)

        val frame = revealer.advanceFrame(100_000_000L + frameNanos)

        assertTrue(frame.text.length < target.length)
        assertSame(target, frame.sourceText)
    }

    @Test
    fun `每100毫秒到达5字符时稳态逐帧平滑且有积压时不停顿6帧`() {
        val revealer = StreamRevealer()
        revealer.updateTarget("", isStreaming = true, nowNanos = 0L)
        val deltas = mutableListOf<Int>()
        var previousLength = 0
        var pausedFrames = 0
        var longestPause = 0
        var target = ""
        for (frame in 1..180) {
            val now = frame * frameNanos
            if (frame % 6 == 0) {
                target += "甲乙丙丁戊"
                revealer.updateTarget(target, isStreaming = true, nowNanos = now)
            }
            val text = revealer.advanceFrame(now).text
            val delta = text.length - previousLength
            if (now >= 500_000_000L) {
                deltas += delta
                pausedFrames = if (delta == 0 && text.length < target.length) pausedFrames + 1 else 0
                longestPause = maxOf(longestPause, pausedFrames)
            }
            previousLength = text.length
        }
        val median = deltas.sorted()[deltas.size / 2]
        assertTrue("稳态每帧最多显示2字符：$deltas", deltas.all { it in 0..2 })
        assertTrue("单帧不能超过中位数3倍", deltas.max() <= median * 3)
        assertTrue("积压期间最长停顿为 $longestPause 帧", longestPause < 6)
    }

    @Test
    fun `2000字符积压遵守每秒2000字符上限并在1200毫秒内追平`() {
        val revealer = StreamRevealer()
        revealer.updateTarget("", isStreaming = true, nowNanos = 0L)
        val arrivedAt = 100_000_000L
        val target = "字".repeat(2000)
        revealer.updateTarget(target, isStreaming = true, nowNanos = arrivedAt)
        for (frame in 1..36) revealer.advanceFrame(arrivedAt + frame * frameNanos)
        assertTrue("600毫秒最多放出1200字符", revealer.shownLength <= 1200)
        assertTrue("速率上限意味着至少1000毫秒", revealer.shownLength < target.length)
        for (frame in 37..72) revealer.advanceFrame(arrivedAt + frame * frameNanos)
        assertEquals(target, revealer.visibleText)
    }

    @Test
    fun `结束时300字符积压在300毫秒内完整放出`() {
        val target = "字".repeat(300)
        val revealer = StreamRevealer()
        revealer.updateTarget("", isStreaming = true, nowNanos = 0L)
        revealer.updateTarget(target, isStreaming = true, nowNanos = 0L)
        revealer.updateTarget(target, isStreaming = false, nowNanos = 0L)
        for (frame in 1..17) revealer.advanceFrame(frame * frameNanos)
        assertTrue(revealer.shownLength < target.length)
        assertEquals(target, revealer.advanceFrame(300_000_000L).text)
    }

    @Test
    fun `非流式消息直接显示全文`() {
        val revealer = StreamRevealer()
        assertEquals("历史消息", revealer.updateTarget("历史消息", false, 0L).text)
        assertFalse(revealer.hasPendingText)
    }

    @Test
    fun `动画禁用时流式内容直接显示全文`() {
        val revealer = StreamRevealer()
        assertEquals("完整内容", revealer.updateTarget("完整内容", true, 0L, animationsEnabled = false).text)
        assertFalse(revealer.hasPendingText)
    }

    @Test
    fun `流结束后没有增长时全文保持稳定`() {
        val revealer = StreamRevealer()
        revealer.updateTarget("最终答案", true, 0L)
        revealer.updateTarget("最终答案", false, 0L)
        revealer.advanceFrame(300_000_000L)
        assertEquals("最终答案", revealer.advanceFrame(500_000_000L).text)
        assertEquals("最终答案", revealer.updateTarget("最终答案", false, 900_000_000L).text)
        assertFalse(revealer.hasPendingText)
    }

    @Test
    fun `不拆开代理对组合字符肤色ZWJ国旗与变体序列`() {
        val clusters = listOf("A", "🚀", "e\u0301", "👩🏽‍💻", "👨‍👩‍👧‍👦", "🇨🇳", "1️⃣", "Z")
        val target = clusters.joinToString("")
        val boundaries = clusters.runningFold(0) { length, cluster -> length + cluster.length }.toSet()
        val revealer = StreamRevealer()
        revealer.updateTarget("", true, 0L)
        revealer.updateTarget(target, true, 0L)
        for (frame in 1..100) {
            val shown = revealer.advanceFrame(frame * frameNanos).text
            assertTrue("非法字形截断 ${shown.length}: $shown", shown.length in boundaries)
            assertTrue(target.startsWith(shown))
        }
        assertEquals(target, revealer.visibleText)
    }

    @Test
    fun `文本改写时立即退回已显示文本的公共字形前缀`() {
        val revealer = StreamRevealer()
        revealer.updateTarget("相同👩🏽‍💻旧答案", false, 0L)
        val rewritten = revealer.updateTarget("相同👩🏽‍💻新答案", true, frameNanos)
        assertEquals("相同👩🏽‍💻", rewritten.text)
        assertTrue(revealer.hasPendingText)
    }

    @Test
    fun `改写发生在组合字形内部时回退至字形之前`() {
        val revealer = StreamRevealer()
        revealer.updateTarget("相同e\u0301旧", false, 0L)
        assertEquals("相同", revealer.updateTarget("相同e\u0300新", true, frameNanos).text)
    }

    @Test
    fun `积压超过4000字符时跳到末尾前200字符`() {
        val revealer = StreamRevealer()
        revealer.updateTarget("", true, 0L)
        val target = "甲".repeat(5000)
        assertEquals(target.take(4800), revealer.updateTarget(target, true, 0L).text)
        assertEquals(4800, revealer.shownLength)
    }

    @Test
    fun `积压跳转同样不能拆开emoji`() {
        val revealer = StreamRevealer()
        revealer.updateTarget("", true, 0L)
        val target = "甲".repeat(4799) + "👩🏽‍💻" + "乙".repeat(199)
        val shown = revealer.updateTarget(target, true, 0L).text
        assertEquals("甲".repeat(4799), shown)
    }

    @Test
    fun `到达速率按增长间隔以0点3指数平均`() {
        val revealer = StreamRevealer()
        revealer.updateTarget("", true, 0L)
        revealer.updateTarget("甲".repeat(10), true, 100_000_000L)
        revealer.updateTarget("甲".repeat(20), true, 200_000_000L)
        revealer.updateTarget("甲".repeat(50), true, 300_000_000L)
        assertEquals(160.0, revealer.estimatedRate, 0.0001)
    }

    @Test
    fun `时钟没有推进时不额外放出字符`() {
        val revealer = StreamRevealer()
        revealer.updateTarget("", true, 0L)
        revealer.updateTarget("新内容", true, 0L)
        assertEquals("", revealer.advanceFrame(0L).text)
        assertEquals("", revealer.advanceFrame(0L).text)
    }

    @Test
    fun `新文字注解透明度按60和120毫秒分档`() {
        assertEquals(0.3f, streamRevealAlpha(ageNanos = 0L), 0.001f)
        assertEquals(0.3f, streamRevealAlpha(ageNanos = 59_999_999L), 0.001f)
        assertEquals(0.65f, streamRevealAlpha(ageNanos = 60_000_000L), 0.001f)
        assertEquals(0.65f, streamRevealAlpha(ageNanos = 119_999_999L), 0.001f)
        assertEquals(1f, streamRevealAlpha(ageNanos = 120_000_000L), 0.001f)
    }
}
