package com.promenar.nexara.ui.rag

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.data.rag.VectorStats
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagDebugScreen(
    viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(LocalContext.current.applicationContext as android.app.Application)),
    onNavigateBack: () -> Unit
) {
    val vectorStats by viewModel.vectorStats.collectAsState()

    NexaraPageLayout(
        title = stringResource(R.string.rag_debug_title),
        onBack = onNavigateBack,
        actions = {
            IconButton(onClick = { viewModel.loadCollections() }) {
                Icon(
                    Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.workbench_refresh),
                    tint = NexaraColors.Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(NexaraColors.SurfaceHigh, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Storage,
                        contentDescription = null,
                        tint = NexaraColors.Primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    stringResource(R.string.rag_debug_desc),
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurfaceVariant
                )
            }

            val stats = vectorStats
            if (stats != null) {
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "${"%,d".format(stats.total)}",
                            style = NexaraTypography.headlineLarge.copy(fontWeight = FontWeight.Black),
                            color = NexaraColors.Primary
                        )
                        Text(
                            stringResource(R.string.rag_debug_total_vectors, stats.storageSizeMb),
                            style = NexaraTypography.labelMedium,
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                }

                SettingsSectionHeader(stringResource(R.string.rag_debug_section_types))
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        DistributionRow(
                            label = stringResource(R.string.rag_debug_doc_vectors),
                            count = stats.byType.doc,
                            total = stats.total,
                            color = NexaraColors.Primary
                        )
                        DistributionRow(
                            label = stringResource(R.string.rag_debug_memory_vectors),
                            count = stats.byType.memory,
                            total = stats.total,
                            color = NexaraColors.StatusInfo
                        )
                        DistributionRow(
                            label = stringResource(R.string.rag_debug_summary_vectors),
                            count = stats.byType.summary,
                            total = stats.total,
                            color = NexaraColors.Tertiary
                        )
                    }
                }

                SettingsSectionHeader(stringResource(R.string.rag_debug_section_health))
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                             Text(stringResource(R.string.rag_debug_redundancy), style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
                            Text(
                                "${"%.1f".format(stats.redundancyRate * 100)}%",
                                style = NexaraTypography.bodySmall.copy(
                                    color = if (stats.redundancyRate > 0.2f) NexaraColors.Error else NexaraColors.StatusSuccess
                                )
                            )
                        }
                        LinearProgressIndicator(
                            progress = { stats.redundancyRate.coerceIn(0f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = if (stats.redundancyRate > 0.2f) NexaraColors.Error else NexaraColors.StatusSuccess,
                            trackColor = NexaraColors.SurfaceHighest
                        )

                        if (stats.redundancyRate > 0.01f) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(NexaraColors.StatusWarning.copy(alpha = 0.1f))
                                    .border(0.5.dp, NexaraColors.StatusWarning.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .clickable { }
                                    .padding(12.dp),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = NexaraColors.StatusWarning,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    stringResource(R.string.rag_debug_cleanup),
                                    style = NexaraTypography.labelMedium,
                                    color = NexaraColors.StatusWarning
                                )
                            }
                        }
                    }
                }

                SettingsSectionHeader(stringResource(R.string.rag_debug_section_sessions))
                val topSessions = stats.bySession.take(5)
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        topSessions.forEachIndexed { index, session ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(24.dp)
                                            .background(NexaraColors.SurfaceHigh, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            "${index + 1}",
                                            style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                            color = NexaraColors.OnSurfaceVariant
                                        )
                                    }
                                    Text(
                                        session.sessionId.take(12),
                                        style = NexaraTypography.labelMedium,
                                        color = NexaraColors.OnSurface
                                    )
                                }
                                Text(
                                    stringResource(R.string.rag_debug_vectors_count, "%,d".format(session.count)),
                                    style = NexaraTypography.bodySmall.copy(fontSize = 12.sp),
                                    color = NexaraColors.Primary
                                )
                            }
                        }
                    }
                }
            } else {
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = NexaraShapes.large as RoundedCornerShape
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Rounded.Storage,
                            contentDescription = null,
                            tint = NexaraColors.OnSurfaceVariant,
                            modifier = Modifier.size(40.dp)
                        )
                        Text(
                            stringResource(R.string.rag_debug_empty),
                            style = NexaraTypography.headlineMedium,
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun DistributionRow(
    label: String,
    count: Int,
    total: Int,
    color: androidx.compose.ui.graphics.Color
) {
    val fraction = if (total > 0) count.toFloat() / total else 0f
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface)
            Text("${"%,d".format(count)}", style = NexaraTypography.bodySmall, color = color)
        }
        LinearProgressIndicator(
            progress = { fraction },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = color,
            trackColor = NexaraColors.SurfaceHighest
        )
    }
}
