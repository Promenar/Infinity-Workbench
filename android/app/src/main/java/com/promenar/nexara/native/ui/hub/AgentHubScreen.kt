package com.promenar.nexara.native.ui.hub

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.promenar.nexara.native.bridge.NexaraBridge
import com.promenar.nexara.native.ui.theme.NexaraColors
import com.promenar.nexara.native.ui.theme.NexaraTypography

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentHubScreen(
    onNavigateToChat: () -> Unit
) {
    val agents by NexaraBridge.agents.collectAsState()

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars,
        topBar = {
            TopAppBar(
                title = { Text("Agents", style = NexaraTypography.headlineLarge) },
                actions = {
                    IconButton(onClick = { }) {
                        Icon(Icons.Rounded.Search, contentDescription = "Search", tint = NexaraColors.OnSurface)
                    }
                    IconButton(onClick = { }) {
                        Icon(Icons.Rounded.Add, contentDescription = "Add", tint = NexaraColors.OnSurface)
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
                start = 20.dp, end = 20.dp, // px-safe-margin
                top = 16.dp, bottom = 120.dp // pb-[100px] equivalent for BottomBar clearance
            )
        ) {
            items(agents) { agent ->
                // Helper to parse hex colors or fallback
                val parsedColor = try {
                    Color(android.graphics.Color.parseColor(agent.color))
                } catch (e: Exception) {
                    NexaraColors.PrimaryContainer
                }
                
                // Helper to map string icon to ImageVector (simplified for now)
                val iconVector = when(agent.icon) {
                    "💻" -> Icons.Rounded.Code
                    "📝" -> Icons.Rounded.EditNote
                    "🌐", "A" -> Icons.Rounded.Translate
                    else -> Icons.Rounded.SmartToy
                }

                AgentListItem(
                    icon = iconVector,
                    title = agent.name,
                    subtitle = agent.description,
                    iconContainerColor = parsedColor,
                    iconTintColor = NexaraColors.OnPrimaryContainer, // Ideal to calculate contrast color, fallback for now
                    onClick = onNavigateToChat
                )
            }
        }
    }
}

@Composable
private fun AgentListItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconContainerColor: androidx.compose.ui.graphics.Color,
    iconTintColor: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp)) // rounded-DEFAULT
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp, horizontal = 8.dp), // py-md px-sm
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icon Container (w-[40px] h-[40px] rounded-[12px])
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(iconContainerColor, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTintColor,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp)) // mr-md

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = NexaraTypography.headlineMedium,
                    color = NexaraColors.OnSurface,
                    maxLines = 1
                )
                Text(
                    text = subtitle,
                    style = NexaraTypography.bodyMedium,
                    color = NexaraColors.OnSurfaceVariant,
                    maxLines = 1
                )
            }

            // Pin button (mock)
            IconButton(onClick = {}) {
                Icon(Icons.Rounded.PushPin, contentDescription = "Pin", tint = NexaraColors.OnSurfaceVariant)
            }
        }

        // Divider (glass-border-b)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(NexaraColors.GlassBorder)
        )
    }
}
