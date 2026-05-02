package com.promenar.nexara.native.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.promenar.nexara.native.ui.common.NexaraGlassCard
import com.promenar.nexara.native.ui.common.NexaraSettingsItem
import com.promenar.nexara.native.ui.theme.NexaraColors
import com.promenar.nexara.native.ui.theme.NexaraShapes
import com.promenar.nexara.native.ui.theme.NexaraTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserSettingsHomeScreen(
    onNavigateToAdvancedRAG: () -> Unit
) {
    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text("Settings", style = NexaraTypography.headlineLarge) },
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
                start = 20.dp, end = 20.dp, // safe-margin
                top = 24.dp, bottom = 120.dp // pt-[120px] equivalent, bottom clear of Nav bar
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp) // gap-sm between items
        ) {
            // The User Profile Gradient Header
            item {
                UserProfileHeader()
                Spacer(modifier = Modifier.height(24.dp)) // gap-lg before list
            }

            item {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Psychology,
                    title = "Model & Inference",
                    subtitle = "GPT-4 Turbo, Temp 0.7",
                    onClick = {}
                )
            }
            item {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Storage,
                    title = "Knowledge Base",
                    subtitle = "3 Active Sources",
                    onClick = onNavigateToAdvancedRAG
                )
            }
            item {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Search,
                    title = "Search Configurations",
                    subtitle = "Tavily Deep Search Enabled",
                    onClick = {}
                )
            }
            item {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Dashboard,
                    title = "Workbench / Skills",
                    subtitle = "14 Functions Ready",
                    onClick = {}
                )
            }
            item {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Analytics,
                    title = "Token Usage & Billing",
                    subtitle = "$12.40 this month",
                    onClick = {}
                )
            }
            item {
                NexaraSettingsItem(
                    icon = Icons.Rounded.Palette,
                    title = "Appearance & Theme",
                    subtitle = "Premium Dark Mode",
                    onClick = {}
                )
            }
        }
    }
}

@Composable
private fun UserProfileHeader() {
    // The gradient border wrapper logic
    // bg-gradient-to-br from-primary-container to-tertiary-container opacity-50
    val gradientBrush = Brush.linearGradient(
        colors = listOf(
            NexaraColors.PrimaryContainer.copy(alpha = 0.5f),
            NexaraColors.TertiaryContainer.copy(alpha = 0.5f)
        )
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(NexaraShapes.large) // rounded-xl
            .background(gradientBrush)
            .padding(1.dp) // p-[1px] creates the "border" effect
    ) {
        // Inner Glass Panel
        NexaraGlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = NexaraShapes.large as androidx.compose.foundation.shape.RoundedCornerShape
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NexaraColors.SurfaceContainer.copy(alpha = 0.9f)) // bg-surface-container/90
                    .padding(16.dp), // p-md
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Mock Avatar (Initials instead of image for now)
                Box(
                    modifier = Modifier
                        .size(64.dp) // w-16 h-16
                        .background(NexaraColors.SurfaceHigh, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("AN", style = NexaraTypography.headlineMedium, color = NexaraColors.Primary)
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Alex Nova",
                        style = NexaraTypography.headlineMedium,
                        color = NexaraColors.OnSurface
                    )
                    Text(
                        text = "Pro Plan • Active",
                        style = NexaraTypography.bodyMedium,
                        color = NexaraColors.OnSurfaceVariant
                    )
                }

                IconButton(
                    onClick = { },
                    modifier = Modifier
                        .background(Color.White.copy(alpha = 0.05f), CircleShape) // hover:bg-white/5
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = "Edit Profile",
                        tint = NexaraColors.Primary
                    )
                }
            }
        }
    }
}
