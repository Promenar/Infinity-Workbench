package com.promenar.nexara.native.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
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
fun AgentSessionsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToChat: () -> Unit
) {
    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text("Super Assistant", style = NexaraTypography.headlineLarge) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = NexaraColors.OnSurface)
                    }
                },
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
                top = 16.dp, bottom = 120.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Search Bar
            item {
                NexaraGlassCard(
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null, tint = NexaraColors.OnSurfaceVariant)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("Search sessions...", style = NexaraTypography.bodyMedium, color = NexaraColors.OnSurfaceVariant)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Session List
            item {
                SessionCard(
                    title = "React Native WebView Refactor",
                    time = "2 hours ago",
                    preview = "Help me analyze this Bridge protocol...",
                    tag = "DEVELOPMENT",
                    onClick = onNavigateToChat
                )
            }
            item {
                SessionCard(
                    title = "Knowledge Base Retrieval Fix",
                    time = "Yesterday",
                    preview = "The retrieved Chunks quantity is incorrect...",
                    tag = "AI LOGIC",
                    onClick = onNavigateToChat
                )
            }
        }
    }
}

@Composable
private fun SessionCard(
    title: String,
    time: String,
    preview: String,
    tag: String,
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
                .padding(24.dp) // p-lg
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = title,
                    style = NexaraTypography.headlineMedium.copy(fontSize = 17.sp), // text-body-lg font-semibold
                    color = NexaraColors.OnSurface,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = time,
                    style = NexaraTypography.labelMedium.copy(fontSize = 12.sp),
                    color = NexaraColors.OnSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(8.dp)) // mb-xs gap equivalent
            
            Text(
                text = preview,
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.OnSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1
            )
            
            Spacer(modifier = Modifier.height(16.dp)) // mt-md
            
            // Tags
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(NexaraColors.GlassSurface)
                        .border(0.5.dp, NexaraColors.Primary.copy(alpha = 0.2f), RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 4.dp) // px-sm py-xs
                ) {
                    Text(
                        text = tag,
                        style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                        color = NexaraColors.Primary
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(NexaraColors.GlassSurface)
                        .border(0.5.dp, NexaraColors.GlassBorder, RoundedCornerShape(50))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "ACTIVE",
                        style = NexaraTypography.labelMedium.copy(fontSize = 10.sp),
                        color = NexaraColors.OnSurfaceVariant
                    )
                }
            }
        }
    }
}
