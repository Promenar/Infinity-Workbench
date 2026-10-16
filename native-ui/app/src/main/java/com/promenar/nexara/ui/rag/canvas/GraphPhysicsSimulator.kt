package com.promenar.nexara.ui.rag.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.promenar.nexara.ui.rag.GraphEdge
import com.promenar.nexara.ui.rag.GraphNode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/** 一帧中的节点不可变，后台计算不会修改画布正在读取的坐标。 */
data class SimNode(
    val id: String,
    val label: String,
    val type: String,
    val x: Float,
    val y: Float,
    val vx: Float = 0f,
    val vy: Float = 0f,
    val isPinned: Boolean = false,
)

private data class GraphSimulationFrame(val nodes: List<SimNode>) {
    val nodesById: Map<String, SimNode> = nodes.associateBy { it.id }
}

/** 生命周期控制与主线程帧提交；布局计算交给 Default 调度器。 */
class GraphPhysicsSimulator(
    private val scope: CoroutineScope,
    kr: Float = 8_000f,
    ka: Float = 0.025f,
    l0: Float = 150f,
    kg: Float = 0.003f,
    friction: Float = 0.85f,
    private val computationDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val seed: Int = 20261017,
) {
    private val engine = GraphLayoutEngine(kr, ka, l0, kg, friction)
    private var frame by mutableStateOf(GraphSimulationFrame(emptyList()))
    val nodesState: List<SimNode> get() = frame.nodes
    val nodesById: Map<String, SimNode> get() = frame.nodesById

    var datasetVersion by mutableStateOf(0L)
        private set
    var isSimulating by mutableStateOf(false)
        private set
    var isVisible by mutableStateOf(true)
        private set

    private var edgesList: List<GraphEdge> = emptyList()
    private var simulationJob: Job? = null
    private var generation = 0L
    private var settled = false

    fun setData(newNodes: List<GraphNode>, newEdges: List<GraphEdge>) {
        stopSimulation()
        val existing = nodesById
        val radius = max(180f, sqrt(newNodes.size.toFloat()) * 45f)
        frame = GraphSimulationFrame(newNodes.distinctBy { it.id }.map { node ->
            val old = existing[node.id]
            val random = Random(seed xor node.id.hashCode())
            SimNode(node.id, node.label, node.type,
                old?.x ?: (random.nextFloat() * 2f - 1f) * radius,
                old?.y ?: (random.nextFloat() * 2f - 1f) * radius,
                old?.vx ?: 0f, old?.vy ?: 0f, old?.isPinned ?: false)
        })
        edgesList = newEdges
        datasetVersion++
        settled = newNodes.isEmpty()
        wakeUp()
    }

    fun setPageVisible(visible: Boolean) {
        if (isVisible == visible) return
        isVisible = visible
        if (visible) {
            if (!settled) wakeUp()
        } else {
            stopSimulation()
        }
    }

    fun wakeUp() {
        if (!isVisible || isSimulating || nodesState.isEmpty()) return
        settled = false
        isSimulating = true
        val expectedGeneration = generation
        simulationJob = scope.launch {
            var stableFrames = 0
            var iteration = 0
            try {
                while (isVisible && expectedGeneration == generation) {
                    val current = nodesState
                    val edges = edgesList
                    val temperature = 0.985f.pow(iteration)
                    val next = withContext(computationDispatcher) { engine.step(current, edges, temperature) }
                    ensureActive()
                    if (expectedGeneration != generation) return@launch
                    frame = GraphSimulationFrame(next.nodes)
                    iteration++
                    stableFrames = if (next.maxVelocity < 0.06f) stableFrames + 1 else 0
                    if (stableFrames >= 40 || iteration >= 900) {
                        settled = true
                        break
                    }
                    delay(16)
                }
            } finally {
                if (expectedGeneration == generation) {
                    isSimulating = false
                    simulationJob = null
                }
            }
        }
    }

    fun startDragging(nodeId: String) = updateNode(nodeId) { it.copy(isPinned = true, vx = 0f, vy = 0f) }

    fun updateDragPosition(nodeId: String, newPos: Offset) {
        if (!newPos.x.isFinite() || !newPos.y.isFinite()) return
        updateNode(nodeId) { it.copy(x = newPos.x, y = newPos.y) }
    }

    fun stopDragging(nodeId: String) = updateNode(nodeId) { it.copy(isPinned = false) }

    private fun updateNode(nodeId: String, update: (SimNode) -> SimNode) {
        if (nodeId !in nodesById) return
        stopSimulation()
        frame = GraphSimulationFrame(nodesState.map { if (it.id == nodeId) update(it) else it })
        settled = false
        wakeUp()
    }

    private fun stopSimulation() {
        generation++
        simulationJob?.cancel()
        simulationJob = null
        isSimulating = false
    }

    fun clear() {
        isVisible = false
        stopSimulation()
    }
}
