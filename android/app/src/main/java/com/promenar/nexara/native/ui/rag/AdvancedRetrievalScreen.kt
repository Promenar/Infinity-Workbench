package com.promenar.nexara.native.ui.rag

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.native.ui.common.NexaraGlassCard
import com.promenar.nexara.native.ui.theme.NexaraColors
import com.promenar.nexara.native.ui.theme.NexaraShapes
import com.promenar.nexara.native.ui.theme.NexaraTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedRetrievalScreen(
    onNavigateBack: () -> Unit
) {
    var topKValue by remember { mutableFloatStateOf(5f) }
    var hybridSearchEnabled by remember { mutableStateOf(true) }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text("", style = NexaraTypography.headlineLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = NexaraColors.OnSurface)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f)
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp,
                top = 8.dp, bottom = 120.dp
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Header
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Rounded.Tune, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(20.dp))
                        Text("CONFIGURATION", style = NexaraTypography.labelMedium, color = NexaraColors.Primary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Advanced Retrieval", style = NexaraTypography.headlineLarge, color = NexaraColors.OnSurface)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Fine-tune the RAG (Retrieval-Augmented Generation) pipeline parameters to optimize context extraction, relevance filtering, and query execution.",
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnSurfaceVariant
                    )
                }
            }

            // Bento Grid Block 1: Memory Retrieval
            item {
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        // Card Header
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .background(NexaraColors.SurfaceContainer, androidx.compose.foundation.shape.CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Memory, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(16.dp))
                            }
                            Text("Memory Retrieval", style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                        }

                        // Divider
                        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(NexaraColors.GlassBorder))

                        // Top-K Slider
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Top-K Documents", style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                                Text("${topKValue.toInt()}", style = NexaraTypography.bodySmall, color = NexaraColors.Primary)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Slider(
                                value = topKValue,
                                onValueChange = { topKValue = it },
                                valueRange = 1f..20f,
                                steps = 18,
                                colors = SliderDefaults.colors(
                                    thumbColor = NexaraColors.Primary,
                                    activeTrackColor = NexaraColors.Primary,
                                    inactiveTrackColor = NexaraColors.SurfaceHighest
                                )
                            )
                            Text(
                                "Determines the maximum number of context chunks retrieved per query.",
                                style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                                color = NexaraColors.OnSurfaceVariant
                            )
                        }

                        // Hybrid Search Toggle
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Hybrid Search Strategy", style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                                Text(
                                    "Combines dense vector similarity with sparse BM25 keyword matching.",
                                    style = NexaraTypography.bodyMedium.copy(fontSize = 12.sp),
                                    color = NexaraColors.OnSurfaceVariant
                                )
                            }
                            Switch(
                                checked = hybridSearchEnabled,
                                onCheckedChange = { hybridSearchEnabled = it },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = NexaraColors.OnPrimary,
                                    checkedTrackColor = NexaraColors.Primary,
                                    uncheckedThumbColor = NexaraColors.Outline,
                                    uncheckedTrackColor = NexaraColors.SurfaceContainer
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
