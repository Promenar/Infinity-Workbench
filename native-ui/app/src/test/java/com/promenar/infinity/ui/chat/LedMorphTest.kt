package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.chat.LedMorphTimeline.Stage
import org.junit.Test

class LedMorphTest {

    @Test
    fun `时间轴依次为字标、变形、环、聚回并循环`() {
        assertThat(LedMorphTimeline.stageAt(0f)).isEqualTo(Stage.WORD)
        assertThat(LedMorphTimeline.stageAt(3.19f)).isEqualTo(Stage.WORD)
        assertThat(LedMorphTimeline.stageAt(3.2f)).isEqualTo(Stage.MORPH)
        assertThat(LedMorphTimeline.stageAt(5.0f)).isEqualTo(Stage.RING)
        assertThat(LedMorphTimeline.stageAt(10.49f)).isEqualTo(Stage.RING)
        assertThat(LedMorphTimeline.stageAt(10.5f)).isEqualTo(Stage.GATHER)
        assertThat(LedMorphTimeline.Period).isWithin(1e-4f).of(12.3f)
        assertThat(LedMorphTimeline.stageAt(12.3f + 1f)).isEqualTo(Stage.WORD)
        assertThat(LedMorphTimeline.progress(0f)).isEqualTo(0f)
        assertThat(LedMorphTimeline.progress(3.2f + 0.9f)).isWithin(1e-4f).of(0.5f)
        assertThat(LedMorphTimeline.progress(7f)).isEqualTo(1f)
        assertThat(LedMorphTimeline.progress(10.5f + 0.9f)).isWithin(1e-4f).of(0.5f)
    }

    @Test
    fun `停留阶段所有灯珠都在同一形态，阶段交界处连续`() {
        for (rank in listOf(0f, 0.3f, 1f)) {
            assertThat(LedMorphTimeline.beadProgress(0f, gathering = false, rank)).isEqualTo(0f)
            assertThat(LedMorphTimeline.beadProgress(1f, gathering = false, rank)).isEqualTo(1f)
            assertThat(LedMorphTimeline.beadProgress(1f, gathering = true, rank)).isEqualTo(1f)
            assertThat(LedMorphTimeline.beadProgress(0f, gathering = true, rank)).isEqualTo(0f)
        }
    }

    @Test
    fun `按横向位置错开出发：变形时左侧先飞，聚回时左侧先落回`() {
        // 整体进度 0.3 时最右一颗还没出发（0.3 × 1.55 < 0.55）。
        val left = LedMorphTimeline.beadProgress(0.3f, gathering = false, rank = 0f)
        val right = LedMorphTimeline.beadProgress(0.3f, gathering = false, rank = 1f)
        assertThat(left).isGreaterThan(right)
        assertThat(right).isEqualTo(0f)
        // 聚回时进度从 1 往 0 走，左侧的形态先回到 0。
        val leftBack = LedMorphTimeline.beadProgress(0.4f, gathering = true, rank = 0f)
        val rightBack = LedMorphTimeline.beadProgress(0.4f, gathering = true, rank = 1f)
        assertThat(leftBack).isLessThan(rightBack)
    }

    @Test
    fun `点按在停留阶段跳到下一段变形，变形途中不打断`() {
        assertThat(LedMorphTimeline.stageAt(LedMorphTimeline.skip(1f))).isEqualTo(Stage.MORPH)
        assertThat(LedMorphTimeline.skip(1f)).isWithin(1e-4f).of(3.2f)
        assertThat(LedMorphTimeline.stageAt(LedMorphTimeline.skip(6f))).isEqualTo(Stage.GATHER)
        assertThat(LedMorphTimeline.skip(4f)).isEqualTo(4f)
        assertThat(LedMorphTimeline.skip(11f)).isEqualTo(11f)
        // 第二圈同样按圈内位置跳。
        assertThat(LedMorphTimeline.skip(12.3f + 1f)).isWithin(1e-3f).of(12.3f + 3.2f)
    }

    @Test
    fun `两种形态按横向名次一一配对`() {
        val word = floatArrayOf(30f, 10f, 20f, 40f)
        val ring = floatArrayOf(-5f, 50f, 0f, 7f)
        val pairs = pairByHorizontalOrder(word, ring)
        // 最左的字标灯珠（10）配最左的环上位置（-5），依此类推。
        assertThat(pairs.toList()).containsExactly(3, 0, 2, 1).inOrder()
        assertThat(pairs.toSet()).hasSize(4)
        assertThat(horizontalOrder(word).toList()).containsExactly(2, 0, 1, 3).inOrder()
    }

    @Test
    fun `环上灯珠数与字标一致，排成 7 列`() {
        val ring = ledMorphRingLayout(15)
        assertThat(ring.size).isEqualTo(30)
        val lanes = (0 until 15).map { ring[it * 2 + 1] }.toSet()
        assertThat(lanes.size).isAtMost(7)
        assertThat(lanes.min()).isEqualTo(-1f)
        assertThat(lanes.max()).isEqualTo(1f)
        assertThat(ledMorphRingLayout(0).size).isEqualTo(0)
    }
}
