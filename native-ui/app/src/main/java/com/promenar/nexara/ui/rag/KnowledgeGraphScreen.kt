package com.promenar.nexara.ui.rag

import android.app.Application
import com.promenar.nexara.ui.common.NexaraMetaLine
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.promenar.nexara.R
import com.promenar.nexara.data.rag.KgDocumentOption
import com.promenar.nexara.data.rag.MAX_GRAPH_NODE_LIMIT
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
                .padding(16.dp),
        ) {
            NexaraMetaLine(
                segments = listOf(
                    stringResource(R.string.kg_graph_displayed_count, nodes.size, totalNodeCount),
                    stringResource(R.string.kg_stats_edges, edges.size),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
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
            if (viewMode == KgViewMode.DOCUMENT) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable { showDocumentSelector = true }
                        .testTag(UiTags.KG_DOCUMENT_SELECTOR)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = documentOptions.firstOrNull { it.docId == selectedDocumentId }?.title
                                ?: stringResource(R.string.kg_select_document),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            imageVector = Icons.Rounded.ArrowDropDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::searchEntities,
                textStyle = MaterialTheme.typography.bodyLarge.forInput(),
                label = { Text(stringResource(R.string.kg_graph_search_entities)) },
                singleLine = true,
                enabled = !documentSelectionRequired,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            )
            if (searchQuery.isNotBlank()) {
                when {
                    isSearching -> Text(stringResource(R.string.kg_graph_searching), style = MaterialTheme.typography.bodySmall)
                    searchFailed -> Text(stringResource(R.string.kg_graph_search_failed), color = MaterialTheme.colorScheme.error)
                    searchResults.isEmpty() -> Text(stringResource(R.string.kg_graph_search_empty), style = MaterialTheme.typography.bodySmall)
                    else -> LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 160.dp)) {
                        items(searchResults, key = { it.id }) { entity ->
                            ListItem(
                                headlineContent = { Text(entity.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(entity.type) },
                                trailingContent = {
                                    TextButton(onClick = { viewModel.expandNode(entity.id) }, enabled = !isLoading && nodes.size < MAX_GRAPH_NODE_LIMIT) {
                                        Text(stringResource(R.string.kg_graph_expand_neighbors))
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (nodes.size >= MAX_GRAPH_NODE_LIMIT) {
                Text(stringResource(R.string.kg_graph_display_limit), style = MaterialTheme.typography.bodySmall)
            }
            loadError?.let {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
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
                        modifier = Modifier.height(36.dp),
                    ) {
                        Text(stringResource(R.string.shared_btn_retry))
                    }
                }
            }
            if (nodes.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = { showAccessibilityList = true },
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = 8.dp)
                            .heightIn(min = 48.dp)
                            .testTag(UiTags.KG_LIST_ENTRY),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.List,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            stringResource(R.string.kg_open_accessibility_list),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    TextButton(onClick = { resetKey++ }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.kg_graph_reset_view))
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (nodes.isNotEmpty()) {
                    InteractiveGraphCanvas(
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
                } else if (isLoading) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else if (documentSelectionRequired) {
                    Text(
                        text = stringResource(R.string.kg_document_required),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.kg_empty_graph),
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    if (showDocumentSelector) {
        ModalBottomSheet(onDismissRequest = { showDocumentSelector = false }) {
            Text(
                text = stringResource(R.string.kg_select_document),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
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
                    shape = RoundedCornerShape(8.dp),
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    border = null,
                    modifier = Modifier.testTag(UiTags.kgMode(mode.name)),
                )
            }
        }

        FilledTonalButton(
            onClick = onRefresh,
            modifier = Modifier
                .height(34.dp)
                .testTag(UiTags.KG_REFRESH),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            shape = RoundedCornerShape(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Refresh,
                contentDescription = null,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(4.dp))
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
            .selectableGroup(),
    ) {
        when {
            loadError != null -> item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag(UiTags.KG_DOCUMENT_OPTIONS_ERROR)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
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
                        .padding(16.dp),
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
                    headlineContent = { Text(option.title) },
                )
            }
        }
    }
}
