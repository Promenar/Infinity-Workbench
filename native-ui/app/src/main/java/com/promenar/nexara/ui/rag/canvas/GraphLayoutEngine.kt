package com.promenar.nexara.ui.rag.canvas

import com.promenar.nexara.ui.rag.GraphEdge
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

data class GraphLayoutStep(
    val nodes: List<SimNode>,
    val maxVelocity: Float,
    val repulsionEvaluations: Long,
)

/** 纯计算的 Barnes-Hut 布局；四叉树聚合远场斥力，预期复杂度为 O(V log V + E)。 */
class GraphLayoutEngine(
    private val kr: Float = 8_000f,
    private val ka: Float = 0.025f,
    private val l0: Float = 150f,
    private val kg: Float = 0.003f,
    private val friction: Float = 0.85f,
    private val theta: Float = 0.7f,
) {
    fun step(nodes: List<SimNode>, edges: List<GraphEdge>, temperature: Float = 1f): GraphLayoutStep {
        if (nodes.isEmpty()) return GraphLayoutStep(emptyList(), 0f, 0)
        val count = nodes.size
        val forceX = FloatArray(count)
        val forceY = FloatArray(count)
        val minX = nodes.minOf { it.x }
        val minY = nodes.minOf { it.y }
        val span = max(max(nodes.maxOf { it.x } - minX, nodes.maxOf { it.y } - minY), 1f) + 0.01f
        val root = Cell(minX - 0.005f, minY - 0.005f, span, 0)
        val leafByNode = arrayOfNulls<Cell>(count)
        nodes.indices.forEach { root.insert(it, nodes, leafByNode) }
        var evaluations = 0L
        nodes.forEachIndexed { index, node ->
            fun accumulate(cell: Cell) {
                if (cell.mass == 0) return
                val contains = node.x >= cell.left && node.x <= cell.left + cell.size &&
                    node.y >= cell.top && node.y <= cell.top + cell.size
                val ownsPoint = leafByNode[index] === cell
                val mass = cell.mass - if (ownsPoint) 1 else 0
                if (mass <= 0) return
                val centerX = ((cell.sumX - if (ownsPoint) node.x.toDouble() else 0.0) / mass).toFloat()
                val centerY = ((cell.sumY - if (ownsPoint) node.y.toDouble() else 0.0) / mass).toFloat()
                var dx = node.x - centerX
                var dy = node.y - centerY
                val distanceSquared = dx * dx + dy * dy
                if (cell.children == null || (!contains && cell.size * cell.size < theta * theta * distanceSquared)) {
                    if (distanceSquared < 0.0001f) {
                        // 重合叶桶以实体身份决定分离方向，不产生随机漂移或无限细分。
                        val angle = ((node.id.hashCode().toLong() and 0xffffL).toFloat() / 65_536f) * 6.2831855f
                        dx = cos(angle)
                        dy = sin(angle)
                    }
                    val force = kr * mass / max(dx * dx + dy * dy, 1f)
                    forceX[index] += dx * force
                    forceY[index] += dy * force
                    evaluations++
                } else {
                    cell.children?.forEach { child -> child?.let { accumulate(it) } }
                }
            }
            accumulate(root)
        }
        val indexById = nodes.withIndex().associate { it.value.id to it.index }
        edges.forEach { edge ->
            val source = indexById[edge.sourceId] ?: return@forEach
            val target = indexById[edge.targetId] ?: return@forEach
            val dx = nodes[target].x - nodes[source].x
            val dy = nodes[target].y - nodes[source].y
            val distance = max(sqrt(dx * dx + dy * dy), 1f)
            val force = ka * (distance - l0) / distance
            forceX[source] += dx * force
            forceY[source] += dy * force
            forceX[target] -= dx * force
            forceY[target] -= dy * force
        }
        var maxVelocity = 0f
        val cooled = temperature.coerceIn(0f, 1f)
        val result = nodes.mapIndexed { index, node ->
            if (node.isPinned) return@mapIndexed node.copy(vx = 0f, vy = 0f)
            var vx = (node.vx + (forceX[index] - node.x * kg) * cooled) * friction
            var vy = (node.vy + (forceY[index] - node.y * kg) * cooled) * friction
            val speed = sqrt(vx * vx + vy * vy)
            if (speed > 40f) {
                vx *= 40f / speed
                vy *= 40f / speed
            }
            maxVelocity = max(maxVelocity, sqrt(vx * vx + vy * vy))
            node.copy(x = node.x + vx, y = node.y + vy, vx = vx, vy = vy)
        }
        return GraphLayoutStep(result, maxVelocity, evaluations)
    }

    private class Cell(val left: Float, val top: Float, val size: Float, val depth: Int) {
        var mass = 0
        var sumX = 0.0
        var sumY = 0.0
        var children: Array<Cell?>? = null
        private val bucket = mutableListOf<Int>()

        fun insert(index: Int, nodes: List<SimNode>, leafByNode: Array<Cell?>) {
            val node = nodes[index]
            mass++
            sumX += node.x
            sumY += node.y
            if (children == null && (bucket.isEmpty() || depth >= 24 || size <= 0.01f)) {
                bucket.add(index)
                leafByNode[index] = this
                return
            }
            if (children == null) {
                children = arrayOfNulls(4)
                bucket.forEach { insertIntoChild(it, nodes, leafByNode) }
                bucket.clear()
            }
            insertIntoChild(index, nodes, leafByNode)
        }

        private fun insertIntoChild(index: Int, nodes: List<SimNode>, leafByNode: Array<Cell?>) {
            val node = nodes[index]
            val half = size / 2f
            val right = node.x >= left + half
            val bottom = node.y >= top + half
            val quadrant = (if (right) 1 else 0) + if (bottom) 2 else 0
            val cells = requireNotNull(children)
            val cell = cells[quadrant] ?: Cell(left + if (right) half else 0f, top + if (bottom) half else 0f, half, depth + 1)
                .also { cells[quadrant] = it }
            cell.insert(index, nodes, leafByNode)
        }
    }
}
