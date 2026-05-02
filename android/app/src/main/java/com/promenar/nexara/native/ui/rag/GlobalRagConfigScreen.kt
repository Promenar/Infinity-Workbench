package com.promenar.nexara.native.ui.rag

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.promenar.nexara.native.ui.common.NexaraGlassCard
import com.promenar.nexara.native.ui.theme.NexaraColors
import com.promenar.nexara.native.ui.theme.NexaraShapes
import com.promenar.nexara.native.ui.theme.NexaraTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlobalRagConfigScreen(
    onNavigateBack: () -> Unit
) {
    var selectedPreset by remember { mutableStateOf("Balanced") }

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
            verticalArrangement = Arrangement.spacedBy(32.dp) // space-y-lg
        ) {
            // Header
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text("RAG Configuration", style = NexaraTypography.headlineLarge, color = NexaraColors.OnSurface)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Fine-tune global retrieval augmented generation logic for optimal AI performance across operations.",
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnSurfaceVariant
                    )
                }
            }

            // Presets Bento
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = "CONFIGURATION PRESETS",
                        style = NexaraTypography.labelMedium,
                        color = NexaraColors.OnSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            PresetCard(
                                icon = Icons.Rounded.Tune,
                                title = "Balanced",
                                isSelected = selectedPreset == "Balanced",
                                onClick = { selectedPreset = "Balanced" }
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            PresetCard(
                                icon = Icons.Rounded.Edit,
                                title = "Writing",
                                isSelected = selectedPreset == "Writing",
                                onClick = { selectedPreset = "Writing" }
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            PresetCard(
                                icon = Icons.Rounded.Code,
                                title = "Coding",
                                isSelected = selectedPreset == "Coding",
                                onClick = { selectedPreset = "Coding" }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresetCard(
    icon: ImageVector,
    title: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    // If selected, it has primary text color. Otherwise, Outline (Zinc-400 equivalent).
    val tint = if (isSelected) NexaraColors.Primary else NexaraColors.Outline
    val textColor = if (isSelected) NexaraColors.OnSurface else NexaraColors.OnSurfaceVariant

    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaraShapes.large as RoundedCornerShape,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp), // p-md
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = tint,
                modifier = Modifier.size(28.dp) // text-[28px]
            )
            Text(
                text = title,
                style = NexaraTypography.labelMedium,
                color = textColor
            )
        }
    }
}
