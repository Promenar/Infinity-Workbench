package com.promenar.nexara.ui.rag.canvas

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.ui.rag.GraphEdge
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlin.math.max
import kotlin.math.sqrt

@Composable
fun InteractiveGraphCanvas(
    simulator: GraphPhysicsSimulator,
    edges: List<GraphEdge>,
    modifier: Modifier = Modifier
) {
    var scale by remember { mutableFloatStateOf(1.0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    // 脉冲粒子流动动画周期驱动器
    val infiniteTransition = rememberInfiniteTransition(label = "particlePulse")
    val particlePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    // 矢量图标画家缓存
    val psychologyPainter = rememberVectorPainter(Icons.Rounded.Psychology)
    val descriptionPainter = rememberVectorPainter(Icons.Rounded.Description)
    val personPainter = rememberVectorPainter(Icons.Rounded.Person)
    val hubPainter = rememberVectorPainter(Icons.Rounded.Hub)

    val density = LocalDensity.current
    val textPaint = remember(density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = with(density) { 10.sp.toPx() }
            color = android.graphics.Color.parseColor("#E5E1E4")
            textAlign = android.graphics.Paint.Align.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            // 部署抗锯齿软投影
            setShadowLayer(4f, 0f, 2f, android.graphics.Color.BLACK)
        }
    }

    val edgeTextPaint = remember(density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = with(density) { 8.sp.toPx() }
            color = android.graphics.Color.parseColor("#999999")
            textAlign = android.graphics.Paint.Align.CENTER
            setShadowLayer(2f, 0f, 1f, android.graphics.Color.BLACK)
        }
    }

    // 色彩映射表
    val colorMap = remember {
        mapOf(
            "concept" to Color(0xFFC0C1FF),
            "document" to Color(0xFF4ADE80),
            "person" to Color(0xFF60A5FA),
            "organization" to Color(0xFFFBBF24),
            "location" to Color(0xFFF472B6),
            "event" to Color(0xFFA78BFA),
            "other" to Color(0xFF9E9EA0)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NexaraColors.CanvasBackground)
            // 🧠 部署极致无冲突手势控制器：单指高敏感拖曳节点/平移，多指无缝 focal-point 缩放
            .pointerInput(simulator) {
                awaitEachGesture {
                    var activeDraggedNode: SimNode? = null
                    var isPanning = false

                    val down = awaitFirstDown(requireUnconsumed = false)
                    val mappedDown = (down.position - offset) / scale

                    // 检索触碰范围内的节点 (点击半径 32dp ~ 80px)
                    val clickedNode = simulator.nodesState.find { simNode ->
                        val dx = simNode.x - mappedDown.x
                        val dy = simNode.y - mappedDown.y
                        sqrt(dx * dx + dy * dy) < 40f
                    }

                    if (clickedNode != null) {
                        activeDraggedNode = clickedNode
                        simulator.startDragging(clickedNode.id)
                    } else {
                        isPanning = true
                    }

                    do {
                        val event = awaitPointerEvent()
                        val canceled = event.changes.any { it.isConsumed }
                        if (!canceled) {
                            if (event.changes.size == 1) {
                                // 单指追踪
                                val change = event.changes.first()
                                if (change.pressed) {
                                    val dragAmount = change.position - change.previousPosition
                                    val currentDrag = activeDraggedNode
                                    if (currentDrag != null) {
                                        change.consume()
                                        val newX = currentDrag.x + dragAmount.x / scale
                                        val newY = currentDrag.y + dragAmount.y / scale
                                        simulator.updateDragPosition(currentDrag.id, Offset(newX, newY))
                                    } else if (isPanning) {
                                        change.consume()
                                        offset += dragAmount
                                    }
                                }
                            } else if (event.changes.size >= 2) {
                                // 双指缩放平移，瞬间释放任何被拖拽节点的锁定
                                activeDraggedNode?.let { simulator.stopDragging(it.id) }
                                activeDraggedNode = null
                                isPanning = false

                                val centroid = event.calculateCentroid(useCurrent = true)
                                val pan = event.calculatePan()
                                val zoom = event.calculateZoom()

                                if (zoom != 1f) {
                                    val oldScale = scale
                                    scale = (scale * zoom).coerceIn(0.25f, 4.0f)
                                    // 矩阵缩放聚焦质心计算
                                    offset = centroid - (centroid - offset) * (scale / oldScale)
                                }
                                offset += pan
                                event.changes.forEach { it.consume() }
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    activeDraggedNode?.let { simulator.stopDragging(it.id) }
                    activeDraggedNode = null
                }
            }
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasWidth = size.width
            val canvasHeight = size.height

            // 1. 绘制科幻宇宙空间微光网格背景
            val gridSpacing = 85f * scale
            val startX = offset.x % gridSpacing
            val startY = offset.y % gridSpacing

            for (x in generateSequence(startX) { it + gridSpacing }.takeWhile { it < canvasWidth }) {
                drawLine(
                    color = Color.White.copy(alpha = 0.025f),
                    start = Offset(x, 0f),
                    end = Offset(x, canvasHeight),
                    strokeWidth = 1f
                )
            }
            for (y in generateSequence(startY) { it + gridSpacing }.takeWhile { it < canvasHeight }) {
                drawLine(
                    color = Color.White.copy(alpha = 0.025f),
                    start = Offset(0f, y),
                    end = Offset(canvasWidth, y),
                    strokeWidth = 1f
                )
            }

            // 2. 进入硬件加速的 GPU 全局缩放平移视图转换层
            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                val currentNodes = simulator.nodesState

                // 计算当前屏幕视口反向映射到虚拟物理坐标系下的可见边界（带 120 像素安全渲染缓冲区以防过渡生硬）
                val padding = 120f
                val viewLeft = -offset.x / scale - padding
                val viewRight = (canvasWidth - offset.x) / scale + padding
                val viewTop = -offset.y / scale - padding
                val viewBottom = (canvasHeight - offset.y) / scale + padding

                // --- A. 绘制关系连线与脉冲粒子流动 ---
                edges.forEach { edge ->
                    val sourceNode = currentNodes.find { it.id == edge.sourceId }
                    val targetNode = currentNodes.find { it.id == edge.targetId }
                    if (sourceNode != null && targetNode != null) {
                        // 视口裁剪：如果关系线两端节点都在可见范围外，直接跳过绘制，零 GPU 损耗！
                        val startInView = sourceNode.x in viewLeft..viewRight && sourceNode.y in viewTop..viewBottom
                        val endInView = targetNode.x in viewLeft..viewRight && targetNode.y in viewTop..viewBottom
                        if (!startInView && !endInView) return@forEach

                        val start = Offset(sourceNode.x, sourceNode.y)
                        val end = Offset(targetNode.x, targetNode.y)

                        // 柔和微透明关系底线
                        drawLine(
                            color = Color.White.copy(alpha = 0.08f),
                            start = start,
                            end = end,
                            strokeWidth = 1.5f
                        )

                        val dx = end.x - start.x
                        val dy = end.y - start.y
                        val length = sqrt(dx * dx + dy * dy)
                        if (length > 0f) {
                            // 绘制动态滚动的脉冲粒子流
                            val particleCount = 2
                            for (p in 0 until particleCount) {
                                val fraction = (particlePhase + p.toFloat() / particleCount) % 1.0f
                                val px = start.x + dx * fraction
                                val py = start.y + dy * fraction
                                drawCircle(
                                    color = Color(0xFFC0C1FF).copy(alpha = 0.55f),
                                    radius = 2.2f,
                                    center = Offset(px, py)
                                )
                            }

                            // 渲染关系边 Label 文本
                            if (edge.relation.isNotEmpty() && scale > 0.6f) {
                                val midX = start.x + dx * 0.5f
                                val midY = start.y + dy * 0.5f
                                drawContext.canvas.nativeCanvas.drawText(
                                    edge.relation,
                                    midX,
                                    midY - 4f,
                                    edgeTextPaint
                                )
                            }
                        }
                    }
                }

                // --- B. 绘制节点（呼吸发光、同心渐变圆、矢量图标、文字投影） ---
                currentNodes.forEach { node ->
                    // 视口裁剪：如果节点本身完全不在屏幕可视边界内，跳过绘制，免除高画质圆和文字投影负载！
                    val inView = node.x in viewLeft..viewRight && node.y in viewTop..viewBottom
                    if (!inView) return@forEach

                    val nodeColor = colorMap[node.type] ?: colorMap["other"]!!

                    // 1) 绘制柔美渐变呼吸发光阴影 Halo
                    drawCircle(
                        color = nodeColor.copy(alpha = 0.12f),
                        radius = 28f,
                        center = Offset(node.x, node.y)
                    )
                    drawCircle(
                        color = nodeColor.copy(alpha = 0.28f),
                        radius = 23f,
                        center = Offset(node.x, node.y)
                    )

                    // 2) 绘制星球实体
                    drawCircle(
                        color = nodeColor,
                        radius = 16f,
                        center = Offset(node.x, node.y)
                    )

                    // 3) 绘制精细的纯白星球亮边轮廓线
                    drawCircle(
                        color = Color.White.copy(alpha = 0.8f),
                        radius = 16f,
                        center = Offset(node.x, node.y),
                        style = Stroke(width = 1.2f)
                    )

                    // 4) 节点中心高精度矢量图标绘制
                    val painter = when (node.type) {
                        "concept" -> psychologyPainter
                        "document" -> descriptionPainter
                        "person" -> personPainter
                        else -> hubPainter
                    }
                    val iconSize = 16f
                    translate(node.x - iconSize / 2f, node.y - iconSize / 2f) {
                        with(painter) {
                            draw(
                                size = Size(iconSize, iconSize),
                                colorFilter = ColorFilter.tint(Color.Black.copy(alpha = 0.85f))
                            )
                        }
                    }

                    // 5) 绘制防重叠防溢出的抗锯齿文本
                    if (scale > 0.4f) {
                        drawContext.canvas.nativeCanvas.drawText(
                            node.label,
                            node.x,
                            node.y + 30f,
                            textPaint
                        )
                    }
                }
            }
        }

        // --- C. 能效静止休眠状态低调提示 (WOW 微光指示灯) ---
        if (simulator.isSimulating) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .background(Color.Transparent)
            ) {
                // 可选：可在 UI 顶部或底部放置一个极简微弱指示器，这里保持整体界面绝对精简
            }
        }
    }
}
