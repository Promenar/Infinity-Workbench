package com.promenar.nexara.ui.rag.canvas

import androidx.compose.ui.geometry.Offset
import com.promenar.nexara.ui.rag.GraphEdge
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 画布的高亮输入：选中实体时它与一跳邻居保持全亮；会话视角选中某轮时该轮实体全亮；
 * 类型筛选非空时只亮所选类型。其余实体与连线淡出到背景。
 */
data class KgCanvasHighlight(
    val selectedId: String? = null,
    /** 选中某一轮时该轮的实体；为 null 表示不按轮次聚焦。 */
    val turnNodeIds: Set<String>? = null,
    /** 本轮首次出现的实体，画“新”角标。 */
    val newNodeIds: Set<String> = emptySet(),
    val typeFilter: Set<KgEntityKind> = emptySet(),
)

/** 一帧里各实体与连线的亮暗，供绘制与测试共用。 */
internal class KgHighlightResolver(
    private val highlight: KgCanvasHighlight,
    edges: List<GraphEdge>,
    private val kindOf: (String) -> KgEntityKind?,
) {
    private val focus: Set<String>? = when {
        highlight.selectedId != null -> buildSet {
            add(highlight.selectedId)
            edges.forEach { edge ->
                if (edge.sourceId == highlight.selectedId) add(edge.targetId)
                if (edge.targetId == highlight.selectedId) add(edge.sourceId)
            }
        }
        else -> highlight.turnNodeIds
    }

    val hasFocus: Boolean get() = focus != null

    fun isLit(nodeId: String): Boolean {
        if (highlight.typeFilter.isNotEmpty() && kindOf(nodeId) !in highlight.typeFilter) return false
        return focus == null || nodeId in focus
    }

    /** 选中实体时只亮与它相连的线；否则两端都亮即亮。 */
    fun isEdgeLit(edge: GraphEdge): Boolean {
        if (!isLit(edge.sourceId) || !isLit(edge.targetId)) return false
        val selected = highlight.selectedId ?: return true
        return edge.sourceId == selected || edge.targetId == selected
    }

    /** 着色强调：有聚焦时亮起的线用起点类型色。 */
    fun isEdgeEmphasized(edge: GraphEdge): Boolean = hasFocus && isEdgeLit(edge)

    /** 标签按细节层级显示：选中、聚焦与筛选出的实体总显示，其余关系较多时显示，放大后全部显示。 */
    fun showsLabel(nodeId: String, degree: Int, scale: Float): Boolean = when {
        nodeId == highlight.selectedId -> true
        !isLit(nodeId) -> false
        focus != null -> nodeId in focus
        highlight.typeFilter.isNotEmpty() -> true
        else -> degree >= KG_LABEL_MIN_DEGREE || scale >= KG_LABEL_ALL_SCALE
    }
}

internal const val KG_LABEL_MIN_DEGREE = 3
internal const val KG_LABEL_ALL_SCALE = 0.6f
internal const val KG_HUB_MIN_DEGREE = 4

/** 节点基准半径（dp）：随关系数平方根增大，上限避免枢纽盖住邻居。 */
internal fun kgNodeRadiusDp(degree: Int): Float = min(11f + 2.6f * sqrt(degree.coerceAtLeast(0).toFloat()), 22f)

/**
 * 节点屏幕半径（px）：以 dp 为基准，只随缩放的平方根轻微变化。
 * 缩小看全图时节点仍能辨认类型图标，放大时也不会大到盖住连线。
 */
internal fun kgNodeScreenRadius(degree: Int, scale: Float, densityPx: Float): Float =
    kgNodeRadiusDp(degree) * densityPx * sqrt(scale.coerceAtLeast(0f)).coerceIn(0.75f, 1.4f)

internal fun kgDegrees(edges: List<GraphEdge>): Map<String, Int> {
    val counts = HashMap<String, Int>()
    edges.forEach { edge ->
        counts[edge.sourceId] = (counts[edge.sourceId] ?: 0) + 1
        counts[edge.targetId] = (counts[edge.targetId] ?: 0) + 1
    }
    return counts
}

/** 连线画成轻微弧线：控制点在中点的法线方向偏移线长的 12%，平行关系不重叠。 */
internal fun kgEdgeControl(start: Offset, end: Offset): Offset {
    val mid = (start + end) / 2f
    val delta = end - start
    return Offset(mid.x - delta.y * 0.12f, mid.y + delta.x * 0.12f)
}

internal fun kgQuadPoint(start: Offset, control: Offset, end: Offset, t: Float): Offset {
    val u = 1f - t
    return start * (u * u) + control * (2f * u * t) + end * (t * t)
}

/** 指向终点的小圆点位置：停在终点节点外缘稍外，表示关系方向。 */
internal fun kgEdgeArrowPoint(start: Offset, end: Offset, targetRadius: Float): Offset {
    val length = (end - start).getDistance()
    if (length <= 0f) return end
    val t = (1f - (targetRadius + 5f) / length).coerceIn(0f, 1f)
    return kgQuadPoint(start, kgEdgeControl(start, end), end, t)
}

/** 枢纽轨道上的卫星点角度，按实体 id 固定，避免每帧跳动。 */
internal fun kgOrbitAngle(nodeId: String): Float = ((nodeId.hashCode().toLong() and 0xffffL).toFloat() / 65_536f) * 6.2831855f

internal fun kgOrbitPoint(center: Offset, radius: Float, nodeId: String): Offset {
    val angle = kgOrbitAngle(nodeId)
    return Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
}

/** 按屏幕坐标命中节点：取屏幕半径加触控余量内最近的一个，保证最小触控范围。 */
internal fun kgHitTest(
    nodes: List<SimNode>,
    degrees: Map<String, Int>,
    screenPoint: Offset,
    scale: Float,
    offset: Offset,
    minTouchRadiusPx: Float,
    screenRadius: (Int) -> Float,
): SimNode? {
    var best: SimNode? = null
    var bestDistance = Float.MAX_VALUE
    nodes.forEach { node ->
        val center = Offset(node.x, node.y) * scale + offset
        val reach = maxOf(screenRadius(degrees[node.id] ?: 0) + minTouchRadiusPx / 4f, minTouchRadiusPx / 2f)
        val distance = (center - screenPoint).getDistance()
        if (distance <= reach && distance < bestDistance) {
            best = node
            bestDistance = distance
        }
    }
    return best
}
