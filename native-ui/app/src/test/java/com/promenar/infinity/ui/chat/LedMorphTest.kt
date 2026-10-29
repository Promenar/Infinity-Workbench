package com.promenar.infinity.ui.chat

import com.google.common.truth.Truth.assertThat
import com.promenar.infinity.ui.chat.LedMorphTimeline.Stage
import com.promenar.infinity.ui.common.motion.MobiusProjector
import com.promenar.infinity.ui.common.motion.Tau
import kotlin.math.hypot
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
    fun `环上排布与灯珠环同一公式：id = 列 × 7 + 行`() {
        val columns = mobiusLedColumns(1f)
        assertThat(columns).isEqualTo(170)
        assertThat(mobiusLedColumns(0.35f)).isEqualTo(60)
        assertThat(mobiusLedU(0, columns)).isEqualTo(0f)
        assertThat(mobiusLedU(85, columns)).isWithin(1e-5f).of(Tau / 2)
        assertThat((0 until MobiusLedLanes).map { mobiusLedV(it) }).isEqualTo((0 until 7).map { -1 + 2f * it / 6 })

        val beads = buildLedMorphBeads(testMask(), width = 900f, density = 3f, columns = columns, radius = 120f * 3)
        assertThat(beads.count).isEqualTo(columns * MobiusLedLanes)
        // 一一配对：每个环上编号恰好出现一次。
        assertThat(beads.id.toSet()).isEqualTo((0 until beads.count).toSet())
        for (i in 0 until beads.count) {
            val id = beads.id[i]
            assertThat(beads.ringU[i]).isEqualTo(mobiusLedU(id / MobiusLedLanes, columns))
            assertThat(beads.ringV[i]).isEqualTo(mobiusLedV(id % MobiusLedLanes))
        }
        // 名次 0..1 覆盖两端，各不相同。
        assertThat(beads.rank.toSet()).hasSize(beads.count)
        assertThat(beads.rank.min()).isEqualTo(0f)
        assertThat(beads.rank.max()).isEqualTo(1f)
    }

    @Test
    fun `配对后字标最左的灯珠落到环上最左的位置`() {
        val columns = 60
        val radius = 360f
        val beads = buildLedMorphBeads(testMask(), width = 900f, density = 3f, columns = columns, radius = radius)
        val projector = MobiusProjector().configure(radius = radius, cx = 450f, cy = 0f, yaw = 0f, pitch = 0.78f)
        val point = FloatArray(3)
        val ringX = FloatArray(beads.count) {
            projector.project(beads.ringU[it], beads.ringV[it] * projector.halfWidth, point)
            point[0]
        }
        val leftWord = beads.wordX.indices.minBy { beads.wordX[it] }
        val rightWord = beads.wordX.indices.maxBy { beads.wordX[it] }
        assertThat(ringX[leftWord]).isEqualTo(ringX.min())
        assertThat(ringX[rightWord]).isEqualTo(ringX.max())
    }

    @Test
    fun `星尘取点数量正好、结果确定且都落在字形内`() {
        val mask = testMask()
        for (count in listOf(420, 1190)) {
            val dust = sampleWordmarkStardust(mask, count)
            assertThat(dust.x.size).isEqualTo(count)
            assertThat(dust.y.size).isEqualTo(count)
            for (i in 0 until count) assertThat(mask.at(dust.x[i], dust.y[i])).isGreaterThan(110)
            val again = sampleWordmarkStardust(mask, count)
            assertThat(again.x.toList()).isEqualTo(dust.x.toList())
            assertThat(again.y.toList()).isEqualTo(dust.y.toList())
        }
    }

    @Test
    fun `星尘点之间大多满足最小间距，不扎堆`() {
        // 点数少时贪心阶段就能取满，所有点对都满足最小间距。
        assertThat(closePairs(sampleWordmarkStardust(testMask(), 420))).isEqualTo(0)
        // 点数多时贪心接近饱和，补齐的点会贴近一两颗已有点；七十万对里过近的不到 N/5 对。
        assertThat(closePairs(sampleWordmarkStardust(testMask(), 1190))).isLessThan(1190 / 5)
    }

    /** 间距小于 [WordmarkStardust.spacing] 的点对数。 */
    private fun closePairs(dust: WordmarkStardust): Int {
        assertThat(dust.spacing).isGreaterThan(0f)
        var close = 0
        for (i in dust.x.indices) for (j in i + 1 until dust.x.size) {
            if (hypot(dust.x[i] - dust.x[j], dust.y[i] - dust.y[j]) < dust.spacing - 1e-4f) close++
        }
        return close
    }

    @Test
    fun `空字标也返回同样数量的点`() {
        val empty = WordmarkMask(ByteArray(40 * 20), 40, 20, 30f)
        assertThat(sampleWordmarkStardust(empty, 70).x.size).isEqualTo(70)
    }

    /** 300×100dp 的栅格，画两笔：一横与一竖，模拟字标笔画。 */
    private fun testMask(): WordmarkMask {
        val width = 300
        val height = 100
        val alpha = ByteArray(width * height)
        fun fill(x0: Int, y0: Int, x1: Int, y1: Int, value: Int) {
            for (y in y0 until y1) for (x in x0 until x1) alpha[y * width + x] = value.toByte()
        }
        fill(20, 40, 280, 52, 255)
        fill(140, 10, 152, 90, 255)
        // 边缘半透明一圈，阈值以下的像素不应取点。
        fill(20, 52, 280, 54, 90)
        return WordmarkMask(alpha, width, height, centerY = 150f)
    }
}
