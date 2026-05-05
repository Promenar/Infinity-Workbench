package com.promenar.nexara.ui.hub

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraGlassCard
import com.promenar.nexara.ui.common.NexaraSearchBar
import com.promenar.nexara.ui.common.SwipeableItem
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentHubScreen(
    onNavigateToSessionList: (String) -> Unit,
    onNavigateToAgentEdit: (String) -> Unit,
    onNavigateToSuperChat: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: AgentHubViewModel = viewModel(factory = AgentHubViewModel.factory(context.applicationContext as android.app.Application))
    val agents by viewModel.agents.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.systemBars,
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToSuperChat,
                containerColor = NexaraColors.Primary,
                contentColor = NexaraColors.OnPrimary,
                shape = CircleShape,
                modifier = Modifier.size(56.dp),
                elevation = FloatingActionButtonDefaults.elevation(
                    defaultElevation = 8.dp,
                    pressedElevation = 12.dp
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.AutoAwesome,
                    contentDescription = stringResource(R.string.hub_fab_super),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    ) { paddingValues ->
        if (showAddDialog) {
            AddAgentDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { name, desc, model, systemPrompt ->
                    viewModel.createAgent(name, desc, model, systemPrompt)
                    showAddDialog = false
                }
            )
        }

        if (agents.isEmpty() && searchQuery.isEmpty()) {
            EmptyAgentState(
                onCreateAgent = { showAddDialog = true },
                modifier = Modifier.padding(paddingValues)
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(
                    start = 20.dp, end = 20.dp,
                    top = 8.dp, bottom = 120.dp
                )
            ) {
                item {
                    AgentHubHeader(onAddClick = { showAddDialog = true })
                    Spacer(modifier = Modifier.height(16.dp))
                }

                stickyHeader {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NexaraColors.CanvasBackground.copy(alpha = 0.9f))
                            .padding(bottom = 12.dp)
                    ) {
                        NexaraSearchBar(
                            value = searchQuery,
                            onValueChange = { viewModel.updateSearchQuery(it) },
                             placeholder = stringResource(R.string.hub_search_placeholder)
                        )
                    }
                }

                itemsIndexed(agents, key = { _, agent -> agent.id }) { _, agent ->
                    val parsedColor = try {
                        Color(android.graphics.Color.parseColor(agent.color))
                    } catch (e: Exception) {
                        NexaraColors.Primary
                    }

                    val iconVector = agentIconVector(agent.icon)

                    AgentCardItem(
                        icon = iconVector,
                        title = agent.name,
                        subtitle = agent.description,
                        iconContainerColor = parsedColor,
                        isPinned = agent.isPinned,
                        onPin = { viewModel.togglePin(agent.id) },
                        onDelete = { viewModel.deleteAgent(agent.id) },
                        onClick = { onNavigateToSessionList(agent.id) }
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun AgentHubHeader(onAddClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.hub_title),
                style = NexaraTypography.headlineLarge,
                color = NexaraColors.OnSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.hub_subtitle),
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.OnSurfaceVariant
            )
        }

        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(NexaraColors.GlassSurface)
                .clickable(onClick = onAddClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Add,
                contentDescription = stringResource(R.string.hub_btn_add_agent),
                tint = NexaraColors.OnSurface,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
fun AgentCardItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    iconContainerColor: Color,
    isPinned: Boolean = false,
    onPin: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit
) {
    SwipeableItem(
        onPin = onPin,
        onDelete = onDelete,
        isPinned = isPinned
    ) {
        NexaraGlassCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            onClick = onClick
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconContainerColor),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = NexaraTypography.headlineMedium.copy(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        ),
                        color = NexaraColors.OnSurface,
                        maxLines = 1
                    )
                    if (subtitle.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = NexaraTypography.bodyMedium.copy(fontSize = 13.sp),
                            color = NexaraColors.OnSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }

                if (isPinned) {
                    Icon(
                        imageVector = Icons.Rounded.PushPin,
                        contentDescription = stringResource(R.string.common_cd_pin),
                        tint = NexaraColors.Primary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    tint = NexaraColors.Outline,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
private fun EmptyAgentState(
    onCreateAgent: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp)
        ) {
            Text(
                text = "✨",
                fontSize = 48.sp
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.hub_empty_title),
                style = NexaraTypography.headlineMedium,
                color = NexaraColors.OnSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.hub_empty_subtitle),
                style = NexaraTypography.bodyMedium,
                color = NexaraColors.OnSurfaceVariant
            )
            Spacer(modifier = Modifier.height(24.dp))
            FilledTonalButton(
                onClick = onCreateAgent,
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = NexaraColors.Primary.copy(alpha = 0.15f),
                    contentColor = NexaraColors.Primary
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.hub_btn_add_agent))
            }
        }
    }
}

@Composable
private fun AddAgentDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("gpt-4o") }
    var systemPrompt by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.hub_dialog_add_title), style = NexaraTypography.headlineSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.hub_dialog_label_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.hub_dialog_label_desc)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text(stringResource(R.string.hub_dialog_label_model)) },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = systemPrompt,
                    onValueChange = { systemPrompt = it },
                    label = { Text(stringResource(R.string.hub_dialog_label_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, description, model, systemPrompt) },
                enabled = name.isNotBlank()
            ) {
                Text(stringResource(R.string.shared_btn_add))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_btn_cancel))
            }
        },
        containerColor = NexaraColors.SurfaceDim,
        titleContentColor = NexaraColors.OnSurface,
        textContentColor = NexaraColors.OnSurfaceVariant
    )
}

private fun agentIconVector(icon: String): ImageVector = when (icon) {
    "💻" -> Icons.Rounded.Code
    "📝" -> Icons.Rounded.EditNote
    "🌐", "A" -> Icons.Rounded.Translate
    else -> Icons.Rounded.SmartToy
}
