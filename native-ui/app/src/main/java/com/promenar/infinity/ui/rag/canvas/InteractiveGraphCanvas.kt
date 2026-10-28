package com.promenar.infinity.ui.rag.canvas

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.infinity.R
import com.promenar.infinity.ui.rag.GraphEdge
import com.promenar.infinity.ui.testing.UiTags
import com.promenar.infinity.ui.theme.infinityDomainColors

/**
 * 知识图谱画布：同一圆形节点以类型色描边、浅底与内部图标区分类型，关系多的枢纽带一圈轨道；
 * 连线为轻弧线，终点外侧的小圆点表示方向。轻点实体选中、轻点空白取消，单指拖动实体或平移，双指缩放。
 *
 * 只在布局仿真或交互时重绘；标签、关系名与角标画在屏幕坐标里，缩放时字号不变。
 */
@Composable
fun InteractiveGraphCanvas(
    simulator: GraphPhysicsSimulator,
    edges: List<GraphEdge>,
    summary: String = "",
    gestureSummary: String = "",
    modifier: Modifier = Modifier,
    resetKey: Int = 0,
    highlight: KgCanvasHighlight = KgCanvasHighlight(),
    /** 需要居中显示的实体（例如搜索选中）；[centerRequestKey] 变化时执行一次。 */
    centerOnNodeId: String? = null,
    centerRequestKey: Int = 0,
    /** 居中时实体在画布中的纵向位置比例，底部被面板遮挡时调小。 */
    centerFraction: Float = 0.5f,
    onNodeTap: (String) -> Unit = {},
    onBackgroundTap: () -> Unit = {},
) {
    var scale by remember { mutableFloatStateOf(1.0f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var hasUserTransform by remember { mutableStateOf(false) }
    val densityPx = LocalDensity.current.density
    // 边缘留出节点、轨道环与标签的位置。
    val fitPaddingPx = 44f * densityPx

    fun fitView() {
        val fitted = fitGraphToViewport(simulator.nodesState.map { Offset(it.x, it.y) }, canvasSize.width.toFloat(), canvasSize.height.toFloat(), fitPaddingPx)
        scale = fitted.scale
        offset = fitted.offset
    }
    LaunchedEffect(simulator, simulator.datasetVersion, resetKey) {
        hasUserTransform = false
        fitView()
    }
    // 轮次条、键盘出现收起会改变画布尺寸；用户已调整的视角保持不动。
    LaunchedEffect(canvasSize) {
        if (!hasUserTransform) fitView()
    }
    // 布局展开的十几秒里持续跟随适配，用户平移缩放或拖动后不再接管视角。
    LaunchedEffect(simulator, canvasSize) {
        snapshotFlow { simulator.nodesState }.collect { if (!hasUserTransform) fitView() }
    }
    val currentCenterFraction by rememberUpdatedState(centerFraction)
    LaunchedEffect(centerRequestKey) {
        val node = centerOnNodeId?.let { simulator.nodesById[it] } ?: return@LaunchedEffect
        if (canvasSize == IntSize.Zero) return@LaunchedEffect
        hasUserTransform = true
        val fromScale = scale
        val fromOffset = offset
        // 保持当前缩放，邻居仍在视野内；只在缩得过小时放大到能看清。
        val toScale = maxOf(scale, 0.35f)
        val toOffset = Offset(canvasSize.width / 2f, canvasSize.height * currentCenterFraction) - Offset(node.x, node.y) * toScale
        animate(0f, 1f, animationSpec = tween(320)) { t, _ ->
            scale = fromScale + (toScale - fromScale) * t
            offset = fromOffset + (toOffset - fromOffset) * t
        }
    }

    val degrees = remember(edges) { kgDegrees(edges) }
    val currentDegrees by rememberUpdatedState(degrees)
    val currentOnNodeTap by rememberUpdatedState(onNodeTap)
    val currentOnBackgroundTap by rememberUpdatedState(onBackgroundTap)

    val palette = kgEntityPalette()
    val painters = KgEntityKind.entries.associateWith { rememberVectorPainter(it.icon) }
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val domain = MaterialTheme.infinityDomainColors
    val background = colors.background
    val newBadge = stringResource(R.string.kg_entity_new_badge)
    val paints = remember(density, colors, domain) { KgTextPaints(density.density, density.fontScale, colors.onSurface, colors.onSurfaceVariant, background, domain.onWarning) }
    val gridColor = colors.outlineVariant.copy(alpha = if (domain.isDark) 0.35f else 0.6f)
    val edgeColor = colors.outline.copy(alpha = 0.32f)
    val dimEdgeColor = colors.outline.copy(alpha = 0.08f)
    val selectionRing = colors.onSurface

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .background(background)
            .semantics(mergeDescendants = true) {
                contentDescription = listOf(summary, gestureSummary)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }
            .testTag(UiTags.KG_CANVAS)
            .pointerInput(simulator) {
                val slop = viewConfiguration.touchSlop
                val touchRadius = 48.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val hit = kgHitTest(simulator.nodesState, currentDegrees, down.position, scale, offset, touchRadius) { kgNodeScreenRadius(it, scale, densityPx) }
                    var moved = false
                    var dragging = false
                    var travelled = Offset.Zero
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.any { it.isConsumed }) continue
                        if (event.changes.size == 1) {
                            val change = event.changes.first()
                            if (!change.pressed) continue
                            val delta = change.position - change.previousPosition
                            travelled += delta
                            if (!moved && travelled.getDistance() > slop) {
                                moved = true
                                hasUserTransform = true
                                if (hit != null) {
                                    dragging = true
                                    simulator.startDragging(hit.id)
                                }
                            }
                            if (!moved) continue
                            change.consume()
                            val dragged = if (dragging) hit?.let { simulator.nodesById[it.id] } else null
                            if (dragged != null) {
                                simulator.updateDragPosition(dragged.id, Offset(dragged.x + delta.x / scale, dragged.y + delta.y / scale))
                            } else if (!dragging) {
                                offset += delta
                            }
                        } else {
                            // 双指缩放平移时释放被拖动的实体。
                            if (dragging && hit != null) simulator.stopDragging(hit.id)
                            dragging = false
                            moved = true
                            hasUserTransform = true
                            val centroid = event.calculateCentroid(useCurrent = true)
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                val oldScale = scale
                                scale = (scale * zoom).coerceIn(0.001f, 4.0f)
                                offset = centroid - (centroid - offset) * (scale / oldScale)
                            }
                            offset += event.calculatePan()
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    if (dragging && hit != null) simulator.stopDragging(hit.id)
                    if (!moved) {
                        if (hit != null) currentOnNodeTap(hit.id) else currentOnBackgroundTap()
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawDotGrid(gridColor, offset, scale)
            val nodes = simulator.nodesState
            val nodesById = simulator.nodesById
            val resolver = KgHighlightResolver(highlight, edges) { id -> nodesById[id]?.let { KgEntityKind.of(it.type) } }
            val kindById = HashMap<String, KgEntityKind>(nodes.size)
            nodes.forEach { kindById[it.id] = KgEntityKind.of(it.type) }

            // 视口反算到布局坐标，留出缓冲避免边缘生硬裁切。
            val margin = 120f
            val viewport = Rect(
                -offset.x / scale - margin, -offset.y / scale - margin,
                (size.width - offset.x) / scale + margin, (size.height - offset.y) / scale + margin,
            )
            val hair = 1f / scale
            val dp = densityPx * hair
            fun screenRadius(degree: Int) = kgNodeScreenRadius(degree, scale, densityPx)
            fun layoutRadius(degree: Int) = screenRadius(degree) * hair

            withTransform({
                translate(offset.x, offset.y)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                // 普通与淡出的线各合成一条路径一次绘制；着色强调的线单独绘制。
                val normalPath = Path()
                val dimPath = Path()
                edges.forEach { edge ->
                    val source = nodesById[edge.sourceId] ?: return@forEach
                    val target = nodesById[edge.targetId] ?: return@forEach
                    val start = Offset(source.x, source.y)
                    val end = Offset(target.x, target.y)
                    if (!segmentIntersectsRect(start, end, viewport)) return@forEach
                    val control = kgEdgeControl(start, end)
                    when {
                        resolver.isEdgeEmphasized(edge) -> {
                            val color = palette.of(kindById[edge.sourceId] ?: KgEntityKind.OTHER)
                            val path = Path().apply { moveTo(start.x, start.y); quadraticTo(control.x, control.y, end.x, end.y) }
                            drawPath(path, color.copy(alpha = 0.9f), style = Stroke(width = 1.6f * dp, cap = StrokeCap.Round))
                            drawCircle(color, radius = 2.4f * dp, center = kgEdgeArrowPoint(start, end, layoutRadius(degrees[edge.targetId] ?: 0)))
                        }
                        resolver.isEdgeLit(edge) -> {
                            normalPath.moveTo(start.x, start.y)
                            normalPath.quadraticTo(control.x, control.y, end.x, end.y)
                            drawCircle(edgeColor, radius = 1.6f * dp, center = kgEdgeArrowPoint(start, end, layoutRadius(degrees[edge.targetId] ?: 0)))
                        }
                        else -> {
                            dimPath.moveTo(start.x, start.y)
                            dimPath.quadraticTo(control.x, control.y, end.x, end.y)
                        }
                    }
                }
                drawPath(dimPath, dimEdgeColor, style = Stroke(width = 1f * dp))
                drawPath(normalPath, edgeColor, style = Stroke(width = 1f * dp, cap = StrokeCap.Round))

                nodes.forEach { node ->
                    if (node.x !in viewport.left..viewport.right || node.y !in viewport.top..viewport.bottom) return@forEach
                    val kind = kindById[node.id] ?: KgEntityKind.OTHER
                    val degree = degrees[node.id] ?: 0
                    drawEntity(
                        center = Offset(node.x, node.y),
                        radius = layoutRadius(degree),
                        color = palette.of(kind),
                        painter = painters.getValue(kind),
                        lit = resolver.isLit(node.id),
                        selected = node.id == highlight.selectedId,
                        hub = degree >= KG_HUB_MIN_DEGREE,
                        nodeId = node.id,
                        dp = dp,
                        background = background,
                        selectionRing = selectionRing,
                        darkTheme = domain.isDark,
                        showIcon = screenRadius(degree) >= 7f * densityPx,
                    )
                }
            }

            // 屏幕坐标中的文字：选中实体的关系名、实体名与“新”角标。
            val native = drawContext.canvas.nativeCanvas
            fun toScreen(x: Float, y: Float) = Offset(x * scale + offset.x, y * scale + offset.y)
            highlight.selectedId?.let { selected ->
                edges.forEach { edge ->
                    if (edge.relation.isBlank() || !resolver.isEdgeEmphasized(edge)) return@forEach
                    if (edge.sourceId != selected && edge.targetId != selected) return@forEach
                    val source = nodesById[edge.sourceId] ?: return@forEach
                    val target = nodesById[edge.targetId] ?: return@forEach
                    val start = Offset(source.x, source.y)
                    val end = Offset(target.x, target.y)
                    val at = kgQuadPoint(start, kgEdgeControl(start, end), end, 0.5f)
                    val screen = toScreen(at.x, at.y)
                    paints.relation.color = palette.of(kindById[edge.sourceId] ?: KgEntityKind.OTHER).toArgb()
                    native.drawText(edge.relation, screen.x, screen.y + paints.relation.textSize / 3f, paints.relationHalo)
                    native.drawText(edge.relation, screen.x, screen.y + paints.relation.textSize / 3f, paints.relation)
                }
            }
            nodes.forEach { node ->
                val degree = degrees[node.id] ?: 0
                val radius = screenRadius(degree)
                val screen = toScreen(node.x, node.y)
                if (screen.x < -80f || screen.x > size.width + 80f || screen.y < -40f || screen.y > size.height + 60f) return@forEach
                if (resolver.showsLabel(node.id, degree, scale)) {
                    val selected = node.id == highlight.selectedId
                    val paint = if (selected) paints.labelStrong else paints.label
                    val halo = if (selected) paints.labelStrongHalo else paints.labelHalo
                    val ringGap = (if (degree >= KG_HUB_MIN_DEGREE) 6f else 2f) * densityPx
                    val baseline = screen.y + radius + ringGap + paint.textSize * 1.15f
                    val text = ellipsize(node.label, paint, 120.dp.toPx())
                    native.drawText(text, screen.x, baseline, halo)
                    native.drawText(text, screen.x, baseline, paint)
                }
                if (node.id in highlight.newNodeIds && resolver.isLit(node.id)) {
                    val badge = Offset(screen.x + radius * 0.75f, screen.y - radius * 0.75f)
                    val badgeRadius = 8.dp.toPx() * maxOf(1f, density.fontScale * 0.9f)
                    drawCircle(background, radius = badgeRadius + 2.dp.toPx(), center = badge)
                    drawCircle(domain.warning, radius = badgeRadius, center = badge)
                    native.drawText(newBadge, badge.x, badge.y + paints.badge.textSize / 3f, paints.badge)
                }
            }
        }
    }
}

private fun DrawScope.drawDotGrid(color: Color, offset: Offset, scale: Float) {
    val spacing = (22.dp.toPx() * scale.coerceIn(0.6f, 1.6f)).coerceAtLeast(14.dp.toPx())
    val startX = ((offset.x % spacing) + spacing) % spacing
    val startY = ((offset.y % spacing) + spacing) % spacing
    val points = ArrayList<Offset>()
    var y = startY
    while (y < size.height) {
        var x = startX
        while (x < size.width) {
            points.add(Offset(x, y))
            x += spacing
        }
        y += spacing
    }
    drawPoints(points, PointMode.Points, color, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
}

private fun DrawScope.drawEntity(
    center: Offset,
    radius: Float,
    color: Color,
    painter: VectorPainter,
    lit: Boolean,
    selected: Boolean,
    hub: Boolean,
    nodeId: String,
    /** 布局坐标中 1dp 的长度。 */
    dp: Float,
    background: Color,
    selectionRing: Color,
    darkTheme: Boolean,
    showIcon: Boolean,
) {
    val alpha = if (lit) 1f else 0.16f
    if (hub) {
        val orbit = radius + 6f * dp
        drawCircle(color.copy(alpha = 0.35f * alpha), radius = orbit, center = center, style = Stroke(width = 1f * dp))
        drawCircle(color.copy(alpha = alpha), radius = 2.4f * dp, center = kgOrbitPoint(center, orbit, nodeId))
    }
    if (selected) {
        drawCircle(selectionRing, radius = radius + 4f * dp, center = center, style = Stroke(width = 2f * dp))
        drawCircle(color, radius = radius, center = center)
    } else {
        // 先用画布底色垫一层，浅底不会透出身后的连线。
        drawCircle(background.copy(alpha = alpha), radius = radius, center = center)
        drawCircle(color.copy(alpha = (if (darkTheme) 0.18f else 0.14f) * alpha), radius = radius, center = center)
        drawCircle(color.copy(alpha = alpha), radius = radius, center = center, style = Stroke(width = 1.5f * dp))
    }
    if (!showIcon && !selected) return
    val iconSize = radius * 1.05f
    translate(center.x - iconSize / 2f, center.y - iconSize / 2f) {
        with(painter) {
            draw(Size(iconSize, iconSize), colorFilter = ColorFilter.tint(if (selected) background else color.copy(alpha = alpha)))
        }
    }
}

private fun ellipsize(text: String, paint: android.graphics.Paint, maxWidth: Float): String {
    if (paint.measureText(text) <= maxWidth) return text
    val count = paint.breakText(text, true, maxWidth - paint.measureText("…"), null)
    return text.take(count.coerceAtLeast(1)) + "…"
}

/** 画布文字画笔：实体名与关系名先画一层底色描边作光晕，压在连线上仍可读。 */
private class KgTextPaints(
    private val density: Float,
    private val fontScale: Float,
    onSurface: Color,
    onSurfaceVariant: Color,
    background: Color,
    onWarning: Color,
) {
    private fun sp(value: Float) = value * density * fontScale
    private fun base(sizeSp: Float, color: Color, medium: Boolean = false) = android.graphics.Paint().apply {
        isAntiAlias = true
        textSize = sp(sizeSp)
        this.color = color.toArgb()
        textAlign = android.graphics.Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", android.graphics.Typeface.NORMAL)
    }
    private fun halo(of: android.graphics.Paint, color: Color) = android.graphics.Paint(of).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeJoin = android.graphics.Paint.Join.ROUND
        this.color = color.toArgb()
    }

    val label = base(11.5f, onSurfaceVariant)
    val labelStrong = base(12f, onSurface, medium = true)
    val labelHalo = halo(label, background)
    val labelStrongHalo = halo(labelStrong, background)
    val relation = base(10.5f, onSurfaceVariant)
    val relationHalo = halo(relation, background)
    val badge = base(9f, onWarning, medium = true)
}
