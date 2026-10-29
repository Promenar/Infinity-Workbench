package com.promenar.infinity.ui.chat

import com.promenar.infinity.ui.common.motion.MobiusProjector
import com.promenar.infinity.ui.common.motion.Tau
import com.promenar.infinity.ui.common.motion.clamp01
import com.promenar.infinity.ui.common.motion.motionHash
import kotlin.math.ceil
import kotlin.math.pow

/**
 * 灯珠变形的时间轴：字标停 [WordSeconds] → [MorphSeconds] 变形成环 → 环停 [RingSeconds] → [GatherSeconds] 聚回字标，循环。
 * 时钟单位为秒，只在动画开启时推进；时钟为 0 时停在字标。
 */
internal object LedMorphTimeline {
    const val WordSeconds = 3.2f
    const val MorphSeconds = 1.8f
    const val RingSeconds = 5.5f
    const val GatherSeconds = 1.8f
    const val Period = WordSeconds + MorphSeconds + RingSeconds + GatherSeconds

    /** 错开出发的比例：最右一颗比最左一颗晚出发整体进度的这一比例。 */
    const val Stagger = 0.55f

    enum class Stage { WORD, MORPH, RING, GATHER }

    private fun cycle(clock: Float): Float = clock.mod(Period)

    fun stageAt(clock: Float): Stage {
        val c = cycle(clock)
        return when {
            c < WordSeconds -> Stage.WORD
            c < WordSeconds + MorphSeconds -> Stage.MORPH
            c < WordSeconds + MorphSeconds + RingSeconds -> Stage.RING
            else -> Stage.GATHER
        }
    }

    /** 整体形态 0（字标）..1（环），变形与聚回两段内线性变化。 */
    fun progress(clock: Float): Float {
        val c = cycle(clock)
        return when (stageAt(clock)) {
            Stage.WORD -> 0f
            Stage.MORPH -> (c - WordSeconds) / MorphSeconds
            Stage.RING -> 1f
            Stage.GATHER -> 1 - (c - WordSeconds - MorphSeconds - RingSeconds) / GatherSeconds
        }
    }

    /**
     * 一颗灯珠的形态 0..1：按横向排名 [rank]（0 最左、1 最右）错开出发，再做缓入缓出。
     * 变形时左侧先飞，聚回时同样左侧先落回字标。
     */
    fun beadProgress(progress: Float, gathering: Boolean, rank: Float): Float {
        val delay = Stagger * if (gathering) 1 - rank else rank
        return easeInOutCubic(clamp01(progress * (1 + Stagger) - delay))
    }

    /** 点按：停在字标或环上时立即开始下一段变形；正在变形时不打断。返回新的时钟。 */
    fun skip(clock: Float): Float {
        val c = cycle(clock)
        return when (stageAt(clock)) {
            Stage.WORD -> clock + (WordSeconds - c)
            Stage.RING -> clock + (WordSeconds + MorphSeconds + RingSeconds - c)
            else -> clock
        }
    }
}

internal fun easeInOutCubic(x: Float): Float = if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).pow(3) / 2

/** 按横向位置从左到右排名；位置相同时保持原顺序。返回各项的名次（0 起）。 */
internal fun horizontalOrder(x: FloatArray): IntArray {
    val sorted = x.indices.sortedBy { x[it] }
    val rank = IntArray(x.size)
    sorted.forEachIndexed { order, index -> rank[index] = order }
    return rank
}

/**
 * 两种形态一一配对：两边灯珠数相同，各自按横向位置排名，名次相同的配成一对。
 * 返回 [fromX] 每一项对应的 [toX] 下标。
 */
internal fun pairByHorizontalOrder(fromX: FloatArray, toX: FloatArray): IntArray {
    require(fromX.size == toX.size) { "两种形态的灯珠数必须一致" }
    val fromRank = horizontalOrder(fromX)
    val byRank = IntArray(toX.size)
    horizontalOrder(toX).forEachIndexed { index, order -> byRank[order] = index }
    return IntArray(fromX.size) { byRank[fromRank[it]] }
}

/** 环上的灯珠排布：带面上 [lanes] 列，沿 u 均匀排满 [count] 颗；返回 u 与单位带宽坐标 v（−1..1）交替的数组。 */
internal fun ledMorphRingLayout(count: Int, lanes: Int = 7): FloatArray {
    val out = FloatArray(count * 2)
    if (count == 0) return out
    val columns = ceil(count / lanes.toFloat()).toInt()
    var n = 0
    loop@ for (i in 0 until columns) for (j in 0 until lanes) {
        if (n >= count) break@loop
        out[n * 2] = i.toFloat() / columns * Tau
        out[n * 2 + 1] = -1 + 2f * j / (lanes - 1)
        n++
    }
    return out
}

/**
 * 灯珠变形的灯珠集：字标点阵上的每颗灯珠配一个环上的位置。字标坐标按栅格化时的位置记录（[WordmarkMask.centerY] 所在处），
 * 绘制时整体平移到当前中心。只在字标栅格或画布宽度变化时重建。
 */
internal class LedMorphBeads(val mask: WordmarkMask, val width: Float, val count: Int) {
    val wordX = FloatArray(count)
    val wordY = FloatArray(count)
    val column = IntArray(count)
    val id = IntArray(count)
    val ringU = FloatArray(count)
    val ringV = FloatArray(count)
    val rank = FloatArray(count)
    val angle = FloatArray(count)
}

/**
 * 按灯珠字标同一套栅格（[pitch] 间距、居中对齐）取出字标灯珠，生成同样数量的环上位置，
 * 两边都按默认视角下的横向位置排序后一一配对。
 */
internal fun buildLedMorphBeads(mask: WordmarkMask, width: Float, height: Float, density: Float, pitch: Float, radius: Float): LedMorphBeads {
    val cols = (width / pitch).toInt()
    val rows = (height / pitch).toInt()
    val ox = (width - cols * pitch) / 2 + pitch / 2
    val oy = (height - rows * pitch) / 2 + pitch / 2
    val xs = ArrayList<Float>()
    val ys = ArrayList<Float>()
    val cs = ArrayList<Int>()
    val ids = ArrayList<Int>()
    for (r in 0 until rows) for (c in 0 until cols) {
        val x = ox + c * pitch
        val y = oy + r * pitch
        if (mask.at(x / density, y / density) <= 100) continue
        xs += x
        ys += y
        cs += c
        ids += r * cols + c
    }
    val beads = LedMorphBeads(mask, width, xs.size)
    val ring = ledMorphRingLayout(beads.count)
    val projector = MobiusProjector().configure(radius = radius, cx = width / 2, cy = 0f, yaw = 0f, pitch = 0.78f)
    val point = FloatArray(3)
    val ringX = FloatArray(beads.count) {
        projector.project(ring[it * 2], ring[it * 2 + 1] * projector.halfWidth, point)
        point[0]
    }
    val wordX = FloatArray(beads.count) { xs[it] }
    val pairs = pairByHorizontalOrder(wordX, ringX)
    val order = horizontalOrder(wordX)
    val last = (beads.count - 1).coerceAtLeast(1)
    for (i in 0 until beads.count) {
        beads.wordX[i] = xs[i]
        beads.wordY[i] = ys[i]
        beads.column[i] = cs[i]
        beads.id[i] = ids[i]
        beads.ringU[i] = ring[pairs[i] * 2]
        beads.ringV[i] = ring[pairs[i] * 2 + 1]
        beads.rank[i] = order[i].toFloat() / last
        beads.angle[i] = motionHash(ids[i].toFloat(), 7f) * Tau
    }
    return beads
}
