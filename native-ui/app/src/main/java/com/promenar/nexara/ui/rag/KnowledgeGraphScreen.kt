package com.promenar.nexara.ui.rag

import android.app.Application
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.rag.canvas.GraphPhysicsSimulator
import com.promenar.nexara.ui.rag.canvas.InteractiveGraphCanvas

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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                            .clickable { viewModel.setViewMode(mode) }
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
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                if (nodes.isNotEmpty()) {
                    InteractiveGraphCanvas(
                        simulator = simulator,
                        edges = edges,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (isLoading) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary,
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
}
