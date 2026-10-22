package com.promenar.nexara.ui.rag

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import com.promenar.nexara.ui.theme.NexaraMotion
import android.app.Application
import com.promenar.nexara.ui.common.NexaraMetaLine
import androidx.compose.foundation.background
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.nexara.R
import com.promenar.nexara.data.rag.KgDocumentOption
import com.promenar.nexara.data.rag.MAX_GRAPH_NODE_LIMIT
import com.promenar.nexara.ui.common.NexaraBottomSheet
import com.promenar.nexara.ui.common.NexaraSettingsItem
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.bettboxListGroup
import com.promenar.nexara.ui.rag.canvas.GraphPhysicsSimulator
import com.promenar.nexara.ui.rag.canvas.InteractiveGraphCanvas
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing
import com.promenar.nexara.ui.theme.forInput

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeGraphScreen(
    viewModel: KnowledgeGraphViewModel = viewModel(factory = KnowledgeGraphViewModel.factory(LocalContext.current.applicationContext as Application)),
    /** 从“本轮记忆”面板进入时预填的实体搜索，图谱首次加载完成后执行一次。 */
    initialQuery: String? = null,
    onNavigateBack: () -> Unit
) {
    val nodes by viewModel.nodes.collectAsState()
    val edges by viewModel.edges.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val selectedDocumentId by viewModel.selectedDocumentId.collectAsState()
    val documentSelectionRequired by viewModel.documentSelectionRequired.collectAsState()
    val documentOptions by viewModel.documentOptions.collectAsState()
    val documentOptionsError by viewModel.documentOptionsError.collectAsState()
    val loadError by viewModel.loadError.collectAsState()
    val graphState by viewModel.graphState.collectAsState()
    val totalNodeCount by viewModel.totalNodeCount.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val searchFailed by viewModel.searchFailed.collectAsState()
    var showDocumentSelector by remember { mutableStateOf(false) }
    var showAccessibilityList by remember { mutableStateOf(false) }
    var resetKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // 维持 Native 图谱物理仿真器的协程生命周期
    val scope = rememberCoroutineScope()
    val simulator = remember(scope) { GraphPhysicsSimulator(scope) }

    // 动态同步数据流至力导向模拟器
    var initialQueryApplied by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(initialQuery, isLoading) {
        if (!initialQueryApplied && !isLoading && !initialQuery.isNullOrBlank()) {
            initialQueryApplied = true
            viewModel.searchEntities(initialQuery)
        }
    }

    LaunchedEffect(nodes, edges) {
        simulator.setData(nodes, edges)
    }

    // 导航离开或应用退到后台时立即暂停布局与动画。
    DisposableEffect(simulator, lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        val observer = LifecycleEventObserver { _, _ ->
            simulator.setPageVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
        lifecycle.addObserver(observer)
        simulator.setPageVisible(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        onDispose {
            lifecycle.removeObserver(observer)
            simulator.clear()
        }
    }

    NexaraSettingsPageLayout(
        title = stringResource(R.string.kg_title),
        onBack = onNavigateBack,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .bettboxListGroup()
                .padding(NexaraSpacing.Large),
        ) {
            NexaraMetaLine(
                segments = listOf(
                    stringResource(R.string.kg_graph_displayed_count, nodes.size, totalNodeCount),
                    stringResource(R.string.kg_stats_edges, edges.size),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = NexaraSpacing.Medium),
            )
            KnowledgeGraphModeSelector(
                viewMode = viewMode,
                onModeSelected = { mode ->
                    viewModel.setViewMode(mode)
                    if (mode == KgViewMode.DOCUMENT && selectedDocumentId == null) {
                        showDocumentSelector = true
                    }
                },
                onRefresh = viewModel::refreshCurrentScope,
            )
            AnimatedVisibility(visible = viewMode == KgViewMode.DOCUMENT, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Description,
                    title = documentOptions.firstOrNull { it.docId == selectedDocumentId }?.title
                        ?: stringResource(R.string.kg_select_document),
                    onClick = { showDocumentSelector = true },
                    showChevron = true,
                    modifier = Modifier
                        .padding(bottom = NexaraSpacing.Medium)
                        .testTag(UiTags.KG_DOCUMENT_SELECTOR),
                )
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::searchEntities,
                textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                label = { Text(stringResource(R.string.kg_graph_search_entities)) },
                singleLine = true,
                enabled = !documentSelectionRequired,
                modifier = Modifier.fillMaxWidth().padding(bottom = NexaraSpacing.Small),
            )
            // 搜索结果区随输入展开收起，搜索中、失败、无结果与列表之间交叉淡入。
            AnimatedVisibility(visible = searchQuery.isNotBlank(), enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
                val searchPhase = when {
                    isSearching -> KgSearchPhase.Searching
                    searchFailed -> KgSearchPhase.Failed
                    searchResults.isEmpty() -> KgSearchPhase.Empty
                    else -> KgSearchPhase.Results
                }
                AnimatedContent(targetState = searchPhase, transitionSpec = { NexaraMotion.fadeThrough() }, label = "kg_search_phase") { shown ->
                    when (shown) {
                        KgSearchPhase.Searching -> Text(stringResource(R.string.kg_graph_searching), style = MaterialTheme.typography.bodySmall)
                        KgSearchPhase.Failed -> Text(stringResource(R.string.kg_graph_search_failed), color = MaterialTheme.colorScheme.error)
                        KgSearchPhase.Empty -> Text(stringResource(R.string.kg_graph_search_empty), style = MaterialTheme.typography.bodySmall)
                        KgSearchPhase.Results -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                            items(searchResults, key = { it.id }) { entity ->
                                ListItem(
                                    headlineContent = { Text(entity.name) },
                                    supportingContent = { Text(entity.type) },
                                    trailingContent = {
                                        TextButton(
                                            onClick = { viewModel.expandNode(entity.id) },
                                            enabled = !isLoading && nodes.size < MAX_GRAPH_NODE_LIMIT,
                                            modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget),
                                        ) {
                                            Text(stringResource(R.string.kg_graph_expand_neighbors))
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
            AnimatedVisibility(visible = nodes.size >= MAX_GRAPH_NODE_LIMIT, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
                Text(stringResource(R.string.kg_graph_display_limit), style = MaterialTheme.typography.bodySmall)
            }
            AnimatedVisibility(visible = loadError != null, enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = NexaraSpacing.Small)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = NexaraSpacing.Medium, vertical = NexaraSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(kgLoadErrorMessageRes(graphState.isStale)),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = viewModel::retryLoad,
                        modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget),
                    ) {
                        Text(stringResource(R.string.shared_btn_retry))
                    }
                }
            }
            AnimatedVisibility(visible = nodes.isNotEmpty(), enter = NexaraMotion.expandIn, exit = NexaraMotion.shrinkOut) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small)) {
                    FilledTonalButton(
                        onClick = { showAccessibilityList = true },
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = NexaraSpacing.Small)
                            .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                            .testTag(UiTags.KG_LIST_ENTRY),
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = NexaraSpacing.Medium),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.List,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(NexaraSpacing.Small))
                        Text(
                            stringResource(R.string.kg_open_accessibility_list),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = { resetKey++ }, modifier = Modifier.heightIn(min = NexaraSpacing.MinimumTouchTarget)) {
                        Text(stringResource(R.string.kg_graph_reset_view))
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    // 平移缩放后的连线不得画到上方的筛选与搜索控件上。
                    .clipToBounds(),
            ) {
                // 图谱画布、加载、需选文档与空图谱之间交叉淡入；画布阶段内数据增长不触发切换。
                val canvasPhase = when {
                    nodes.isNotEmpty() -> KgCanvasPhase.Graph
                    isLoading -> KgCanvasPhase.Loading
                    documentSelectionRequired -> KgCanvasPhase.DocumentRequired
                    else -> KgCanvasPhase.Empty
                }
                AnimatedContent(
                    targetState = canvasPhase,
                    transitionSpec = { NexaraMotion.fadeThrough() },
                    modifier = Modifier.fillMaxSize(),
                    label = "kg_canvas_phase",
                ) { shown ->
                    when (shown) {
                        KgCanvasPhase.Graph -> InteractiveGraphCanvas(
                            simulator = simulator,
                            edges = edges,
                            summary = stringResource(
                                R.string.kg_canvas_summary,
                                nodes.size,
                                edges.size,
                                when (viewMode) {
                                    KgViewMode.GLOBAL -> stringResource(R.string.kg_view_global)
                                    KgViewMode.DOCUMENT -> stringResource(R.string.kg_view_document)
                                    KgViewMode.CONCEPT -> stringResource(R.string.kg_filter_concepts)
                                },
                                stringResource(R.string.kg_canvas_gestures),
                            ),
                            modifier = Modifier.fillMaxSize(),
                            resetKey = resetKey,
                        )
                        KgCanvasPhase.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            androidx.compose.material3.CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                        KgCanvasPhase.DocumentRequired, KgCanvasPhase.Empty -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = stringResource(
                                    if (shown == KgCanvasPhase.DocumentRequired) R.string.kg_document_required else R.string.kg_empty_graph,
                                ),
                                modifier = Modifier.padding(horizontal = NexaraSpacing.Large),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }

    if (showDocumentSelector) {
        NexaraBottomSheet(
            show = true,
            onDismiss = { showDocumentSelector = false },
            title = stringResource(R.string.kg_select_document),
        ) {
            KnowledgeGraphDocumentOptionsContent(
                options = documentOptions,
                selectedDocumentId = selectedDocumentId,
                loadError = documentOptionsError,
                onRetry = viewModel::retryDocumentOptions,
                onSelect = { docId ->
                    showDocumentSelector = false
                    viewModel.loadGraphByDoc(docId)
                },
            )
        }
    }
    if (showAccessibilityList) {
        KnowledgeGraphAccessibilitySheet(
            nodes = nodes,
            edges = edges,
            onDismiss = { showAccessibilityList = false },
            totalNodeCount = totalNodeCount,
            canExpand = !isLoading && nodes.size < MAX_GRAPH_NODE_LIMIT,
            onExpandNode = viewModel::expandNode,
        )
    }
}

internal fun kgLoadErrorMessageRes(isStale: Boolean): Int =
    if (isStale) R.string.kg_load_failed_stale else R.string.kg_load_failed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KnowledgeGraphModeSelector(
    viewMode: KgViewMode,
    onModeSelected: (KgViewMode) -> Unit,
    onRefresh: () -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = NexaraSpacing.Medium)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(NexaraSpacing.Small),
    ) {
        listOf(
            KgViewMode.GLOBAL to stringResource(R.string.kg_view_global),
            KgViewMode.DOCUMENT to stringResource(R.string.kg_view_document),
            KgViewMode.CONCEPT to stringResource(R.string.kg_filter_concepts),
        ).forEach { (mode, label) ->
            val isActive = viewMode == mode
            FilterChip(
                selected = isActive,
                onClick = { onModeSelected(mode) },
                label = {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                    )
                },
                shape = MaterialTheme.shapes.small,
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
                border = null,
                modifier = Modifier
                    .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                    .testTag(UiTags.kgMode(mode.name)),
            )
        }
        FilledTonalButton(
            onClick = onRefresh,
            modifier = Modifier
                .heightIn(min = NexaraSpacing.MinimumTouchTarget)
                .testTag(UiTags.KG_REFRESH),
            contentPadding = PaddingValues(horizontal = NexaraSpacing.Medium, vertical = NexaraSpacing.Small),
            shape = MaterialTheme.shapes.small,
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(NexaraSpacing.XSmall))
            Text(
                text = stringResource(R.string.workbench_refresh),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

@Composable
internal fun KnowledgeGraphDocumentOptionsContent(
    options: List<KgDocumentOption>,
    selectedDocumentId: String? = null,
    loadError: KgDocumentOptionsError?,
    onRetry: () -> Unit,
    onSelect: (String) -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .bettboxListGroup()
            .selectableGroup(),
    ) {
        when {
            loadError != null -> item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.KG_DOCUMENT_OPTIONS_ERROR)
                        .padding(horizontal = NexaraSpacing.Large, vertical = NexaraSpacing.Small),
                ) {
                    Text(
                        text = stringResource(R.string.kg_document_options_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (loadError.canRetry) {
                        TextButton(
                            onClick = onRetry,
                            modifier = Modifier
                                .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                                .testTag(UiTags.KG_DOCUMENT_OPTIONS_RETRY),
                        ) {
                            Text(stringResource(R.string.shared_btn_retry))
                        }
                    }
                }
            }
            options.isEmpty() -> item {
                Text(
                    text = stringResource(R.string.kg_document_options_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .testTag(UiTags.KG_DOCUMENT_OPTIONS_EMPTY)
                        .padding(NexaraSpacing.Large),
                )
            }
            else -> items(options, key = { it.docId }) { option ->
                ListItem(
                    modifier = Modifier
                        .fillMaxWidth()
                        .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                        .selectable(
                            selected = option.docId == selectedDocumentId,
                            role = Role.RadioButton,
                            onClick = { onSelect(option.docId) },
                        )
                        .testTag(UiTags.kgDocumentOption(option.docId)),
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    headlineContent = { Text(option.title) },
                )
            }
        }
    }
}

private enum class KgSearchPhase { Searching, Failed, Empty, Results }

private enum class KgCanvasPhase { Graph, Loading, DocumentRequired, Empty }
