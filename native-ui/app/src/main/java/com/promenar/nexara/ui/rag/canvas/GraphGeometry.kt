package com.promenar.nexara.ui.rag.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.max
import kotlin.math.min

data class GraphViewportTransform(val scale: Float = 1f, val offset: Offset = Offset.Zero)

/** 通过线段参数区间裁剪，保留两端在视口外但穿过屏幕的关系线。 */
internal fun segmentIntersectsRect(start: Offset, end: Offset, rect: Rect): Boolean {
    if (!start.x.isFinite() || !start.y.isFinite() || !end.x.isFinite() || !end.y.isFinite()) return false
    var enter = 0f
    var leave = 1f
    fun intersect(origin: Float, delta: Float, lower: Float, upper: Float): Boolean {
        if (delta == 0f) return origin in lower..upper
        val first = (lower - origin) / delta
        val second = (upper - origin) / delta
        enter = max(enter, min(first, second))
        leave = min(leave, max(first, second))
        return enter <= leave
    }
    return intersect(start.x, end.x - start.x, rect.left, rect.right) &&
        intersect(start.y, end.y - start.y, rect.top, rect.bottom)
}

internal fun fitGraphToViewport(
    points: List<Offset>,
    width: Float,
    height: Float,
    padding: Float = 48f,
): GraphViewportTransform {
    if (width <= 0f || height <= 0f || points.isEmpty()) return GraphViewportTransform()
    val finite = points.filter { it.x.isFinite() && it.y.isFinite() }
    if (finite.isEmpty()) return GraphViewportTransform()
    val minX = finite.minOf { it.x }
    val maxX = finite.maxOf { it.x }
    val minY = finite.minOf { it.y }
    val maxY = finite.maxOf { it.y }
    val horizontal = max(width - 2f * padding, 1f) / max(maxX - minX, 1f)
    val vertical = max(height - 2f * padding, 1f) / max(maxY - minY, 1f)
    val scale = min(horizontal, vertical).coerceAtMost(1.5f).coerceAtLeast(0.001f)
    val center = Offset((minX + maxX) / 2f, (minY + maxY) / 2f)
    return GraphViewportTransform(scale, Offset(width / 2f, height / 2f) - center * scale)
}
