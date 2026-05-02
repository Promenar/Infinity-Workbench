package com.promenar.nexara.native.ui.rag

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.promenar.nexara.native.ui.common.NexaraGlassCard
import com.promenar.nexara.native.ui.common.NexaraSettingsItem
import com.promenar.nexara.native.ui.theme.NexaraColors
import com.promenar.nexara.native.ui.theme.NexaraShapes
import com.promenar.nexara.native.ui.theme.NexaraTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RagHomeScreen(
    onNavigateToDetail: () -> Unit
) {
    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text("Knowledge Base", style = NexaraTypography.headlineLarge) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f),
                    titleContentColor = NexaraColors.OnSurface
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
                top = 24.dp, bottom = 120.dp
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp) // mb-xl separation
        ) {
            // Header Description
            item {
                Text(
                    text = "Centralized intelligence repository and vector storage.",
                    style = NexaraTypography.bodyLarge,
                    color = NexaraColors.OnSurfaceVariant
                )
            }

            // Portal View Cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        PortalCard(
                            icon = Icons.Rounded.Description,
                            title = "Documents",
                            subtitle = "1,204 Indexed",
                            onClick = onNavigateToDetail
                        )
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        PortalCard(
                            icon = Icons.Rounded.Psychology,
                            title = "Memory",
                            subtitle = "8.5GB Allocated",
                            onClick = onNavigateToDetail
                        )
                    }
                }
            }

            // Collections List
            item {
                Text(
                    text = "Collections",
                    style = NexaraTypography.headlineMedium,
                    color = NexaraColors.OnSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            item {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Folder,
                    title = "Codebase Index",
                    subtitle = "852 Files • Syncing",
                    onClick = onNavigateToDetail
                )
            }
            item {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Folder,
                    title = "Design System",
                    subtitle = "124 Files • Up to date",
                    onClick = onNavigateToDetail
                )
            }
            item {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Folder,
                    title = "Meeting Transcripts",
                    subtitle = "45 Files • Pending",
                    onClick = onNavigateToDetail
                )
            }
        }
    }
}

@Composable
private fun PortalCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    NexaraGlassCard(
        modifier = Modifier.fillMaxWidth(),
        shape = NexaraShapes.large as RoundedCornerShape,
        onClick = onClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp), // p-lg
            verticalArrangement = Arrangement.spacedBy(16.dp) // gap-md
        ) {
            // Icon box: w-10 h-10 rounded-lg bg-indigo-500/10 border border-indigo-500/20
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(NexaraColors.Primary.copy(alpha = 0.1f))
                    .border(1.dp, NexaraColors.Primary.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NexaraColors.Primary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column {
                Text(
                    text = title,
                    style = NexaraTypography.headlineMedium,
                    color = NexaraColors.OnSurface
                )
                Spacer(modifier = Modifier.height(4.dp)) // mt-xs
                Text(
                    text = subtitle,
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurfaceVariant
                )
            }
        }
    }
}
