// UNIT TEST EXEMPTION STATEMENT: 本文件仅涉及 Jetpack Compose 纯布局、UI 交互展现与跳转逻辑，不包含任何数据转换、判定、图算法或状态流转核心业务逻辑，故依全局开发规范 §3.4 予以单元测试豁免。
package com.promenar.nexara.ui.chat.components


import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
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

import androidx.compose.ui.platform.LocalUriHandler
import com.promenar.nexara.data.model.Citation

private enum class InspectionTab(val label: String) {
    Retrieved("知识检索"),
    WebSearch("联网搜索"),
    KnowledgeGraph("知识图谱")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagDetailsSheet(
    references: List<RagReference>?,
    kgPaths: List<KgPath>?,
    citations: List<Citation>? = null,
    onDismissRequest: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    
    val hasReferences = !references.isNullOrEmpty()
    val hasKgPaths = !kgPaths.isNullOrEmpty()
    val hasCitations = !citations.isNullOrEmpty()

    val initialTab = when {
        hasReferences -> 0
        hasCitations -> 1
        hasKgPaths -> 2
        else -> 0
    }
    var selectedTab by remember { mutableIntStateOf(initialTab) }
    val tabs = InspectionTab.entries

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = NexaraColors.SurfaceLow,
        dragHandle = { BottomSheetDefaults.DragHandle(color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.3f)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.8f)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "知识与联网审计 (Knowledge & Web Inspection)",
                style = NexaraTypography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = NexaraColors.OnSurface,
                modifier = Modifier.padding(bottom = 12.dp)
            )

            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = NexaraColors.SurfaceLow,
                contentColor = NexaraColors.Primary,
                edgePadding = 0.dp,
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            height = 2.dp,
                            color = NexaraColors.Primary
                        )
                    }
                },
                divider = {}
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                text = tab.label,
                                style = NexaraTypography.labelMedium.copy(
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (selectedTab == index) NexaraColors.Primary else NexaraColors.OnSurfaceVariant
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Crossfade(
                targetState = selectedTab,
                label = "tab-crossfade",
                modifier = Modifier.weight(1f)
            ) { page ->
                when (page) {
                    0 -> {
                        if (hasReferences) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(bottom = 32.dp)
                            ) {
                                item {
                                    SectionHeader(title = "检索片段 (Retrieved Chunks)", icon = Icons.Rounded.Source)
                                }
                                itemsIndexed(references!!) { index, ref ->
                                    RagReferenceCard(ref = ref, rank = index + 1)
                                }
                            }
                        } else {
                            EmptyStateText()
                        }
                    }
                    1 -> {
                        if (hasCitations) {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                contentPadding = PaddingValues(bottom = 32.dp)
                            ) {
                                item {
                                    SectionHeader(title = "联网引用 (Web Search Citations)", icon = Icons.Rounded.TravelExplore)
                                }
                                itemsIndexed(citations!!) { index, citation ->
                                    WebSearchReferenceCard(citation = citation, rank = index + 1)
                                }
                            }
                        } else {
                            EmptyStateText()
                        }
                    }
                    2 -> {
                        if (hasKgPaths) {
                            KgPathsTab(
                                kgPaths = kgPaths!!,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            EmptyStateText()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun WebSearchReferenceCard(citation: Citation, rank: Int) {
    val uriHandler = LocalUriHandler.current
    NexaraGlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                try {
                    uriHandler.openUri(citation.url)
                } catch (e: Exception) {
                    // 防御性崩溃保护
                }
            },
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .background(NexaraColors.Primary.copy(alpha = 0.2f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "#$rank",
                            style = NexaraTypography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            color = NexaraColors.Primary
                        )
                    }
                    Text(
                        text = citation.title.ifBlank { "未知网页" },
                        style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        color = NexaraColors.OnSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }

                // 来源小标签 (Tavily/DuckDuckGo/SearXNG/Google Grounding 等)
                val source = citation.source ?: "Google"
                Surface(
                    color = NexaraColors.Tertiary.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(0.5.dp, NexaraColors.Tertiary.copy(alpha = 0.3f))
                ) {
                    Text(
                        text = source,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = NexaraTypography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                        color = NexaraColors.Tertiary
                    )
                }
            }

            // URL 链接展现与新页面打开提示
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Rounded.Language,
                    contentDescription = null,
                    tint = NexaraColors.Primary.copy(alpha = 0.7f),
                    modifier = Modifier.size(12.dp)
                )
                Text(
                    text = citation.url,
                    style = NexaraTypography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp
                    ),
                    color = NexaraColors.Primary.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Rounded.OpenInNew,
                    contentDescription = null,
                    tint = NexaraColors.OnSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyStateText() {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "暂无数据",
            style = NexaraTypography.bodyMedium,
            color = NexaraColors.OnSurfaceVariant
        )
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
private fun KgPathsTab(kgPaths: List<KgPath>, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp)
    ) {
        itemsIndexed(kgPaths) { index, path ->
            KgPathSection(path = path, index = index + 1)
        }
    }
}

@Composable
private fun KgPathSection(path: KgPath, index: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(NexaraColors.Tertiary.copy(alpha = 0.2f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "P$index",
                    style = NexaraTypography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = NexaraColors.Tertiary
                )
            }
            Text(
                text = "关键词: ${path.queryKeywords.joinToString(", ")}",
                style = NexaraTypography.labelSmall,
                color = NexaraColors.OnSurfaceVariant
            )
        }

        path.edges.forEachIndexed { i, edge ->
            val sourceNode = path.nodes.find { it.id == edge.sourceId }?.label ?: edge.sourceId
            val targetNode = path.nodes.find { it.id == edge.targetId }?.label ?: edge.targetId

            KgEdgeRow(
                sourceLabel = sourceNode,
                targetLabel = targetNode,
                relation = edge.relation
            )

            if (i < path.edges.size - 1) {
                Box(
                    modifier = Modifier
                        .padding(start = 10.dp)
                        .width(2.dp)
                        .height(8.dp)
                        .background(NexaraColors.OutlineVariant.copy(alpha = 0.3f))
                )
            }
        }

        if (!path.reasoning.isNullOrBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(
                    Icons.Rounded.Lightbulb,
                    null,
                    tint = NexaraColors.Primary,
                    modifier = Modifier.size(14.dp)
                )
                Text(
                    text = path.reasoning,
                    style = NexaraTypography.labelSmall,
                    color = NexaraColors.OnSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun KgEdgeRow(sourceLabel: String, targetLabel: String, relation: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        KgNodeCard(label = sourceLabel, modifier = Modifier.weight(1f))
        KgRelationBadge(relation = relation)
        KgNodeCard(label = targetLabel, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun KgNodeCard(label: String, modifier: Modifier = Modifier) {
    NexaraGlassCard(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(2.dp)
                    .background(NexaraColors.Primary)
            )
            Text(
                text = label,
                style = NexaraTypography.labelSmall.copy(fontWeight = FontWeight.Medium),
                color = NexaraColors.OnSurface,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun KgRelationBadge(relation: String) {
    Surface(
        color = NexaraColors.SurfaceHigh,
        shape = RoundedCornerShape(4.dp)
    ) {
        Text(
            text = relation,
            style = NexaraTypography.labelSmall.copy(
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace
            ),
            color = NexaraColors.OnSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
        )
    }
}
