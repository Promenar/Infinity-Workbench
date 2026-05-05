package com.promenar.nexara.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

@Composable
fun AgentAdvancedRetrievalScreen(
    agentId: String,
    scopeLabel: String,
    onNavigateBack: () -> Unit
) {
    var useInherited by remember { mutableStateOf(true) }
    var showResetConfirm by remember { mutableStateOf(false) }

    var memoryLimit by remember { mutableFloatStateOf(5f) }
    var memoryThreshold by remember { mutableFloatStateOf(0.7f) }
    var docLimit by remember { mutableFloatStateOf(8f) }
    var docThreshold by remember { mutableFloatStateOf(0.45f) }
    var enableRerank by remember { mutableStateOf(false) }
    var rerankTopK by remember { mutableFloatStateOf(30f) }
    var rerankFinalK by remember { mutableFloatStateOf(5f) }
    var enableQueryRewrite by remember { mutableStateOf(false) }
    var queryRewriteStrategy by remember { mutableStateOf("multi-query") }
    var queryRewriteCount by remember { mutableFloatStateOf(3f) }
    var enableHybridSearch by remember { mutableStateOf(false) }
    var hybridAlpha by remember { mutableFloatStateOf(0.6f) }
    var hybridBM25Boost by remember { mutableFloatStateOf(1.0f) }

    ConfirmDialog(
        show = showResetConfirm,
        onDismiss = { showResetConfirm = false },
        onConfirm = {
            useInherited = true
            memoryLimit = 5f; memoryThreshold = 0.7f
            docLimit = 8f; docThreshold = 0.45f
            enableRerank = false; rerankTopK = 30f; rerankFinalK = 5f
            enableQueryRewrite = false; queryRewriteStrategy = "multi-query"; queryRewriteCount = 3f
            enableHybridSearch = false; hybridAlpha = 0.6f; hybridBM25Boost = 1.0f
            showResetConfirm = false
        },
        title = stringResource(R.string.agent_rag_reset_title),
        description = stringResource(R.string.agent_rag_reset_message),
        confirmLabel = stringResource(R.string.shared_btn_reset),
        confirmColor = NexaraColors.StatusWarning,
        destructive = true
    )

    NexaraPageLayout(
        title = stringResource(R.string.agent_retrieval_title),
        onBack = onNavigateBack
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.BookmarkAdded,
                    contentDescription = null,
                    tint = NexaraColors.Primary,
                    modifier = Modifier.size(20.dp)
                )
                Text(
                    text = scopeLabel,
                    style = NexaraTypography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = NexaraColors.Primary
                )
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    if (useInherited) NexaraColors.Primary.copy(alpha = 0.15f)
                                    else NexaraColors.StatusWarning.copy(alpha = 0.15f)
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (useInherited) stringResource(R.string.agent_rag_status_inherited) else stringResource(R.string.agent_rag_status_custom),
                                style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                color = if (useInherited) NexaraColors.Primary else NexaraColors.StatusWarning
                            )
                        }
                    }
                    if (!useInherited) {
                        TextButton(onClick = { showResetConfirm = true }) {
                            Icon(
                                imageVector = Icons.Rounded.RestartAlt,
                                contentDescription = null,
                                tint = NexaraColors.StatusWarning,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.shared_btn_reset),
                                style = NexaraTypography.labelMedium,
                                color = NexaraColors.StatusWarning
                            )
                        }
                    }
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.agent_retrieval_section_memory),
                        style = NexaraTypography.headlineMedium,
                        color = NexaraColors.OnSurface
                    )
                    Box(
                        modifier = Modifier.fillMaxWidth().height(0.5.dp).background(NexaraColors.GlassBorder)
                    )
                    RetrievalParamSlider(
                        label = stringResource(R.string.agent_retrieval_memory_limit),
                        value = memoryLimit,
                        valueRange = 3f..10f,
                        step = 1f,
                        displayValue = "${memoryLimit.toInt()}",
                        onValueChange = { memoryLimit = it; useInherited = false }
                    )
                    RetrievalParamSlider(
                        label = stringResource(R.string.agent_retrieval_memory_threshold),
                        value = memoryThreshold,
                        valueRange = 0.5f..0.95f,
                        step = 0.05f,
                        displayValue = "${(memoryThreshold * 100).toInt()}%",
                        onValueChange = { memoryThreshold = it; useInherited = false }
                    )
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = stringResource(R.string.agent_retrieval_section_document),
                        style = NexaraTypography.headlineMedium,
                        color = NexaraColors.OnSurface
                    )
                    Box(
                        modifier = Modifier.fillMaxWidth().height(0.5.dp).background(NexaraColors.GlassBorder)
                    )
                    RetrievalParamSlider(
                        label = stringResource(R.string.agent_retrieval_doc_limit),
                        value = docLimit,
                        valueRange = 5f..15f,
                        step = 1f,
                        displayValue = "${docLimit.toInt()}",
                        onValueChange = { docLimit = it; useInherited = false }
                    )
                    RetrievalParamSlider(
                        label = stringResource(R.string.agent_retrieval_doc_threshold),
                        value = docThreshold,
                        valueRange = 0.3f..0.8f,
                        step = 0.05f,
                        displayValue = "${(docThreshold * 100).toInt()}%",
                        onValueChange = { docThreshold = it; useInherited = false }
                    )
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.agent_retrieval_section_rerank),
                            style = NexaraTypography.headlineMedium,
                            color = NexaraColors.OnSurface
                        )
                        Switch(
                            checked = enableRerank,
                            onCheckedChange = {
                                enableRerank = it
                                useInherited = false
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NexaraColors.OnPrimary,
                                checkedTrackColor = NexaraColors.Primary,
                                uncheckedThumbColor = NexaraColors.Outline,
                                uncheckedTrackColor = NexaraColors.SurfaceContainer
                            )
                        )
                    }
                    if (enableRerank) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(0.5.dp).background(NexaraColors.GlassBorder)
                        )
                        RetrievalParamSlider(
                            label = stringResource(R.string.agent_retrieval_recall_count),
                            value = rerankTopK,
                            valueRange = 10f..100f,
                            step = 5f,
                            displayValue = "${rerankTopK.toInt()}",
                            onValueChange = { rerankTopK = it; useInherited = false }
                        )
                        RetrievalParamSlider(
                            label = stringResource(R.string.agent_retrieval_final_count),
                            value = rerankFinalK,
                            valueRange = 3f..20f,
                            step = 1f,
                            displayValue = "${rerankFinalK.toInt()}",
                            onValueChange = { rerankFinalK = it; useInherited = false }
                        )
                    }
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.agent_retrieval_section_rewrite),
                            style = NexaraTypography.headlineMedium,
                            color = NexaraColors.OnSurface
                        )
                        Switch(
                            checked = enableQueryRewrite,
                            onCheckedChange = {
                                enableQueryRewrite = it
                                useInherited = false
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NexaraColors.OnPrimary,
                                checkedTrackColor = NexaraColors.Primary,
                                uncheckedThumbColor = NexaraColors.Outline,
                                uncheckedTrackColor = NexaraColors.SurfaceContainer
                            )
                        )
                    }
                    if (enableQueryRewrite) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(0.5.dp).background(NexaraColors.GlassBorder)
                        )
                        Text(
                            text = stringResource(R.string.agent_retrieval_strategy_label),
                            style = NexaraTypography.labelMedium,
                            color = NexaraColors.OnSurfaceVariant
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                "hyde" to stringResource(R.string.agent_retrieval_strategy_hyde),
                                "multi-query" to stringResource(R.string.agent_retrieval_strategy_multi),
                                "expansion" to stringResource(R.string.agent_retrieval_strategy_expansion)
                            ).forEach { (id, label) ->
                                val isSelected = queryRewriteStrategy == id
                                FilterChip(
                                    selected = isSelected,
                                    onClick = {
                                        queryRewriteStrategy = id
                                        useInherited = false
                                    },
                                    label = {
                                        Text(
                                            text = label,
                                            style = NexaraTypography.labelMedium.copy(fontSize = 11.sp)
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = NexaraColors.Primary.copy(alpha = 0.2f),
                                        selectedLabelColor = NexaraColors.Primary
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        RetrievalParamSlider(
                            label = stringResource(R.string.agent_retrieval_variant_count),
                            value = queryRewriteCount,
                            valueRange = 2f..5f,
                            step = 1f,
                            displayValue = "${queryRewriteCount.toInt()}",
                            onValueChange = { queryRewriteCount = it; useInherited = false }
                        )
                    }
                }
            }

            NexaraGlassCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.agent_retrieval_section_hybrid),
                            style = NexaraTypography.headlineMedium,
                            color = NexaraColors.OnSurface
                        )
                        Switch(
                            checked = enableHybridSearch,
                            onCheckedChange = {
                                enableHybridSearch = it
                                useInherited = false
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = NexaraColors.OnPrimary,
                                checkedTrackColor = NexaraColors.Primary,
                                uncheckedThumbColor = NexaraColors.Outline,
                                uncheckedTrackColor = NexaraColors.SurfaceContainer
                            )
                        )
                    }
                    if (enableHybridSearch) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(0.5.dp).background(NexaraColors.GlassBorder)
                        )
                        RetrievalParamSlider(
                            label = stringResource(R.string.agent_retrieval_vector_weight),
                            value = hybridAlpha,
                            valueRange = 0f..1f,
                            step = 0.05f,
                            displayValue = "${(hybridAlpha * 100).toInt()}%",
                            onValueChange = { hybridAlpha = it; useInherited = false }
                        )
                        RetrievalParamSlider(
                            label = stringResource(R.string.agent_retrieval_bm25_boost),
                            value = hybridBM25Boost,
                            valueRange = 0.5f..2.0f,
                            step = 0.1f,
                            displayValue = String.format("%.1fx", hybridBM25Boost),
                            onValueChange = { hybridBM25Boost = it; useInherited = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RetrievalParamSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    step: Float,
    displayValue: String,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = NexaraTypography.labelMedium,
                color = NexaraColors.OnSurfaceVariant
            )
            Text(
                text = displayValue,
                style = NexaraTypography.bodySmall,
                color = NexaraColors.Primary
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = ((valueRange.endInclusive - valueRange.start) / step).toInt() - 1,
            colors = SliderDefaults.colors(
                thumbColor = NexaraColors.Primary,
                activeTrackColor = NexaraColors.Primary,
                inactiveTrackColor = NexaraColors.SurfaceHighest
            )
        )
    }
}
