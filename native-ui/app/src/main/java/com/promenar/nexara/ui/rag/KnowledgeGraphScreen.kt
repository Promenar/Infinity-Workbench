package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.rag.canvas.GraphPhysicsSimulator
import com.promenar.nexara.ui.rag.canvas.InteractiveGraphCanvas
import com.promenar.nexara.ui.testing.UiTags
import com.promenar.nexara.ui.theme.NexaraSpacing

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
    val loadError by viewModel.loadError.collectAsState()
    var showDocumentSelector by remember { mutableStateOf(false) }
    var showAccessibilityList by remember { mutableStateOf(false) }

    // 维持 Native 图谱物理仿真器的协程生命周期
    val scope = rememberCoroutineScope()
    val simulator = remember(scope) { GraphPhysicsSimulator(scope) }

    // 动态同步数据流至力导向模拟器
    LaunchedEffect(nodes, edges) {
        simulator.setData(nodes, edges)
    }

    // 页面销毁时自动注销并清空协程循环以防内存泄漏
    DisposableEffect(simulator) {
        onDispose {
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
                .padding(contentPadding),
        ) {
            Text(
                text = stringResource(R.string.kg_stats_summary, nodes.size, edges.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(
                    KgViewMode.GLOBAL to stringResource(R.string.kg_view_global),
                    KgViewMode.DOCUMENT to stringResource(R.string.kg_view_document),
                    KgViewMode.CONCEPT to stringResource(R.string.kg_filter_concepts),
                ).forEach { (mode, label) ->
                    val isActive = viewMode == mode
                    val backgroundColor by animateColorAsState(
                        if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                        label = "graphModeBackground",
                    )
                    val contentColor by animateColorAsState(
                        if (isActive) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        label = "graphModeContent",
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(backgroundColor)
                            .then(
                                if (isActive) {
                                    Modifier.border(
                                        width = 0.5.dp,
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                                        shape = RoundedCornerShape(8.dp),
                                    )
                                } else {
                                    Modifier
                                },
                            )
                            .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                            .clickable {
                                viewModel.setViewMode(mode)
                                if (mode == KgViewMode.DOCUMENT && selectedDocumentId == null) {
                                    showDocumentSelector = true
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            color = contentColor,
                        )
                    }
                }
            }
            if (viewMode == KgViewMode.DOCUMENT) {
                TextButton(
                    onClick = { showDocumentSelector = true },
                    modifier = Modifier
                        .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                        .testTag(UiTags.KG_DOCUMENT_SELECTOR),
                ) {
                    Text(
                        documentOptions.firstOrNull { it.docId == selectedDocumentId }?.title
                            ?: stringResource(R.string.kg_select_document),
                    )
                }
            }
            loadError?.let {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.kg_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = viewModel::retryLoad,
                        modifier = Modifier.sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget),
                    ) {
                        Text(stringResource(R.string.shared_btn_retry))
                    }
                }
            }
            if (nodes.isNotEmpty()) {
                TextButton(
                    onClick = { showAccessibilityList = true },
                    modifier = Modifier
                        .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                        .testTag(UiTags.KG_LIST_ENTRY),
                ) {
                    Icon(Icons.AutoMirrored.Rounded.List, contentDescription = null)
                    Text(stringResource(R.string.kg_open_accessibility_list))
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
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                if (documentOptions.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(R.string.kg_document_options_empty),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                items(documentOptions, key = { it.docId }) { option ->
                    ListItem(
                        modifier = Modifier
                            .fillMaxWidth()
                            .sizeIn(minHeight = NexaraSpacing.MinimumTouchTarget)
                            .clickable {
                                showDocumentSelector = false
                                viewModel.loadGraphByDoc(option.docId)
                            },
                        headlineContent = { Text(option.title) },
                    )
                }
            }
        }
    }
    if (showAccessibilityList) {
        KnowledgeGraphAccessibilitySheet(
            nodes = nodes,
            edges = edges,
            onDismiss = { showAccessibilityList = false },
        )
    }
}
