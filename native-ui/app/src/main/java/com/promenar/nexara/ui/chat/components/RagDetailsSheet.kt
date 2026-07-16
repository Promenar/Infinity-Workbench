// UNIT TEST EXEMPTION STATEMENT: 本文件仅涉及 Jetpack Compose 纯布局、UI 交互展现与跳转逻辑，不包含任何数据转换、判定、图算法或状态流转核心业务逻辑，故依全局开发规范 §4.2 予以单元测试豁免。
package com.promenar.nexara.ui.chat.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.TrendingDown
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.promenar.nexara.R
import com.promenar.nexara.data.model.Citation
import com.promenar.nexara.data.model.KgPath
import com.promenar.nexara.data.model.RagReference
import com.promenar.nexara.ui.testing.UiTags
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

enum class RagDetailsTab(val labelRes: Int) {
    Retrieved(R.string.rag_details_tab_retrieved),
    WebSearch(R.string.rag_details_tab_web_search),
    KnowledgeGraph(R.string.rag_details_tab_knowledge_graph),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagDetailsSheet(
    references: List<RagReference>?,
    kgPaths: List<KgPath>?,
    citations: List<Citation>? = null,
    onDismissRequest: () -> Unit,
    openLink: ((String) -> Unit)? = null,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        dragHandle = { BottomSheetDefaults.DragHandle() },
    ) {
        RagDetailsSheetContent(
            references = references.orEmpty(),
            citations = citations.orEmpty(),
            kgPaths = kgPaths.orEmpty(),
            modifier = Modifier.fillMaxHeight(0.82f),
            openLink = openLink,
        )
    }
}

@Composable
fun RagDetailsSheetContent(
    references: List<RagReference>,
    citations: List<Citation>,
    kgPaths: List<KgPath>,
    modifier: Modifier = Modifier,
    initialTab: RagDetailsTab = initialRagDetailsTab(references, citations, kgPaths),
    openLink: ((String) -> Unit)? = null,
) {
    var selectedTabIndex by rememberSaveable(initialTab) { mutableIntStateOf(initialTab.ordinal) }
    val tabs = RagDetailsTab.entries
    val uriHandler = LocalUriHandler.current
    val linkOpener = openLink ?: uriHandler::openUri
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val failureMessage = stringResource(R.string.rag_details_link_error)
    val retryLabel = stringResource(R.string.rag_details_retry)

    fun requestOpen(url: String) {
        scope.launch {
            runCatching { linkOpener(url) }
                .onFailure {
                    val result = snackbarHostState.showSnackbar(
                        message = failureMessage,
                        actionLabel = retryLabel,
                        withDismissAction = true,
                    )
                    if (result == SnackbarResult.ActionPerformed) requestOpen(url)
                }
        }
    }

    Scaffold(
        modifier = modifier
            .fillMaxWidth()
            .testTag(UiTags.RAG_DETAILS_ROOT),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    modifier = Modifier.testTag(UiTags.RAG_DETAILS_LINK_ERROR),
                    action = {
                        TextButton(
                            onClick = data::performAction,
                            modifier = Modifier
                                .heightIn(min = 48.dp)
                                .testTag(UiTags.RAG_DETAILS_LINK_RETRY),
                        ) {
                            Text(data.visuals.actionLabel.orEmpty())
                        }
                    },
                    dismissAction = null,
                ) {
                    Text(data.visuals.message)
                }
            }
        },
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .padding(horizontal = 16.dp),
        ) {
            Text(
                text = stringResource(R.string.rag_details_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            PrimaryScrollableTabRow(
                selectedTabIndex = selectedTabIndex,
                edgePadding = 0.dp,
                containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                divider = {},
            ) {
                tabs.forEachIndexed { index, tab ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag(tab.testTag()),
                        text = { Text(stringResource(tab.labelRes)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            when (tabs[selectedTabIndex]) {
                RagDetailsTab.Retrieved -> RetrievedReferencesList(references)
                RagDetailsTab.WebSearch -> WebCitationsList(citations, ::requestOpen)
                RagDetailsTab.KnowledgeGraph -> KnowledgeGraphList(kgPaths)
            }
        }
    }
}

private fun initialRagDetailsTab(
    references: List<RagReference>,
    citations: List<Citation>,
    kgPaths: List<KgPath>,
): RagDetailsTab = when {
    references.isNotEmpty() -> RagDetailsTab.Retrieved
    citations.isNotEmpty() -> RagDetailsTab.WebSearch
    kgPaths.isNotEmpty() -> RagDetailsTab.KnowledgeGraph
    else -> RagDetailsTab.Retrieved
}

private fun RagDetailsTab.testTag(): String = when (this) {
    RagDetailsTab.Retrieved -> UiTags.RAG_DETAILS_TAB_RETRIEVED
    RagDetailsTab.WebSearch -> UiTags.RAG_DETAILS_TAB_WEB
    RagDetailsTab.KnowledgeGraph -> UiTags.RAG_DETAILS_TAB_KG
}

@Composable
private fun RetrievedReferencesList(references: List<RagReference>) {
    if (references.isEmpty()) {
        EmptyDetailsState()
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.RAG_DETAILS_LIST),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        itemsIndexed(
            items = references,
            key = { index, reference ->
                reference.id.ifBlank { "${reference.source}:${reference.chunkIndex}:$index" }
            },
        ) { index, reference ->
            RetrievedReferenceRow(reference, index)
            if (index != references.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun RetrievedReferenceRow(reference: RagReference, index: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.ragDetailsReferenceItem(index))
            .padding(vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RankLabel(index + 1)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = reference.source.substringAfterLast('/').ifBlank {
                        stringResource(R.string.rag_details_unknown_source)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                scoreSummary(reference).takeIf(String::isNotBlank)?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (reference.rankChange != null && reference.rankChange != 0) {
                    val movedUp = reference.rankChange > 0
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(
                            imageVector = if (movedUp) Icons.AutoMirrored.Rounded.TrendingUp else Icons.AutoMirrored.Rounded.TrendingDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(
                                if (movedUp) R.string.rag_details_rank_up else R.string.rag_details_rank_down,
                                abs(reference.rankChange),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        Text(
            text = reference.content,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun scoreSummary(reference: RagReference): String {
    val scores = buildList {
        if (reference.score > 0f) {
            add("${stringResource(R.string.rag_details_score_vector)} ${(reference.score * 100).roundToInt()}%")
        }
        reference.rerankScore?.let { score ->
            add("${stringResource(R.string.rag_details_score_rerank)} ${(score * 100).roundToInt()}%")
        }
    }
    return scores.joinToString(" · ")
}

@Composable
private fun WebCitationsList(citations: List<Citation>, onOpen: (String) -> Unit) {
    if (citations.isEmpty()) {
        EmptyDetailsState()
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.RAG_DETAILS_LIST),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        itemsIndexed(
            items = citations,
            key = { index, citation -> "${citation.url}:$index" },
        ) { index, citation ->
            WebCitationRow(citation, index, onOpen)
        }
    }
}

@Composable
private fun WebCitationRow(citation: Citation, index: Int, onOpen: (String) -> Unit) {
    val openLabel = stringResource(R.string.rag_details_open_link)
    val unknownSource = stringResource(R.string.rag_details_unknown_source)
    val usesStackedHeader = LocalDensity.current.fontScale >= 1.5f
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                onClickLabel = openLabel,
                role = Role.Button,
                onClick = { onOpen(citation.url) },
            )
            .testTag(UiTags.ragDetailsWebItem(index)),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (usesStackedHeader) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RankLabel(index + 1)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Text(
                    text = citation.title.ifBlank { stringResource(R.string.rag_details_unknown_webpage) },
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RankLabel(index + 1)
                    Text(
                        text = citation.title.ifBlank { stringResource(R.string.rag_details_unknown_webpage) },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Text(
                text = citation.source?.takeIf(String::isNotBlank) ?: unknownSource,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            citation.snippet?.takeIf(String::isNotBlank)?.let { snippet ->
                Text(
                    text = snippet,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = citation.url,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun KnowledgeGraphList(kgPaths: List<KgPath>) {
    if (kgPaths.isEmpty()) {
        EmptyDetailsState()
        return
    }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag(UiTags.RAG_DETAILS_LIST),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        kgPaths.forEachIndexed { pathIndex, path ->
            item(key = "path-header:$pathIndex:${path.queryKeywords.joinToString()}") {
                KgPathHeader(path, pathIndex)
            }
            itemsIndexed(
                items = path.edges,
                key = { edgeIndex, edge ->
                    "path-edge:$pathIndex:$edgeIndex:${edge.sourceId}:${edge.targetId}:${edge.relation}"
                },
            ) { edgeIndex, edge ->
                val source = path.nodes.find { it.id == edge.sourceId }?.label ?: edge.sourceId
                val target = path.nodes.find { it.id == edge.targetId }?.label ?: edge.targetId
                KgRelationshipSentence(
                    source = source,
                    relation = edge.relation,
                    target = target,
                    modifier = Modifier.testTag(UiTags.ragDetailsKgRelation(pathIndex, edgeIndex)),
                )
            }
            path.reasoning?.takeIf(String::isNotBlank)?.let { reasoning ->
                item(key = "path-reasoning:$pathIndex") {
                    Row(
                        modifier = Modifier.padding(start = 36.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lightbulb,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = reasoning,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            if (pathIndex != kgPaths.lastIndex) {
                item(key = "path-divider:$pathIndex") {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun KgPathHeader(path: KgPath, pathIndex: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(UiTags.ragDetailsKgPath(pathIndex))
            .padding(top = 12.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RankLabel(pathIndex + 1)
            Text(
                text = stringResource(R.string.rag_details_path_label, pathIndex + 1),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Text(
            text = stringResource(R.string.rag_details_keywords, path.queryKeywords.joinToString(", ")),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 36.dp),
        )
    }
}

@Composable
private fun KgRelationshipSentence(
    source: String,
    relation: String,
    target: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 36.dp, end = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(source, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        Text(relation, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(target, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RankLabel(rank: Int) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun EmptyDetailsState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Source,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.rag_details_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
