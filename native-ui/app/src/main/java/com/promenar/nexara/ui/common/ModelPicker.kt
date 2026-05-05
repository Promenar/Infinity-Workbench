package com.promenar.nexara.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeveloperBoard
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.promenar.nexara.R
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography
import kotlinx.coroutines.delay

enum class ModelCapability {
    REASONING, VISION, WEB, RERANK, EMBEDDING, CHAT
}

data class ModelItem(
    val id: String,
    val name: String,
    val providerName: String,
    val capabilities: List<ModelCapability>,
    val contextLength: Int? = null
)

private val capabilityColors: Map<ModelCapability, Pair<Color, Color>> = mapOf(
    ModelCapability.REASONING to (Color(0xFFA78BFA) to Color(0xFF1E1B4B)),
    ModelCapability.VISION to (Color(0xFFF472B6) to Color(0xFF4A1942)),
    ModelCapability.WEB to (Color(0xFF38BDF8) to Color(0xFF0C2D48)),
    ModelCapability.RERANK to (Color(0xFFFB923C) to Color(0xFF431407)),
    ModelCapability.EMBEDDING to (Color(0xFF22D3EE) to Color(0xFF083344)),
    ModelCapability.CHAT to (Color(0xFF34D399) to Color(0xFF022C22))
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelPicker(
    show: Boolean,
    onDismiss: () -> Unit,
    onSelect: (modelId: String, modelName: String) -> Unit,
    currentModelId: String = "",
    models: List<ModelItem> = emptyList()
) {
    if (!show) return

    var searchQuery by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    LaunchedEffect(searchQuery) {
        delay(150)
        debouncedQuery = searchQuery
    }

    val filteredModels = remember(debouncedQuery, models) {
        if (debouncedQuery.isBlank()) models
        else models.filter {
            it.name.contains(debouncedQuery, ignoreCase = true) ||
                    it.providerName.contains(debouncedQuery, ignoreCase = true)
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = NexaraColors.SurfaceContainer,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            Text(
                text = stringResource(R.string.common_model_picker_title),
                style = NexaraTypography.headlineMedium,
                color = NexaraColors.OnSurface
            )

            Spacer(modifier = Modifier.height(16.dp))

            NexaraSearchBar(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = stringResource(R.string.common_model_picker_search)
            )

            Spacer(modifier = Modifier.height(12.dp))

            if (filteredModels.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Rounded.DeveloperBoard,
                            contentDescription = null,
                            tint = NexaraColors.Outline,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.common_model_picker_empty),
                            style = NexaraTypography.bodyMedium,
                            color = NexaraColors.OnSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(filteredModels, key = { it.id }) { model ->
                        val isSelected = model.id == currentModelId

                        NexaraGlassCard(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(model.id, model.name) },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Memory,
                                    contentDescription = null,
                                    tint = NexaraColors.Primary,
                                    modifier = Modifier.size(16.dp)
                                )

                                Spacer(modifier = Modifier.width(8.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = model.name,
                                        style = NexaraTypography.bodyMedium.copy(
                                            fontWeight = FontWeight.Bold
                                        ),
                                        color = NexaraColors.OnSurface
                                    )

                                    Spacer(modifier = Modifier.height(2.dp))

                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Rounded.Storage,
                                            contentDescription = null,
                                            tint = NexaraColors.OnSurfaceVariant,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = model.providerName,
                                            style = NexaraTypography.labelMedium.copy(fontSize = 11.sp),
                                            color = NexaraColors.OnSurfaceVariant
                                        )
                                    }

                                    if (model.capabilities.isNotEmpty()) {
                                        Spacer(modifier = Modifier.height(6.dp))
                                        FlowRow(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            model.capabilities.forEach { cap ->
                                                val (fg, bg) = capabilityColors[cap]!!
                                                Box(
                                                    modifier = Modifier
                                                        .background(bg, RoundedCornerShape(50))
                                                        .border(
                                                            0.5.dp,
                                                            fg.copy(alpha = 0.3f),
                                                            RoundedCornerShape(50)
                                                        )
                                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                                ) {
                                                    Text(
                                                        text = cap.name.lowercase()
                                                            .replaceFirstChar { it.uppercase() },
                                                        style = NexaraTypography.labelMedium.copy(
                                                            fontSize = 10.sp
                                                        ),
                                                        color = fg
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (model.contextLength != null) {
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "${model.contextLength / 1000}${stringResource(R.string.common_model_picker_k_context)}",
                                            style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                                            color = NexaraColors.Outline
                                        )
                                    }
                                }

                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = stringResource(R.string.common_model_picker_selected),
                                        tint = NexaraColors.Primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
