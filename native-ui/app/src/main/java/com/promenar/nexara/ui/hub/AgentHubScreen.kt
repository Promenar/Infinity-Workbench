package com.promenar.nexara.ui.hub

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.core.graphics.toColorInt
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.clickable
import androidx.compose.ui.text.style.TextOverflow
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.*
import com.promenar.nexara.ui.theme.NexaraColors
import com.promenar.nexara.ui.theme.NexaraTypography

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AgentHubScreen(
    onNavigateToSessionList: (String) -> Unit,
    onNavigateToAgentEdit: (String) -> Unit
) {
    val context = LocalContext.current
    val viewModel: AgentHubViewModel = viewModel(factory = AgentHubViewModel.factory(context.applicationContext as android.app.Application))
    val agents by viewModel.agents.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }

    var agentToDelete by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = NexaraColors.CanvasBackground,
        contentWindowInsets = WindowInsets.statusBars,
        topBar = {
            TopAppBar(
                title = {
                    Box(modifier = Modifier.padding(start = 4.dp)) {
                        Text(stringResource(R.string.hub_title), style = NexaraTypography.headlineLarge)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = stringResource(R.string.hub_btn_add_agent),
                            tint = NexaraColors.OnSurface,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NexaraColors.CanvasBackground.copy(alpha = 0.8f),
                    titleContentColor = NexaraColors.OnSurface
                )
            )
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

        ConfirmDialog(
            show = agentToDelete != null,
            onDismiss = { agentToDelete = null },
            onConfirm = {
                agentToDelete?.let { viewModel.deleteAgent(it) }
                agentToDelete = null
            },
            title = stringResource(R.string.agent_edit_delete_title),
            description = stringResource(R.string.agent_edit_delete_message),
            confirmLabel = stringResource(R.string.agent_edit_delete_confirm),
            destructive = true
        )

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
                    top = 8.dp, bottom = 24.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {

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
                        Color(agent.color.toColorInt())
                    } catch (_: Exception) {
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
                        onDelete = { agentToDelete = agent.id },
                        onEdit = { onNavigateToAgentEdit(agent.id) },
                        onClick = { onNavigateToSessionList(agent.id) }
                    )
                }
            }
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
    onEdit: () -> Unit,
    onClick: () -> Unit
) {
    SwipeableItem(
        onPin = onPin,
        onDelete = onDelete,
        onEdit = onEdit,
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
    val pm = ProviderManager.getInstance()
    val defaultModel by pm.summaryModelId.collectAsState()
    
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(defaultModel) }
    var systemPrompt by remember { mutableStateOf("") }
    var showPromptEditor by remember { mutableStateOf(false) }

    UnifiedPromptEditor(
        show = showPromptEditor,
        onDismiss = { showPromptEditor = false },
        onSave = {
            systemPrompt = it
            showPromptEditor = false
        },
        title = stringResource(R.string.hub_dialog_label_prompt),
        initialText = systemPrompt,
        placeholder = stringResource(R.string.agent_edit_prompt_placeholder),
        mode = EditorMode.DIALOG
    )

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
                
                Spacer(modifier = Modifier.height(4.dp))
                
                NexaraGlassCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showPromptEditor = true },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = stringResource(R.string.hub_dialog_label_prompt),
                            style = NexaraTypography.labelMedium,
                            color = NexaraColors.OnSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = systemPrompt.ifBlank { stringResource(R.string.agent_edit_prompt_hint) },
                            style = NexaraTypography.bodyMedium,
                            color = if (systemPrompt.isNotBlank()) NexaraColors.OnSurface else NexaraColors.OnSurfaceVariant.copy(alpha = 0.5f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
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
