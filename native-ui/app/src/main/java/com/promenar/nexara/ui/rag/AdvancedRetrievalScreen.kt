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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraPageLayout
import com.promenar.nexara.ui.common.SettingsSectionHeader
import com.promenar.nexara.ui.common.SettingsToggle
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

@Composable
fun AdvancedRetrievalScreen(
    viewModel: RagViewModel = viewModel(factory = RagViewModel.factory(LocalContext.current.applicationContext as Application)),
    onNavigateBack: () -> Unit
) {
    val config by viewModel.config.collectAsState()
    var queryRewriteStrategy by remember { mutableStateOf("hyde") }

    NexaraPageLayout(
        title = stringResource(R.string.retrieval_title),
        onBack = onNavigateBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Tune, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(18.dp))
                Text(stringResource(R.string.retrieval_title_full), style = NexaraTypography.headlineLarge, color = NexaraColors.Primary)
            }
            Text(
                stringResource(R.string.retrieval_desc),
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.OnSurfaceVariant
            )

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(30.dp).background(NexaraColors.SurfaceContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Memory, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(15.dp))
                        }
                        Text(stringResource(R.string.retrieval_memory_section), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                    }
                    AdaptiveSlider(
                        label = stringResource(R.string.retrieval_memory_limit),
                        value = config.memoryLimit.toFloat(),
                        valueRange = 1f..50f,
                        displayValue = "${config.memoryLimit}",
                        enabled = !config.enableRerank,
                        rerankBadge = config.enableRerank,
                        onValueChange = { v -> viewModel.updateConfig { c -> c.copy(memoryLimit = v.toInt()) } }
                    )
                    AdaptiveSlider(
                        label = stringResource(R.string.retrieval_similarity_threshold),
                        value = config.memoryThreshold,
                        valueRange = 0f..1f,
                        displayValue = "%.2f".format(config.memoryThreshold),
                        enabled = !config.enableRerank,
                        rerankBadge = config.enableRerank,
                        onValueChange = { v -> viewModel.updateConfig { c -> c.copy(memoryThreshold = v) } }
                    )
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.size(30.dp).background(NexaraColors.SurfaceContainer, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.Tune, contentDescription = null, tint = NexaraColors.Primary, modifier = Modifier.size(15.dp))
                        }
                        Text(stringResource(R.string.retrieval_doc_section), style = NexaraTypography.headlineMedium, color = NexaraColors.OnSurface)
                    }
                    AdaptiveSlider(
                        label = stringResource(R.string.retrieval_doc_limit),
                        value = config.docLimit.toFloat(),
                        valueRange = 1f..50f,
                        displayValue = "${config.docLimit}",
                        enabled = !config.enableRerank,
                        rerankBadge = config.enableRerank,
                        onValueChange = { v -> viewModel.updateConfig { c -> c.copy(docLimit = v.toInt()) } }
                    )
                    AdaptiveSlider(
                        label = stringResource(R.string.retrieval_similarity_threshold),
                        value = config.docThreshold,
                        valueRange = 0f..1f,
                        displayValue = "%.2f".format(config.docThreshold),
                        enabled = !config.enableRerank,
                        rerankBadge = config.enableRerank,
                        onValueChange = { v -> viewModel.updateConfig { c -> c.copy(docThreshold = v) } }
                    )
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SettingsSectionHeader(stringResource(R.string.retrieval_hybrid_section))
            SettingsToggle(
                title = stringResource(R.string.retrieval_hybrid_enable),
                description = stringResource(R.string.retrieval_hybrid_desc),
                checked = config.enableHybridSearch,
                onCheckedChange = { viewModel.updateConfig { c -> c.copy(enableHybridSearch = it) } }
            )
                    if (config.enableHybridSearch) {
                        AdaptiveSlider(
                            label = stringResource(R.string.retrieval_vector_weight),
                            value = config.hybridAlpha,
                            valueRange = 0f..1f,
                            displayValue = "${(config.hybridAlpha * 100).toInt()}%",
                            enabled = true,
                            rerankBadge = false,
                            onValueChange = { v -> viewModel.updateConfig { c -> c.copy(hybridAlpha = v) } }
                        )
                        AdaptiveSlider(
                            label = stringResource(R.string.retrieval_bm25_boost),
                            value = config.hybridBM25Boost,
                            valueRange = 0.5f..2f,
                            displayValue = "%.1fx".format(config.hybridBM25Boost),
                            enabled = true,
                            rerankBadge = false,
                            onValueChange = { v -> viewModel.updateConfig { c -> c.copy(hybridBM25Boost = v) } }
                        )
                    }
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SettingsSectionHeader(stringResource(R.string.retrieval_rerank_section))
                    SettingsToggle(
                        title = stringResource(R.string.retrieval_rerank_enable),
                        description = stringResource(R.string.retrieval_rerank_desc),
                        checked = config.enableRerank,
                        onCheckedChange = { enabled -> viewModel.updateConfig { c -> c.copy(enableRerank = enabled) } }
                    )
                    if (config.enableRerank) {
                        AdaptiveSlider(
                            label = stringResource(R.string.retrieval_rerank_top_n),
                            value = config.rerankTopK.toFloat(),
                            valueRange = 5f..100f,
                            displayValue = "${config.rerankTopK}",
                            enabled = true,
                            rerankBadge = false,
                            onValueChange = { v -> viewModel.updateConfig { c -> c.copy(rerankTopK = v.toInt()) } }
                        )
                        AdaptiveSlider(
                            label = stringResource(R.string.retrieval_rerank_final),
                            value = config.rerankFinalK.toFloat(),
                            valueRange = 1f..20f,
                            displayValue = "${config.rerankFinalK}",
                            enabled = true,
                            rerankBadge = false,
                            onValueChange = { v -> viewModel.updateConfig { c -> c.copy(rerankFinalK = v.toInt()) } }
                        )
                    }
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingsSectionHeader(stringResource(R.string.retrieval_rewrite_section))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf("hyde" to "HyDE", "multi-query" to "Multi-Query", "expansion" to "Expansion").forEach { (value, label) ->
                            val isSelected = queryRewriteStrategy == value
                            val bg by animateColorAsState(
                                if (isSelected) NexaraColors.Primary.copy(alpha = 0.12f) else NexaraColors.SurfaceContainer,
                                label = value
                            )
                            val border by animateColorAsState(
                                if (isSelected) NexaraColors.Primary.copy(alpha = 0.3f) else Color.Transparent,
                                label = "$value-b"
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(bg)
                                        .border(1.dp, border, RoundedCornerShape(10.dp))
                                        .clickable {
                                            queryRewriteStrategy = value
                                            viewModel.updateConfig { it.copy(queryRewriteStrategy = value) }
                                        }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        label,
                                        style = NexaraTypography.labelMedium.copy(fontSize = 13.sp),
                                        color = if (isSelected) NexaraColors.Primary else NexaraColors.OnSurface
                                    )
                                }
                            }
                        }
                    }
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = NexaraShapes.large as RoundedCornerShape
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SettingsSectionHeader(stringResource(R.string.retrieval_section_observability))
                    SettingsToggle(
                        title = stringResource(R.string.retrieval_show_progress),
                        description = stringResource(R.string.retrieval_show_progress_desc),
                        checked = config.enableQueryRewrite,
                        onCheckedChange = { enabled -> viewModel.updateConfig { c -> c.copy(enableQueryRewrite = enabled) } },
                        icon = Icons.Rounded.Visibility
                    )
                    SettingsToggle(
                        title = stringResource(R.string.retrieval_show_details),
                        description = stringResource(R.string.retrieval_show_details_desc),
                        checked = config.enableDocs,
                        onCheckedChange = { enabled -> viewModel.updateConfig { c -> c.copy(enableDocs = enabled) } },
                        icon = Icons.Rounded.BarChart
                    )
                    SettingsToggle(
                        title = stringResource(R.string.retrieval_track_metrics),
                        description = stringResource(R.string.retrieval_track_metrics_desc),
                        checked = config.trackRetrievalMetrics,
                        onCheckedChange = { enabled -> viewModel.updateConfig { c -> c.copy(trackRetrievalMetrics = enabled) } },
                        icon = Icons.Rounded.Tune
                    )
                }
            }

            Spacer(modifier = Modifier.height(80.dp))
        }
    }
}

@Composable
private fun AdaptiveSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    displayValue: String,
    enabled: Boolean,
    rerankBadge: Boolean,
    onValueChange: (Float) -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = NexaraTypography.labelMedium, color = NexaraColors.OnSurface.copy(alpha = alpha))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (rerankBadge) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(NexaraColors.Primary.copy(alpha = 0.15f))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            stringResource(R.string.retrieval_rerank_badge),
                            style = NexaraTypography.bodySmall.copy(fontSize = 9.sp, fontFamily = FontFamily.Monospace),
                            color = NexaraColors.Primary
                        )
                    }
                }
                Text(displayValue, style = NexaraTypography.bodySmall, color = if (enabled) NexaraColors.Primary else NexaraColors.OnSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Slider(
            value = value,
            onValueChange = if (enabled) onValueChange else { {} },
            valueRange = valueRange,
            enabled = enabled,
            colors = SliderDefaults.colors(
                thumbColor = if (enabled) NexaraColors.Primary else NexaraColors.SurfaceHighest,
                activeTrackColor = if (enabled) NexaraColors.Primary else NexaraColors.SurfaceHighest,
                inactiveTrackColor = NexaraColors.SurfaceHighest,
                disabledThumbColor = NexaraColors.SurfaceHighest,
                disabledActiveTrackColor = NexaraColors.SurfaceHighest
            )
        )
    }
}
