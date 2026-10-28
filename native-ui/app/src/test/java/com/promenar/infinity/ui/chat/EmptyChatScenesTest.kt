package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.common.motion.Tau
import com.promenar.infinity.ui.theme.EmptyChatStyle
import org.junit.Test

class EmptyChatScenesTest {

    @Test
    fun `短按抬起才算点按，记录按下位置`() {
        val gesture = EmptyChatGesture()
        assertThat(gesture.update(120f, 80f, 1f, 0.016f)).isFalse()
        assertThat(gesture.update(122f, 81f, 1.05f, 0.016f)).isFalse()
        assertThat(gesture.update(Float.NaN, Float.NaN, 1.1f, 0.016f)).isTrue()
        assertThat(gesture.tapX).isEqualTo(120f)
        assertThat(gesture.tapY).isEqualTo(80f)
        assertThat(gesture.rotY).isEqualTo(0f)
    }

    @Test
    fun `拖动转视角不算点按，松手后带惯性继续转并逐渐停下`() {
        val gesture = EmptyChatGesture()
        gesture.update(100f, 100f, 0f, 0.016f)
        gesture.update(130f, 110f, 0.016f, 0.016f)
        gesture.update(160f, 120f, 0.032f, 0.016f)
        val afterDrag = gesture.rotY
        assertThat(afterDrag).isGreaterThan(0.3f)
        assertThat(gesture.rotX).isGreaterThan(0f)
        assertThat(gesture.update(Float.NaN, Float.NaN, 0.048f, 0.016f)).isFalse()
        repeat(10) { gesture.update(Float.NaN, Float.NaN, 0.05f + it * 0.016f, 0.016f) }
        val coasting = gesture.rotY
        assertThat(coasting).isGreaterThan(afterDrag)
        repeat(600) { gesture.update(Float.NaN, Float.NaN, 1f + it * 0.016f, 0.016f) }
        val settled = gesture.rotY
        gesture.update(Float.NaN, Float.NaN, 20f, 0.016f)
        assertThat(gesture.rotY).isEqualTo(settled)
    }

    @Test
    fun `俯仰角限制在可看清带面的范围内`() {
        val gesture = EmptyChatGesture()
        gesture.update(0f, 0f, 0f, 0.016f)
        gesture.update(0f, 2000f, 0.016f, 0.016f)
        assertThat(gesture.rotX).isEqualTo(0.5f)
        gesture.update(0f, -4000f, 0.032f, 0.016f)
        assertThat(gesture.rotX).isEqualTo(-0.9f)
    }

    @Test
    fun `流环粒子越过一圈时翻到带面另一侧`() {
        val flow = MobiusFlowParticles(50)
        val before = flow.v.copyOf()
        val laps = IntArray(50)
        var previous = flow.u.copyOf()
        repeat(400) {
            flow.step(0.1f)
            for (i in 0 until 50) if (flow.u[i] < previous[i]) laps[i]++
            previous = flow.u.copyOf()
        }
        for (i in 0 until 50) {
            assertThat(flow.u[i]).isIn(com.google.common.collect.Range.closedOpen(0f, Tau))
            assertThat(flow.v[i]).isEqualTo(if (laps[i] % 2 == 0) before[i] else -before[i])
        }
    }

    @Test
    fun `字标变形按 11 秒周期往返，点按跳到下一段`() {
        val targets = floatArrayOf(10f, 50f, 200f, 50f, 390f, 50f)
        val morph = MorphParticles(targets, 6, width = 400f)
        assertThat(morph.progress).isEqualTo(0f)
        morph.step(4f)
        assertThat(morph.progress).isWithin(1e-4f).of(0.5f)
        morph.step(2f)
        assertThat(morph.progress).isEqualTo(1f)
        morph.skip()
        assertThat(morph.progress).isEqualTo(1f)
        morph.step(1f)
        assertThat(morph.progress).isWithin(1e-4f).of(0.5f)
        // 字标左侧的粒子先飞向带面。
        val restart = MorphParticles(targets, 6, width = 400f)
        restart.step(4f)
        assertThat(restart.particleProgress(0)).isGreaterThan(restart.particleProgress(2))
    }

    @Test
    fun `提示语让开图形：莫比乌斯与球体比字标留得更远`() {
        val wordmark = emptyChatPromptGapDp(EmptyChatStyle.LED_WORDMARK, 411f, 700f)
        val mobius = emptyChatPromptGapDp(EmptyChatStyle.MOBIUS, 411f, 700f)
        val globe = emptyChatPromptGapDp(EmptyChatStyle.LED_GLOBE, 411f, 700f)
        assertThat(wordmark).isEqualTo(56f)
        assertThat(mobius).isGreaterThan(wordmark)
        assertThat(globe).isGreaterThan(wordmark)
    }
}
