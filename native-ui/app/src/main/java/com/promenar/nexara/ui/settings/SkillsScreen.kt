package com.promenar.nexara.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.text.font.FontWeight
import com.promenar.nexara.R
import com.promenar.nexara.ui.common.NexaraSettingsPageLayout
import com.promenar.nexara.ui.common.BettboxListGroup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(context.applicationContext as android.app.Application))

    val skillIcons = remember {
        mapOf(
            "web_search" to Icons.Rounded.Search,
            "web_fetch" to Icons.Rounded.Description,
            "search_tavily" to Icons.Rounded.Search,
            "search_searxng" to Icons.Rounded.Search,
            "calculator" to Icons.Rounded.Build,
            "image_generation" to Icons.Rounded.Image,
            "file_read" to Icons.Rounded.Description,
            "file_write" to Icons.Rounded.Edit,
            "file_list" to Icons.Rounded.Folder,
            "file_search" to Icons.Rounded.Search,
            "file_diff" to Icons.Rounded.Sync,
            "file_patch" to Icons.Rounded.Build,
            "exec_js" to Icons.Rounded.Code,
            "initialize_plan" to Icons.Rounded.AccountTree,
            "update_plan" to Icons.Rounded.Edit,
            "get_plan" to Icons.Rounded.Visibility,
            "drop_plan" to Icons.Rounded.Cancel
        )
    }
    val presetSkills by viewModel.skills.collectAsState()
    val userSkills by viewModel.userSkills.collectAsState()
    val mcpServers by viewModel.mcpServers.collectAsState()
    val loopLimit by viewModel.loopLimit.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf(
        stringResource(R.string.skills_tab_preset),
        stringResource(R.string.skills_tab_user),
        stringResource(R.string.skills_tab_mcp)
    )

    var showAddMcp by remember { mutableStateOf(false) }
    var showCreateSkill by remember { mutableStateOf(false) }
    var showSearchConfig by remember { mutableStateOf<String?>(null) }
    var selectedSkillForEdit by remember { mutableStateOf<String?>(null) }
    var expandedServerId by remember { mutableStateOf<String?>(null) }

    NexaraSettingsPageLayout(
        title = stringResource(R.string.skills_title),
        onBack = onNavigateBack,
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(contentPadding),
        ) {

            BettboxListGroup {
                ListItem(
                    headlineContent = {
                    Text(stringResource(R.string.skills_loop_limit), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                },
                supportingContent = {
                    Text(stringResource(R.string.skills_loop_limit_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        IconButton(
                            onClick = { viewModel.updateLoopLimit((loopLimit - 1).coerceAtLeast(1)) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Rounded.Remove, contentDescription = stringResource(R.string.common_cd_decrease))
                        }
                        Text(
                            text = if (loopLimit >= 100) stringResource(R.string.skills_unlimited) else "$loopLimit",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        IconButton(
                            onClick = { viewModel.updateLoopLimit((loopLimit + 1).coerceAtMost(100)) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = stringResource(R.string.common_cd_increase))
                        }
                    }
                },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                divider = {
                    HorizontalDivider(
                        thickness = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                },
                indicator = { tabPositions ->
                    if (selectedTab < tabPositions.size) {
                        val pos = tabPositions[selectedTab]
                        Box(
                            Modifier
                                .tabIndicatorOffset(pos)
                                .padding(horizontal = 48.dp)
                                .height(3.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.primary)
                        )
                    }
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = {
                            Text(
                                title,
                                style = MaterialTheme.typography.labelMedium.copy(
                                    fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal
                                ),
                                color = if (selectedTab == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            BettboxListGroup {
            when (selectedTab) {
                0 -> {
                    presetSkills.forEach { skill ->
                        SkillItem(
                            skill = skill,
                            icon = skillIcons[skill.id] ?: Icons.Rounded.Code,
                            onToggle = { viewModel.toggleSkill(skill.id) },
                            onConfig = if (skill.id in listOf("web_search", "search_tavily", "search_searxng")) {
                                { showSearchConfig = skill.id }
                            } else null
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                1 -> {
                    userSkills.forEach { skill ->
                        UserSkillItem(
                            id = skill.id,
                            name = skill.name,
                            description = skill.description,
                            enabled = skill.enabled,
                            icon = Icons.Rounded.Code,
                            onToggle = { viewModel.toggleSkill(skill.id) },
                            onEdit = {
                                selectedSkillForEdit = skill.id
                                showCreateSkill = true
                            },
                            onDelete = { viewModel.deleteCustomSkill(skill.id) }
                        )
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }

                    if (userSkills.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.skills_user_empty),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            selectedSkillForEdit = null
                            showCreateSkill = true
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.skills_add_custom))
                    }
                }
                2 -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { showAddMcp = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(R.string.skills_add_mcp))
                        }

                        if (mcpServers.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    stringResource(R.string.skills_mcp_empty),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        mcpServers.forEach { server ->
                            McpServerItem(
                                server = server,
                                isExpanded = expandedServerId == server.id,
                                onToggleExpand = { expandedServerId = if (expandedServerId == server.id) null else server.id },
                                onToggleEnabled = { enabled -> viewModel.toggleMcpServer(server.id, enabled) },
                                onDelete = { viewModel.deleteMcpServer(server.id) },
                                onSync = { viewModel.syncMcpServer(server.id) },
                            )
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
            }

            Spacer(modifier = Modifier.height(120.dp))
        }
    }

    if (showAddMcp) {
        var mcpName by remember { mutableStateOf("") }
        var mcpUrl by remember { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = { showAddMcp = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(R.string.skills_add_mcp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)

                OutlinedTextField(
                    value = mcpName,
                    onValueChange = { mcpName = it },
                    label = { Text(stringResource(R.string.skills_mcp_name_placeholder)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = mcpUrl,
                    onValueChange = { mcpUrl = it },
                    label = { Text(stringResource(R.string.skills_mcp_url_placeholder)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.skills_mcp_https_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Button(
                    onClick = {
                        if (mcpName.isNotBlank() && mcpUrl.startsWith("https://", ignoreCase = true)) {
                            viewModel.addMcpServer(mcpName, mcpUrl, "http")
                            showAddMcp = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.skills_mcp_add_btn))
                }
            }
        }
    }

    if (showCreateSkill) {
        val skillToEdit = selectedSkillForEdit?.let { id -> userSkills.find { it.id == id } }
        var skillName by remember { mutableStateOf(skillToEdit?.name ?: "") }
        var skillDesc by remember { mutableStateOf(skillToEdit?.description ?: "") }
        var skillCode by remember { mutableStateOf(skillToEdit?.code ?: "") }

        ModalBottomSheet(
            onDismissRequest = { showCreateSkill = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
                    .padding(bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = if (selectedSkillForEdit == null) stringResource(R.string.skills_add_custom)
                           else stringResource(R.string.skills_edit_custom),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Text(stringResource(R.string.skills_metadata), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

                OutlinedTextField(
                    value = skillName,
                    onValueChange = { skillName = it },
                    label = { Text(stringResource(R.string.skills_tool_name_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = skillDesc,
                    onValueChange = { skillDesc = it },
                    label = { Text(stringResource(R.string.skills_description_hint)) },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(stringResource(R.string.skills_implementation), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

                OutlinedTextField(
                    value = skillCode,
                    onValueChange = { skillCode = it },
                    label = { Text(stringResource(R.string.skills_code_example)) },
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                )

                Button(
                    onClick = {
                        if (skillName.isNotBlank()) {
                            viewModel.addCustomSkill(
                                name = skillName,
                                description = skillDesc,
                                schema = "{}",
                                code = skillCode,
                                id = selectedSkillForEdit
                            )
                            showCreateSkill = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.shared_btn_save))
                }
            }
        }
    }

    if (showSearchConfig != null) {
        SearchConfigBottomSheet(
            skillId = showSearchConfig!!,
            onDismiss = { showSearchConfig = null }
        )
    }
}

@Composable
private fun SkillItem(
    skill: SkillInfo,
    icon: ImageVector,
    onToggle: () -> Unit,
    onConfig: (() -> Unit)? = null
) {
    var enabled by remember { mutableStateOf(skill.enabled) }

    ListItem(
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
        },
        headlineContent = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(skill.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    text = skill.id,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        supportingContent = {
            Text(skill.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingContent = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onConfig != null) {
                    IconButton(onClick = onConfig, modifier = Modifier.size(48.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.common_cd_config),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = { enabled = it; onToggle() }
                )
            }
        }
    )
}

@Composable
private fun UserSkillItem(
    id: String,
    name: String,
    description: String,
    enabled: Boolean,
    icon: ImageVector,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var isEnabled by remember { mutableStateOf(enabled) }

    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                }
            },
            headlineContent = {
                Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            },
            supportingContent = {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            },
            trailingContent = {
                Switch(
                    checked = isEnabled,
                    onCheckedChange = { isEnabled = it; onToggle() }
                )
            }
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onEdit) {
                Icon(Icons.Rounded.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.skills_configure))
            }
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.shared_btn_delete))
            }
        }
    }
}

@Composable
private fun McpServerItem(
    server: McpServerUiModel,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onSync: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            if (server.isConnected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                            CircleShape
                        )
                )
            },
            headlineContent = {
                Text(server.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            },
            supportingContent = {
                Column {
                    Text(server.url, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when {
                            !server.isSupported -> stringResource(R.string.skills_mcp_legacy_unsupported)
                            server.syncError != null -> stringResource(R.string.skills_mcp_sync_error, server.syncError)
                            else -> stringResource(R.string.skills_mcp_modern_transport)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (server.isSupported && server.syncError == null) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                }
            },
            trailingContent = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onSync, enabled = server.isSupported && server.isEnabled) {
                        Icon(Icons.Rounded.Sync, contentDescription = stringResource(R.string.common_cd_sync))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Rounded.Delete, contentDescription = stringResource(R.string.common_cd_delete), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = server.isEnabled,
                onCheckedChange = onToggleEnabled
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.skills_mcp_enabled), style = MaterialTheme.typography.bodyMedium)

        }

        if (server.tools.isNotEmpty()) {
            ListItem(
                modifier = Modifier.clickable { onToggleExpand() },
                headlineContent = {
                    Text(
                        text = stringResource(R.string.skills_mcp_tools, server.tools.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingContent = {
                    Icon(
                        imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.ChevronRight,
                        contentDescription = null
                    )
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent)
            )
            AnimatedVisibility(visible = isExpanded) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 32.dp, end = 16.dp, bottom = 8.dp)
                ) {
                    server.tools.forEach { tool ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.Code, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                            Text(tool, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace), color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchConfigBottomSheet(
    skillId: String,
    onDismiss: () -> Unit
) {
    val application = LocalContext.current.applicationContext as android.app.Application
    val searchViewModel: SearchConfigViewModel = viewModel(
        factory = SearchConfigViewModel.factory(application)
    )
    val searchState by searchViewModel.uiState.collectAsState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        dragHandle = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .padding(vertical = 12.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), CircleShape)
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = when (skillId) {
                    "web_search" -> stringResource(R.string.search_sheet_web_title)
                    "search_tavily" -> stringResource(R.string.search_sheet_tavily_title)
                    "search_searxng" -> stringResource(R.string.search_sheet_searxng_title)
                    else -> stringResource(R.string.search_sheet_title)
                },
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            if (skillId == "web_search") {
                Text(stringResource(R.string.search_engine_select), style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EngineOption("duckduckgo", stringResource(R.string.search_engine_duckduckgo), searchState.searchEngine == "duckduckgo") {
                        searchViewModel.updateSearchEngine("duckduckgo")
                    }
                    EngineOption("tavily", stringResource(R.string.search_engine_tavily), searchState.searchEngine == "tavily") {
                        searchViewModel.updateSearchEngine("tavily")
                    }
                    EngineOption("searxng", stringResource(R.string.search_engine_searxng), searchState.searchEngine == "searxng") {
                        searchViewModel.updateSearchEngine("searxng")
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.search_count_label) + ": ${searchState.resultCount}",
                    style = MaterialTheme.typography.labelMedium
                )
                Slider(
                    value = searchState.resultCount.toFloat(),
                    valueRange = 1f..20f,
                    steps = 18,
                    onValueChange = { searchViewModel.updateResultCount(it.toInt()) }
                )
            }

            if (skillId == "search_tavily" || (skillId == "web_search" && searchState.searchEngine == "tavily")) {
                Text(stringResource(R.string.search_api_key), style = MaterialTheme.typography.labelMedium)
                TavilySecretEditor(searchViewModel, searchState)

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.search_depth_label), style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DepthChip("basic", stringResource(R.string.search_depth_basic), searchState.searchDepth == "basic") {
                            searchViewModel.updateSearchDepth("basic")
                        }
                        DepthChip("advanced", stringResource(R.string.search_depth_advanced), searchState.searchDepth == "advanced") {
                            searchViewModel.updateSearchDepth("advanced")
                        }
                    }
                }
            }

            if (skillId == "search_searxng" || (skillId == "web_search" && searchState.searchEngine == "searxng")) {
                Text(stringResource(R.string.search_instance_url), style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = searchState.searXngUrl,
                    onValueChange = { searchViewModel.updateSearXngUrl(it) },
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = TextStyle(fontFamily = FontFamily.Monospace)
                )
            }
        }
    }
}

@Composable
internal fun EngineOption(id: String, label: String, isSelected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f) else Color.Transparent)
            .border(
                width = if (isSelected) 1.dp else 0.5.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            )
            .defaultMinSize(minHeight = 48.dp)
            .testTag("search_engine_$id")
            .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
        if (isSelected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun DepthChip(id: String, label: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
            .defaultMinSize(minHeight = 48.dp)
            .testTag("search_depth_$id")
            .selectable(
                selected = isSelected,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
