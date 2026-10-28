package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ThinkingPreviewEngineTest {
    /** 汉字宽 20、其余宽 10，用来证明断行按测量宽度而不是按字数。 */
    private val measure: (String) -> Float = { value -> value.sumOf { (if (it.code >= 0x2E80) 20 else 10).toInt() }.toFloat() }

    private fun engine(viewport: Float = 100f) = ThinkingPreviewEngine(measure).also { it.setViewport(viewport) }

    /** 以每 [frameMs] 毫秒一帧、持续 [seconds] 秒，按 [charsPerSecond] 的速度追加思考文本；返回结束时的累计文本。 */
    private fun feed(
        engine: ThinkingPreviewEngine,
        base: String,
        clockMs: Long,
        charsPerSecond: Float,
        seconds: Float,
        frameMs: Long = 16,
    ): Pair<String, Long> {
        var text = base
        var now = clockMs
        var produced = 0f
        var emitted = 0
        val frames = (seconds * 1000 / frameMs).toInt()
        repeat(frames) {
            now += frameMs
            produced += charsPerSecond * frameMs / 1000f
            val want = produced.toInt()
            if (want > emitted) {
                text += "字".repeat(want - emitted)
                emitted = want
            }
            engine.update(text, now)
        }
        return text to now
    }

    @Test
    fun `折叠空白并首次挂载直接显示全文`() {
        val engine = engine()
        engine.update("1. 先确认输入\n\n  2. 再组织输出  ", 0)
        assertThat(engine.substring(0, engine.textLength)).isEqualTo("1. 先确认输入 2. 再组织输出 ")
        assertThat(engine.shown).isEqualTo(engine.textLength.toFloat())
        assertThat(engine.mode).isEqualTo(ThinkingPreviewMode.SINGLE_LINE)
    }

    @Test
    fun `速率越过进入阈值切到翻滚，回差区间内保持原模式，低于退出阈值才切回`() {
        val engine = engine()
        var (text, now) = "字" to 0L
        engine.update(text, now)

        // 低于进入阈值：保持单行；
        feed(engine, text, now, 40f, 6f).also { text = it.first; now = it.second }
        assertThat(engine.mode).isEqualTo(ThinkingPreviewMode.SINGLE_LINE)

        // 高于 45 字/秒：切到翻滚。
        feed(engine, text, now, 60f, 6f).also { text = it.first; now = it.second }
        assertThat(engine.mode).isEqualTo(ThinkingPreviewMode.LINE_ROLL)

        // 回落到 35～45 之间：回差内仍为翻滚。
        feed(engine, text, now, 40f, 6f).also { text = it.first; now = it.second }
        assertThat(engine.mode).isEqualTo(ThinkingPreviewMode.LINE_ROLL)

        // 低于 35：切回单行。
        feed(engine, text, now, 25f, 8f).also { text = it.first; now = it.second }
        assertThat(engine.mode).isEqualTo(ThinkingPreviewMode.SINGLE_LINE)
    }

    @Test
    fun `平滑速率停止输入后衰减，翻滚模式随之退出`() {
        val engine = engine()
        var text = "字"
        var now = 0L
        engine.update(text, now)
        feed(engine, text, now, 80f, 6f).also { text = it.first; now = it.second }
        assertThat(engine.mode).isEqualTo(ThinkingPreviewMode.LINE_ROLL)
        feed(engine, text, now, 0f, 6f)
        assertThat(engine.mode).isEqualTo(ThinkingPreviewMode.SINGLE_LINE)
        assertThat(engine.isBusy).isFalse()
    }

    @Test
    fun `单行模式匀速追赶：积压越多放得越快，停止输入后在末尾平稳停住`() {
        val engine = engine()
        engine.update("字", 0)
        // 一次到达 100 字：积压大于 0.5 秒的量，放出速度随积压加快，不会一帧到位，也不会拖过 1.5 秒。
        val text = "字".repeat(101)
        engine.update(text, 16)
        assertThat(engine.shown).isLessThan(101f)
        var now = 16L
        var previous = engine.shown
        var firstStep = -1f
        repeat(120) {
            now += 16
            engine.update(text, now)
            if (firstStep < 0f) firstStep = engine.shown - previous
            assertThat(engine.shown).isAtLeast(previous)
            previous = engine.shown
        }
        assertThat(engine.shown).isEqualTo(101f)
        assertThat(firstStep).isGreaterThan(0f)
        assertThat(engine.isBusy).isFalse()
    }

    @Test
    fun `放出位置不落在代理对中间`() {
        val engine = engine()
        engine.update("a", 0)
        engine.update("a😀b", 16)
        // 无论放出到哪个浮点位置，整数位置都不会切开 emoji。
        var now = 16L
        repeat(40) {
            now += 8
            engine.update("a😀b", now)
            val index = engine.shownIndex
            if (index in 1 until engine.textLength) {
                assertThat(engine.substring(index - 1, index).first().isHighSurrogate() && engine.substring(index, index + 1).first().isLowSurrogate()).isFalse()
            }
        }
    }

    @Test
    fun `按测量宽度断行：汉字占两倍宽度，每行放不下就换行`() {
        val engine = engine(viewport = 100f)
        // 进入翻滚模式后才排版：先用高速率喂入汉字，再检查行表。
        var text = "字"
        var now = 0L
        engine.update(text, now)
        feed(engine, text, now, 80f, 4f).also { text = it.first; now = it.second }
        assertThat(engine.mode).isEqualTo(ThinkingPreviewMode.LINE_ROLL)
        for (index in 0 until engine.lineCount - 1) {
            val line = engine.lineText(index)
            assertThat(measure(line)).isAtMost(100f)
            // 汉字宽 20，一行 5 个汉字正好 100，不是按 10 字估算的 10 个。
            assertThat(line.length).isEqualTo(5)
        }
    }

    @Test
    fun `拉丁词不被拆开，空格留在上一行`() {
        val engine = engine(viewport = 100f)
        val source = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda mu nu xi omicron pi rho sigma tau "
            .repeat(6)
        engine.update("a", 0)
        var now = 0L
        // 约 80 字/秒逐字喂入，触发翻滚模式并排版。
        var emitted = 1
        repeat(300) {
            now += 16
            emitted = minOf(source.length, 1 + (it + 1) * 80 * 16 / 1000)
            engine.update(source.substring(0, emitted), now)
        }
        assertThat(engine.mode).isEqualTo(ThinkingPreviewMode.LINE_ROLL)
        val words = source.trim().split(" ").toSet()
        for (index in 0 until engine.lineCount - 1) {
            val line = engine.lineText(index)
            // 行尾留下的断点空格不占可见宽度。
            assertThat(measure(line.trimEnd())).isAtMost(100f)
            assertThat(engine.lineText(index + 1).startsWith(" ")).isFalse()
            // 除第一行外，每一行都以完整的词开头，并以完整的词结尾。
            line.trim().split(" ").forEach { assertThat(words).contains(it) }
        }
    }

    @Test
    fun `每行写满后至少停留 0_5 秒才换行`() {
        val engine = engine(viewport = 100f)
        var text = "字"
        var now = 0L
        engine.update(text, now)
        // 速率恰好在进入阈值之上；每秒 50 字，一行 5 字，约 0.1 秒一行，积压很快超过 1.6 行会跳行，
        // 所以改为一次性给出三行后停止输入，只验证“停留”规则。
        feed(engine, text, now, 55f, 5f).also { text = it.first; now = it.second }
        assertThat(engine.mode).isEqualTo(ThinkingPreviewMode.LINE_ROLL)
        // 停止输入，等放出完成并换到最新行。
        feed(engine, text, now, 0f, 0.05f).also { now = it.second }
        val before = engine.rollSeq
        var lastRoll = before
        var lastRollAt = now
        val gaps = mutableListOf<Long>()
        repeat(400) {
            now += 16
            engine.update(text, now)
            if (engine.rollSeq != lastRoll) {
                gaps += now - lastRollAt
                lastRoll = engine.rollSeq
                lastRollAt = now
            }
        }
        // 速率衰减到退出阈值以下前发生的逐行换行，相邻两次间隔不小于 0.5 秒（跳行不受此限，故只检查逐行换行）。
        gaps.drop(1).forEach { assertThat(it).isAtLeast(ThinkingPreviewTuning.MIN_LINE_DWELL_MS) }
    }

    @Test
    fun `积压超过约 1_6 行时跳到最新一行，不追读旧内容`() {
        val engine = engine(viewport = 100f)
        var text = "字"
        var now = 0L
        engine.update(text, now)
        feed(engine, text, now, 80f, 3f).also { text = it.first; now = it.second }
        assertThat(engine.mode).isEqualTo(ThinkingPreviewMode.LINE_ROLL)
        val seqBefore = engine.rollSeq
        // 一次性追加 12 行（60 字）的内容。
        text += "字".repeat(60)
        now += 16
        engine.update(text, now)
        // 同一帧内排版与判定已完成：当前行就是最新一行，并且发生过跳行。
        assertThat(engine.rollSeq).isGreaterThan(seqBefore)
        assertThat(engine.displayLine).isEqualTo(engine.lineCount - 1)
        val (lineStart, _) = engine.visibleLine()
        assertThat(engine.shown).isAtLeast(lineStart.toFloat())
    }

    @Test
    fun `积压在约 1_6 行以内时不跳行`() {
        val engine = engine(viewport = 100f)
        var text = "字"
        var now = 0L
        engine.update(text, now)
        feed(engine, text, now, 80f, 3f).also { text = it.first; now = it.second }
        // 让显示行追上最新行，再追加一行半的内容（1.5 行，小于 1.6）。
        feed(engine, text, now, 0f, 0.2f).also { now = it.second }
        val seq = engine.rollSeq
        text += "字".repeat(7)
        now += 16
        engine.update(text, now)
        assertThat(engine.rollSeq).isEqualTo(seq)
    }

    @Test
    fun `微光速度随平滑速率变快并封顶`() {
        assertThat(thinkingShimmerSpeed(0f)).isEqualTo(ThinkingPreviewTuning.SHIMMER_MIN_SPEED)
        assertThat(thinkingShimmerSpeed(50f)).isGreaterThan(thinkingShimmerSpeed(10f))
        assertThat(thinkingShimmerSpeed(500f)).isEqualTo(ThinkingPreviewTuning.SHIMMER_MAX_SPEED)
    }

    @Test
    fun `新字符透明度随放出位置推进从半透明过渡到不透明`() {
        assertThat(thinkingFadeAlpha(10, 10f)).isEqualTo(ThinkingPreviewTuning.FADE_FLOOR_ALPHA)
        assertThat(thinkingFadeAlpha(10, 11.5f)).isGreaterThan(ThinkingPreviewTuning.FADE_FLOOR_ALPHA)
        assertThat(thinkingFadeAlpha(10, 11.5f)).isLessThan(1f)
        assertThat(thinkingFadeAlpha(10, 13f)).isEqualTo(1f)
        assertThat(thinkingFadeAlpha(10, 99f)).isEqualTo(1f)
    }

    @Test
    fun `阈值与停留常量符合方案`() {
        assertThat(ThinkingPreviewTuning.ENTER_ROLL_RATE).isEqualTo(45f)
        assertThat(ThinkingPreviewTuning.EXIT_ROLL_RATE).isEqualTo(35f)
        assertThat(ThinkingPreviewTuning.MIN_LINE_DWELL_MS).isEqualTo(500L)
        assertThat(ThinkingPreviewTuning.JUMP_BACKLOG_LINES).isEqualTo(1.6f)
        assertThat(ThinkingPreviewTuning.MODE_CROSS_FADE_MS).isEqualTo(200)
    }
}
