package com.promenar.nexara.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraShapes
import com.promenar.nexara.ui.theme.NexaraTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionSettingsScreen(
    onNavigateBack: () -> Unit
) {
    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text("Session Settings", style = NexaraTypography.headlineMedium) },
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
                top = 24.dp, bottom = 120.dp
            ),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            item {
                Text("General", style = NexaraTypography.headlineLarge, color = NexaraColors.OnSurface)
                Spacer(modifier = Modifier.height(16.dp))
                
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
                        // Agent Reference
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(NexaraColors.SurfaceHigh),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Rounded.SmartToy, contentDescription = null, tint = NexaraColors.Primary)
                                }
                                Column {
                                    Text("Active Agent", style = NexaraTypography.labelMedium, color = NexaraColors.OnSurfaceVariant)
                                    Text("Super Assistant", style = NexaraTypography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium), color = NexaraColors.OnSurface)
                                }
                            }
                            IconButton(onClick = { }) {
                                Icon(Icons.Rounded.SwapHoriz, contentDescription = "Swap", tint = NexaraColors.Primary)
                            }
                        }
                        
                        // Divider
                        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(NexaraColors.GlassBorder))
                        
                        // Other controls can be added here
                    }
                }
            }
        }
    }
}
