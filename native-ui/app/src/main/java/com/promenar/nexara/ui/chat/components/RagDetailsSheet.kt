package com.promenar.nexara.ui.chat.components

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.data.model.KgPath
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagDetailsSheet(
    references: List<RagReference>?,
    kgPaths: List<KgPath>?,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = NexaraColors.SurfaceLow,
        dragHandle = { BottomSheetDefaults.DragHandle(color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.3f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "知识审计 (Knowledge Inspection)",
                style = NexaraTypography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = NexaraColors.OnSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(16.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                // RAG References (Chunks)
                if (!references.isNullOrEmpty()) {
                    item {
                        SectionHeader(title = "检索片段 (Retrieved Chunks)", icon = Icons.Rounded.Source)
                    }
                    itemsIndexed(references) { index, ref ->
                        RagReferenceCard(ref = ref, rank = index + 1)
                    }
                }

                // KG Paths
                if (!kgPaths.isNullOrEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        SectionHeader(title = "知识图谱 (Knowledge Graph Paths)", icon = Icons.Rounded.Hub)
                    }
                    itemsIndexed(kgPaths) { index, path ->
                        KgPathCard(path = path, index = index + 1)
                    }
                }

                if (references.isNullOrEmpty() && kgPaths.isNullOrEmpty()) {
                    item {
                        Text(
                            text = "暂无检索详情",
                            style = NexaraTypography.bodyMedium,
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(bottom = 8.dp)
    ) {
        Icon(icon, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(18.dp))
        Text(
            text = title,
            style = NexaraTypography.labelLarge.copy(fontWeight = FontWeight.Bold),
            color = NexaraColors.OnSurface
        )
    }
}

@Composable
private fun RagReferenceCard(ref: RagReference, rank: Int) {
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(NexaraColors.Primary.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("#$rank", style = NexaraTypography.labelSmall.copy(fontWeight = FontWeight.Bold), color = NexaraColors.Primary)
                    }
                    Text(
                        text = ref.source.substringAfterLast("/"),
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                // Scores
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (ref.score > 0f) {
                        ScoreBadge(label = "Vector", score = ref.score, color = NexaraColors.Tertiary)
                    }
                    if (ref.rerankScore != null) {
                        ScoreBadge(label = "Rerank", score = ref.rerankScore, color = NexaraColors.Primary)
                    }
                }
            }

            // Rank Change
            if (ref.rankChange != null && ref.rankChange != 0) {
                val successColor = androidx.compose.ui.graphics.Color(0xFF4CAF50)
                val errorColor = androidx.compose.ui.graphics.Color(0xFFF44336)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(
                        if (ref.rankChange > 0) Icons.Rounded.TrendingUp else Icons.Rounded.TrendingDown,
                        contentDescription = null,
                        tint = if (ref.rankChange > 0) successColor else errorColor,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "重排排名${if (ref.rankChange > 0) "上升" else "下降"} ${kotlin.math.abs(ref.rankChange)} 位",
                        style = NexaraTypography.labelSmall,
                        color = if (ref.rankChange > 0) successColor else errorColor
                    )
                }
            }

            // Content
            Surface(
                color = NexaraColors.SurfaceContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = ref.content,
                    style = NexaraTypography.bodySmall,
                    color = NexaraColors.OnSurfaceVariant,
                    modifier = Modifier.padding(10.dp)
                )
            }
        }
    }
}

@Composable
private fun ScoreBadge(label: String, score: Float, color: androidx.compose.ui.graphics.Color) {
    Surface(
        color = color.copy(alpha = 0.1f),
        border = BorderStroke(0.5.dp, color.copy(alpha = 0.3f)),
        shape = RoundedCornerShape(4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = NexaraTypography.labelSmall.copy(fontSize = 9.sp), color = color.copy(alpha = 0.8f))
            Text("${(score * 100).roundToInt()}%", style = NexaraTypography.labelSmall.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), color = color)
        }
    }
}

@Composable
private fun KgPathCard(path: KgPath, index: Int) {
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .background(NexaraColors.Tertiary.copy(alpha = 0.2f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("P$index", style = NexaraTypography.labelSmall.copy(fontWeight = FontWeight.Bold), color = NexaraColors.Tertiary)
                }
                Text(
                    text = "关键词: ${path.queryKeywords.joinToString(", ")}",
                    style = NexaraTypography.labelSmall,
                    color = NexaraColors.OnSurfaceVariant
                )
            }

            // Topology
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NexaraColors.SurfaceContainer.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                    .padding(12.dp)
            ) {
                path.edges.forEachIndexed { i, edge ->
                    val sourceNode = path.nodes.find { it.id == edge.sourceId }?.label ?: edge.sourceId
                    val targetNode = path.nodes.find { it.id == edge.targetId }?.label ?: edge.targetId

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        KgNodeBadge(text = sourceNode)
                        Icon(Icons.Rounded.ArrowRightAlt, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(16.dp))
                        Text(
                            text = edge.relation,
                            style = NexaraTypography.labelSmall.copy(fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                            color = NexaraColors.Tertiary
                        )
                        Icon(Icons.Rounded.ArrowRightAlt, null, tint = NexaraColors.OnSurfaceVariant, modifier = Modifier.size(16.dp))
                        KgNodeBadge(text = targetNode)
                    }
                    if (i < path.edges.size - 1) {
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }

            if (!path.reasoning.isNullOrBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Rounded.Lightbulb, null, tint = NexaraColors.Primary, modifier = Modifier.size(14.dp))
                    Text(
                        text = path.reasoning,
                        style = NexaraTypography.labelSmall,
                        color = NexaraColors.OnSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun KgNodeBadge(text: String) {
    Surface(
        color = NexaraColors.SurfaceHigh,
        shape = RoundedCornerShape(4.dp),
        border = BorderStroke(0.5.dp, NexaraColors.OutlineVariant.copy(alpha = 0.2f))
    ) {
        Text(
            text = text,
            style = NexaraTypography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = NexaraColors.OnSurface,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
}
