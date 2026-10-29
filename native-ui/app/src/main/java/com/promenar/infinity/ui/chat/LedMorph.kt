package com.promenar.infinity.ui.chat

import com.promenar.infinity.ui.common.motion.MobiusProjector
import com.promenar.infinity.ui.common.motion.Tau
import com.promenar.infinity.ui.common.motion.clamp01
import com.promenar.infinity.ui.common.motion.motionHash
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

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

/** 灯珠环带面上的列数（沿宽度方向的灯珠数）。灯珠环与灯珠变形共用。 */
internal const val MobiusLedLanes = 7

/** 灯珠环沿 u 方向的灯珠列数，[particleScale] 为设置页小预览的缩减比例。 */
internal fun mobiusLedColumns(particleScale: Float): Int = (170 * particleScale).toInt().coerceAtLeast(60)

/** 灯珠环第 [column] 列的 u（0..2π）。 */
internal fun mobiusLedU(column: Int, columns: Int): Float = column.toFloat() / columns * Tau

/** 灯珠环第 [lane] 行的单位带宽坐标 v（−1..1）。 */
internal fun mobiusLedV(lane: Int): Float = -1 + 2f * lane / (MobiusLedLanes - 1)

/** 字标星尘：dp 坐标，x、y 分开存放；[spacing] 为取点时要求的最小间距（dp）。 */
internal class WordmarkStardust(val x: FloatArray, val y: FloatArray, val spacing: Float)

/**
 * 在字形内撒 [count] 个近似蓝噪声的点：先在 [WordmarkMask] 上按 0.5dp 步长取透明度高于 [threshold] 的候选点（格内随机抖动），
 * 按固定种子洗牌后以最小间距 √(候选面积 / count) × 0.82 贪心接受，空间哈希网格加速；凑不满时从剩余候选点里按洗牌顺序补齐。
 * 同一输入结果确定。
 */
internal fun sampleWordmarkStardust(mask: WordmarkMask, count: Int, seed: Int = 11, threshold: Int = 110): WordmarkStardust {
    val step = 0.5f
    // 先找笔画外接框，只在框内取候选点。
    var minX = mask.width
    var minY = mask.height
    var maxX = -1
    var maxY = -1
    for (row in 0 until mask.height) for (column in 0 until mask.width) {
        if ((mask.alpha[row * mask.width + column].toInt() and 0xFF) <= threshold) continue
        if (column < minX) minX = column
        if (column > maxX) maxX = column
        if (row < minY) minY = row
        if (row > maxY) maxY = row
    }
    val xs = FloatArray(count)
    val ys = FloatArray(count)
    if (maxX < 0 || count == 0) {
        xs.fill(mask.width / 2f)
        ys.fill(mask.height / 2f)
        return WordmarkStardust(xs, ys, 0f)
    }
    val random = Random(seed)
    val cellsX = (maxX - minX + 1) * 2
    val cellsY = (maxY - minY + 1) * 2
    val cx = FloatArray(cellsX * cellsY)
    val cy = FloatArray(cellsX * cellsY)
    var candidates = 0
    for (row in 0 until cellsY) for (column in 0 until cellsX) {
        val x = minX + (column + random.nextFloat()) * step
        val y = minY + (row + random.nextFloat()) * step
        if (mask.at(x, y) <= threshold) continue
        cx[candidates] = x
        cy[candidates] = y
        candidates++
    }
    val order = IntArray(candidates) { it }
    for (i in candidates - 1 downTo 1) {
        val j = random.nextInt(i + 1)
        val swap = order[i]
        order[i] = order[j]
        order[j] = swap
    }
    val spacing = sqrt(candidates * step * step / count) * 0.82f
    // 网格边长 spacing/√2，每格至多一个已接受点，查周围 5×5 格即可。
    val cell = (spacing / sqrt(2f)).coerceAtLeast(1e-3f)
    val gridW = ((maxX - minX + 1) / cell).toInt() + 1
    val gridH = ((maxY - minY + 1) / cell).toInt() + 1
    val grid = IntArray(gridW * gridH) { -1 }
    val accepted = BooleanArray(candidates)
    val limit = spacing * spacing
    var n = 0
    for (index in order) {
        if (n >= count) break
        val x = cx[index]
        val y = cy[index]
        val gx = ((x - minX) / cell).toInt().coerceIn(0, gridW - 1)
        val gy = ((y - minY) / cell).toInt().coerceIn(0, gridH - 1)
        var free = true
        search@ for (ny in maxOf(0, gy - 2)..minOf(gridH - 1, gy + 2)) for (nx in maxOf(0, gx - 2)..minOf(gridW - 1, gx + 2)) {
            val other = grid[ny * gridW + nx]
            if (other < 0) continue
            val dx = xs[other] - x
            val dy = ys[other] - y
            if (dx * dx + dy * dy < limit) {
                free = false
                break@search
            }
        }
        if (!free) continue
        grid[gy * gridW + gx] = n
        accepted[index] = true
        xs[n] = x
        ys[n] = y
        n++
    }
    // 凑不满时按洗牌顺序补上未接受的候选点；候选点本身不够时循环复用。
    var cursor = 0
    while (n < count && candidates > 0) {
        val index = order[cursor % candidates]
        if (cursor >= candidates || !accepted[index]) {
            xs[n] = cx[index]
            ys[n] = cy[index]
            n++
        }
        cursor++
    }
    return WordmarkStardust(xs, ys, spacing)
}

/**
 * 灯珠变形的灯珠集：灯珠数与灯珠环相同，每颗灯珠带着它在环上的编号与位置（与灯珠环同一公式），再配一个字标星尘点。
 * 字标坐标（像素）按栅格化时的位置记录（[WordmarkMask.centerY] 所在处），绘制时整体平移到当前中心。只在字标栅格或画布宽度变化时重建。
 */
internal class LedMorphBeads(val mask: WordmarkMask, val width: Float, val count: Int) {
    val wordX = FloatArray(count)
    val wordY = FloatArray(count)
    val id = IntArray(count)
    val ringU = FloatArray(count)
    val ringV = FloatArray(count)
    val rank = FloatArray(count)
    val angle = FloatArray(count)
    /** 字标形态下的亮度与尺寸差异，取 motionHash(id, 5.3)。 */
    val dw = FloatArray(count)
}

/**
 * 按灯珠环的排布（[columns] 列 × [MobiusLedLanes] 行）生成环上位置，在字形内撒同样数量的星尘点，
 * 两边都按默认视角（yaw 0、pitch 0.78、半径 [radius]）下的横向位置排序后一一配对。
 */
internal fun buildLedMorphBeads(mask: WordmarkMask, width: Float, density: Float, columns: Int, radius: Float): LedMorphBeads {
    val count = columns * MobiusLedLanes
    val beads = LedMorphBeads(mask, width, count)
    val dust = sampleWordmarkStardust(mask, count)
    val projector = MobiusProjector().configure(radius = radius, cx = width / 2, cy = 0f, yaw = 0f, pitch = 0.78f)
    val point = FloatArray(3)
    val ringX = FloatArray(count) { id ->
        projector.project(mobiusLedU(id / MobiusLedLanes, columns), mobiusLedV(id % MobiusLedLanes) * projector.halfWidth, point)
        point[0]
    }
    val wordX = FloatArray(count) { dust.x[it] * density }
    val pairs = pairByHorizontalOrder(wordX, ringX)
    val order = horizontalOrder(wordX)
    val last = (count - 1).coerceAtLeast(1)
    for (i in 0 until count) {
        val id = pairs[i]
        beads.wordX[i] = wordX[i]
        beads.wordY[i] = dust.y[i] * density
        beads.id[i] = id
        beads.ringU[i] = mobiusLedU(id / MobiusLedLanes, columns)
        beads.ringV[i] = mobiusLedV(id % MobiusLedLanes)
        beads.rank[i] = order[i].toFloat() / last
        beads.angle[i] = motionHash(id.toFloat(), 7f) * Tau
        beads.dw[i] = motionHash(id.toFloat(), 5.3f)
    }
    return beads
}
