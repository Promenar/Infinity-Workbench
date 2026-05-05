package com.promenar.nexara.ui.rag

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Psychology
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlin.math.roundToInt
import kotlin.random.Random

data class GraphNode(
    val id: String,
    val label: String,
    val type: String,
    val x: Float,
    val y: Float,
    val icon: ImageVector = Icons.Rounded.Hub
)

data class GraphEdge(
    val sourceId: String,
    val targetId: String,
    val relation: String = ""
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeGraphScreen(
    onNavigateBack: () -> Unit
) {
    val nodes = remember { generateMockNodes() }
    val edges = remember { generateMockEdges(nodes) }

    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(0.3f, 3f)
        offsetX += panChange.x
        offsetY += panChange.y
    }

    var selectedNode by remember { mutableStateOf<GraphNode?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            stringResource(R.string.kg_title),
                            style = NexaraTypography.headlineLarge,
                            color = NexaraColors.OnSurface
                        )
                        Text(
                            stringResource(R.string.kg_stats_summary, nodes.size, edges.size),
                            style = NexaraTypography.labelMedium,
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_cd_back),
                            tint = NexaraColors.OnSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f)
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .pointerInput(nodes) {
                        detectTapGestures { tapOffset ->
                            val canvasX = (tapOffset.x - offsetX) / scale
                            val canvasY = (tapOffset.y - offsetY) / scale

                            val hitNode = nodes.find { node ->
                                val dx = canvasX - node.x
                                val dy = canvasY - node.y
                                kotlin.math.sqrt(dx * dx + dy * dy) < 35f
                            }
                            selectedNode = hitNode
                        }
                    }
                    .transformable(state = transformableState)
            ) {
                Canvas(
                    modifier = Modifier.fillMaxSize()
                ) {
                    val canvasWidth = size.width
                    val canvasHeight = size.height

                    drawGrid(canvasWidth, canvasHeight, scale, offsetX, offsetY)

                    edges.forEach { edge ->
                        val source = nodes.find { it.id == edge.sourceId } ?: return@forEach
                        val target = nodes.find { it.id == edge.targetId } ?: return@forEach
                        val sx = source.x * scale + offsetX
                        val sy = source.y * scale + offsetY
                        val tx = target.x * scale + offsetX
                        val ty = target.y * scale + offsetY
                        drawLine(
                            color = Color.White.copy(alpha = 0.15f),
                            start = Offset(sx, sy),
                            end = Offset(tx, ty),
                            strokeWidth = 1.5f * scale
                        )
                    }

                    nodes.forEach { node ->
                        val cx = node.x * scale + offsetX
                        val cy = node.y * scale + offsetY
                        val radius = 22f * scale
                        val isSelected = selectedNode?.id == node.id

                        val nodeColor = when (node.type) {
                            "concept" -> NexaraColors.Primary
                            "document" -> Color(0xFF4ADE80)
                            "person" -> Color(0xFF60A5FA)
                            else -> NexaraColors.Outline
                        }

                        if (isSelected) {
                            drawCircle(
                                color = nodeColor.copy(alpha = 0.3f),
                                radius = radius + 8f * scale,
                                center = Offset(cx, cy)
                            )
                        }

                        drawCircle(
                            color = Color(0xFF131315),
                            radius = radius,
                            center = Offset(cx, cy)
                        )
                        drawCircle(
                            color = nodeColor,
                            radius = radius,
                            center = Offset(cx, cy),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2f * scale)
                        )

                        drawContext.canvas.nativeCanvas.apply {
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#E5E1E4")
                                textSize = 10f * scale * density
                                textAlign = android.graphics.Paint.Align.CENTER
                                isAntiAlias = true
                            }
                            drawText(
                                node.label,
                                cx,
                                cy + radius + 14f * scale * density,
                                paint
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(NexaraColors.SurfaceLow.copy(alpha = 0.8f))
                    .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(12.dp))
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val allLabel = stringResource(R.string.kg_filter_all)
                listOf(
                    allLabel to NexaraColors.Primary,
                    stringResource(R.string.kg_filter_documents) to Color(0xFF4ADE80),
                    stringResource(R.string.kg_filter_folders) to Color(0xFFFBBF24),
                    stringResource(R.string.kg_filter_concepts) to NexaraColors.Outline
                ).forEach { (label, color) ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (label == allLabel) Modifier.background(NexaraColors.SurfaceBright)
                                else Modifier
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                            .then(
                                if (label != allLabel) Modifier else Modifier
                            )
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(color, CircleShape)
                            )
                            Text(
                                label,
                                style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                                color = if (label == allLabel) NexaraColors.OnSurface else NexaraColors.OnSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (selectedNode != null) {
        ModalBottomSheet(
            onDismissRequest = { selectedNode = null },
            sheetState = sheetState,
            containerColor = NexaraColors.SurfaceLow,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            tonalElevation = 0.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(NexaraColors.SurfaceHigh, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                selectedNode?.icon ?: Icons.Rounded.Hub,
                                contentDescription = null,
                                tint = NexaraColors.Primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Column {
                            Text(
                                selectedNode?.label ?: "",
                                style = NexaraTypography.headlineMedium,
                                color = NexaraColors.OnSurface
                            )
                            Text(
                                stringResource(R.string.kg_node_details, selectedNode?.type?.uppercase() ?: "", edges.count { it.sourceId == selectedNode?.id || it.targetId == selectedNode?.id }),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.OnSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { selectedNode = null }) {
                        Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.shared_btn_close), tint = NexaraColors.OnSurfaceVariant)
                    }
                }

                Text(
                    stringResource(R.string.kg_node_description),
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(NexaraColors.Primary.copy(alpha = 0.1f))
                            .border(0.5.dp, NexaraColors.Primary.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            stringResource(R.string.kg_expand_context),
                            style = NexaraTypography.labelMedium,
                            color = NexaraColors.Primary
                        )
                    }
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(NexaraColors.SurfaceHigh)
                            .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(12.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Hub,
                            contentDescription = "More",
                            tint = NexaraColors.OnSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawGrid(width: Float, height: Float, scale: Float, offsetX: Float, offsetY: Float) {
    val gridSize = 60f * scale
    val gridColor = Color.White.copy(alpha = 0.03f)
    var x = offsetX % gridSize
    while (x < width) {
        drawLine(gridColor, Offset(x, 0f), Offset(x, height), strokeWidth = 0.5f)
        x += gridSize
    }
    var y = offsetY % gridSize
    while (y < height) {
        drawLine(gridColor, Offset(0f, y), Offset(width, y), strokeWidth = 0.5f)
        y += gridSize
    }
}

private fun generateMockNodes(): List<GraphNode> {
    val rng = Random(42)
    return listOf(
        GraphNode("n0", "Nexus Core", "concept", 500f, 350f, Icons.Rounded.Psychology),
        GraphNode("n1", "Q3 Research", "document", 180f, 120f, Icons.Rounded.Description),
        GraphNode("n2", "Architecture.md", "document", 750f, 100f, Icons.Rounded.Description),
        GraphNode("n3", "API Gateway", "concept", 150f, 520f, Icons.Rounded.Hub),
        GraphNode("n4", "Agent 04", "person", 800f, 520f, Icons.Rounded.Person),
    ) + (5..54).map { i ->
        val type = when (rng.nextInt(3)) {
            0 -> "concept"
            1 -> "document"
            else -> "person"
        }
        val icon = when (type) {
            "concept" -> Icons.Rounded.Hub
            "document" -> Icons.Rounded.Description
            else -> Icons.Rounded.Person
        }
        GraphNode(
            "n$i",
            "${type.capitalize()} ${('A' + i % 26)}${i / 26 + 1}",
            type,
            rng.nextFloat() * 900f + 50f,
            rng.nextFloat() * 600f + 50f,
            icon
        )
    }
}

private fun generateMockEdges(nodes: List<GraphNode>): List<GraphEdge> {
    val rng = Random(42)
    val edges = mutableListOf<GraphEdge>()
    edges.add(GraphEdge("n0", "n1", "contains"))
    edges.add(GraphEdge("n0", "n2", "references"))
    edges.add(GraphEdge("n0", "n3", "depends_on"))
    edges.add(GraphEdge("n0", "n4", "managed_by"))
    edges.add(GraphEdge("n1", "n3", "related"))
    edges.add(GraphEdge("n2", "n4", "authored"))

    for (i in 0 until 74) {
        val src = nodes[rng.nextInt(nodes.size)]
        val tgt = nodes[rng.nextInt(nodes.size)]
        if (src.id != tgt.id && edges.none { (it.sourceId == src.id && it.targetId == tgt.id) || (it.sourceId == tgt.id && it.targetId == src.id) }) {
            edges.add(GraphEdge(src.id, tgt.id))
        }
    }
    return edges
}

private fun String.capitalize() = replaceFirstChar { it.uppercase() }
