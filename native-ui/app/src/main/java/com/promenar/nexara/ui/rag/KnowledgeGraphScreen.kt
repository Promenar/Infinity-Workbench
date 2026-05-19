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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraBackButton
import com.promenar.nexara.ui.rag.canvas.GraphPhysicsSimulator
import com.promenar.nexara.ui.rag.canvas.InteractiveGraphCanvas
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

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

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column(modifier = Modifier.padding(start = 4.dp)) {
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
                        NexaraBackButton(onClick = onNavigateBack)
                    },

                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f)
                    )
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        KgViewMode.GLOBAL to stringResource(R.string.kg_view_global),
                        KgViewMode.DOCUMENT to stringResource(R.string.kg_view_document),
                        KgViewMode.CONCEPT to stringResource(R.string.kg_filter_concepts)
                    ).forEach { (mode, label) ->
                        val isActive = viewMode == mode
                        val bgColor by animateColorAsState(
                            if (isActive) NexaraColors.Primary.copy(alpha = 0.12f) else NexaraColors.SurfaceHigh,
                            label = "tabBg"
                        )
                        val textColor by animateColorAsState(
                            if (isActive) NexaraColors.Primary else NexaraColors.OnSurfaceVariant,
                            label = "tabText"
                        )
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(bgColor)
                                .then(
                                    if (isActive) Modifier.border(
                                        0.5.dp,
                                        NexaraColors.Primary.copy(alpha = 0.3f),
                                        RoundedCornerShape(8.dp)
                                    ) else Modifier
                                )
                                .clickable { viewModel.setViewMode(mode) }
                                .padding(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text(
                                label,
                                style = NexaraTypography.labelMedium.copy(fontSize = 12.sp),
                                color = textColor
                            )
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (nodes.isNotEmpty()) {
                InteractiveGraphCanvas(
                    simulator = simulator,
                    edges = edges,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (isLoading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = NexaraColors.Primary
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        stringResource(R.string.kg_empty_graph),
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnSurfaceVariant
                    )
                }
            }
        }
    }
}
