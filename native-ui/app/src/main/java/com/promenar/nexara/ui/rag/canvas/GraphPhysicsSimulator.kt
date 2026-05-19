package com.promenar.nexara.ui.rag.canvas

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.promenar.nexara.ui.rag.GraphEdge
import com.promenar.nexara.ui.rag.GraphNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 原生力导向物理模拟器节点状态定义
 */
class SimNode(
    val id: String,
    val label: String,
    val type: String,
    x: Float,
    y: Float,
    vx: Float = 0f,
    vy: Float = 0f,
    isPinned: Boolean = false
) {
    var x by mutableStateOf(x)
    var y by mutableStateOf(y)
    var vx by mutableStateOf(vx)
    var vy by mutableStateOf(vy)
    var isPinned by mutableStateOf(isPinned)
}

/**
 * 知识图谱三体力场（库仑斥力、胡克弹簧引力、向心引力）协程仿真器
 */
class GraphPhysicsSimulator(
    private val scope: CoroutineScope,
    private val kr: Float = 8000f,      // 库仑斥力常数（配合 1/d 慢衰减场）
    private val ka: Float = 0.025f,     // 胡克弹簧拉力常数（降低张力）
    private val l0: Float = 150f,       // 关系边理想长度（像素，增至 150px 以舒展图谱）
    private val kg: Float = 0.003f,     // 向心引力常数（降至 0.003f 以允许外层空间舒展）
    private val friction: Float = 0.85f // 摩擦阻尼系数
) {
    // 对外暴露响应式节点列表
    var nodesState by mutableStateOf<List<SimNode>>(emptyList())
        private set

    private var edgesList: List<GraphEdge> = emptyList()

    private var simulationJob: Job? = null

    // 能效仿真运行指示器
    var isSimulating by mutableStateOf(false)
        private set

    /**
     * 动态同步数据：保留已有节点坐标以确保动画平滑，对新节点在圆形区域内随机分布以防止爆炸。
     */
    fun setData(newNodes: List<GraphNode>, newEdges: List<GraphEdge>) {
        edgesList = newEdges
        val existingMap = nodesState.associateBy { it.id }
        
        nodesState = newNodes.map { node ->
            val existing = existingMap[node.id]
            SimNode(
                id = node.id,
                label = node.label,
                type = node.type,
                x = existing?.x ?: (Random.nextFloat() * 1200f - 600f),
                y = existing?.y ?: (Random.nextFloat() * 1200f - 600f),
                vx = existing?.vx ?: 0f,
                vy = existing?.vy ?: 0f,
                isPinned = existing?.isPinned ?: false
            )
        }
        wakeUp()
    }

    /**
     * 唤醒物理模拟循环进行坐标迭代收敛
     */
    fun wakeUp() {
        if (isSimulating) return
        isSimulating = true
        simulationJob?.cancel()
        simulationJob = scope.launch {
            var sleepCountdown = 0
            val epsilon = 0.06f // 静止休眠速度阈值
            
            while (isSimulating) {
                val activeNodes = nodesState
                if (activeNodes.isEmpty()) {
                    delay(32)
                    continue
                }

                val n = activeNodes.size
                val fx = FloatArray(n)
                val fy = FloatArray(n)

                // 1. 库仑斥力计算 (相互排斥，防止重叠)：O(N^2)
                for (i in 0 until n) {
                    val nodeA = activeNodes[i]
                    for (j in i + 1 until n) {
                        val nodeB = activeNodes[j]
                        val dx = nodeA.x - nodeB.x
                        val dy = nodeA.y - nodeB.y
                        val distSqr = dx * dx + dy * dy
                        val dist = sqrt(distSqr)
                        val safeDist = max(dist, 1.0f)

                        // 斥力公式改用慢衰减 F = kr / d，维持长程排斥防止节点叠加
                        val force = kr / safeDist
                        val fX = (dx / safeDist) * force
                        val fY = (dy / safeDist) * force

                        fx[i] += fX
                        fy[i] += fY
                        fx[j] -= fX
                        fy[j] -= fY
                    }
                }

                // 2. 胡克弹簧拉力计算 (关系边相连的两点拉近)：O(E)
                val nodeIndexMap = activeNodes.mapIndexed { idx, simNode -> simNode.id to idx }.toMap()
                edgesList.forEach { edge ->
                    val sourceIdx = nodeIndexMap[edge.sourceId]
                    val targetIdx = nodeIndexMap[edge.targetId]
                    if (sourceIdx != null && targetIdx != null) {
                        val nodeA = activeNodes[sourceIdx]
                        val nodeB = activeNodes[targetIdx]
                        val dx = nodeB.x - nodeA.x
                        val dy = nodeB.y - nodeA.y
                        val dist = sqrt(dx * dx + dy * dy)
                        val safeDist = max(dist, 1.0f)

                        // 弹簧引力公式 F = ka * (d - l0)
                        val force = ka * (safeDist - l0)
                        val fX = (dx / safeDist) * force
                        val fY = (dy / safeDist) * force

                        fx[sourceIdx] += fX
                        fy[sourceIdx] += fY
                        fx[targetIdx] -= fX
                        fy[targetIdx] -= fY
                    }
                }

                // 3. 中心引力及速度位移更新，智能休眠监测
                var maxVelocity = 0f
                for (i in 0 until n) {
                    val node = activeNodes[i]
                    if (node.isPinned) {
                        node.vx = 0f
                        node.vy = 0f
                        continue
                    }

                    // 微弱中心引力，拉回原点 (0,0)
                    fx[i] -= node.x * kg
                    fy[i] -= node.y * kg

                    // 应用阻尼摩擦力更新速度
                    node.vx = (node.vx + fx[i]) * friction
                    node.vy = (node.vy + fy[i]) * friction

                    // 防飞出极限速度限幅（最大单帧位移 40px）
                    val speed = sqrt(node.vx * node.vx + node.vy * node.vy)
                    val maxSpeed = 40f
                    if (speed > maxSpeed) {
                        node.vx = (node.vx / speed) * maxSpeed
                        node.vy = (node.vy / speed) * maxSpeed
                    }

                    // 更新节点实际坐标
                    node.x += node.vx
                    node.y += node.vy

                    val currentSpeed = sqrt(node.vx * node.vx + node.vy * node.vy)
                    if (currentSpeed > maxVelocity) {
                        maxVelocity = currentSpeed
                    }
                }

                // 🔋 智能休眠能效判定
                if (maxVelocity < epsilon) {
                    sleepCountdown++
                    if (sleepCountdown > 40) { // 持续 40 帧均满足静止，进入休眠以节省电量
                        break
                    }
                } else {
                    sleepCountdown = 0
                }

                delay(16) // ~60fps 平滑更新频率
            }
            isSimulating = false
        }
    }

    // --- 拖拽交互入口，触发物理重新激活 ---

    fun startDragging(nodeId: String) {
        nodesState.find { it.id == nodeId }?.let { node ->
            node.isPinned = true
            node.vx = 0f
            node.vy = 0f
            wakeUp()
        }
    }

    fun updateDragPosition(nodeId: String, newPos: Offset) {
        nodesState.find { it.id == nodeId }?.let { node ->
            node.x = newPos.x
            node.y = newPos.y
            wakeUp()
        }
    }

    fun stopDragging(nodeId: String) {
        nodesState.find { it.id == nodeId }?.let { node ->
            node.isPinned = false
            wakeUp()
        }
    }

    /**
     * 清理协程工作器
     */
    fun clear() {
        simulationJob?.cancel()
        isSimulating = false
    }
}
